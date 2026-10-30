#!/usr/bin/env python3
"""
把按「集」拆分的音频，按「卷/部」无损合成为 m4b（stream-copy，不重编码）。
每集 = 一个 chapter；整卷 = 一个 m4b 文件；可选挂单封面与三段式标签。

用法：编辑下方 CONFIG，然后 `python build_m4b.py`。
依赖：imageio-ffmpeg（或自备 ffmpeg 路径）；mutagen（写标签）。
"""
import os
import re
import subprocess
import sys
from mutagen.mp4 import MP4, MP4FreeForm

# ================= 改这里 =================
CONFIG = {
    # 音频所在目录（内含 第NNN集 xxx.m4a 与可选 cover.jpeg）
    "media_dir": r"E:/path/to/audiobook",
    # 输出目录
    "out_dir": r"E:/path/to/audiobook/m4b",
    # 单封面路径（.jpeg/.jpg/.png）；None 表示不挂封面
    "cover": None,
    # ffmpeg 可执行文件；None 时自动用 imageio_ffmpeg
    "ffmpeg": None,
    # 参与合并的音频扩展名
    "audio_exts": (".m4a", ".mp3", ".aac"),
    # 从文件名抽取集号的正则（第一个捕获组=集号）
    "episode_regex": r"第(\d+)集",

    # 卷定义（有序；每卷 start/end 为闭区间集号）。disc 可省略，默认按顺序 1,2,3...
    "volumes": [
        # {"name": "第01卷 七玄门风云", "start": 1,   "end": 54},
        # {"name": "第02卷 初踏修仙路", "start": 55,  "end": 100},
        # ...
    ],

    # 标签（作品层 + 系列层）；None = 不写该字段
    # 系列层正解 = freeform ---- 原子；©ser/©sno 为非标准冗余，一并写入
    "series":   None,      # 系列名：写 ----:com.apple.iTunes:SERIES 等 6 freeform + ©ser
    "author":   None,      # aART(专辑艺术家) + ©aut
    "narrator": None,      # ©ART 表演者（演播）
    "genre":    "有声书",   # ©gen
    "stik":     2,         # 媒体类型 2=有声书
    "disc_total": None,    # 写 disk=(disc, disc_total)；None 则不写 disk
    "trkn":     None,    # 不写：foobar 等会用文件级 trkn 覆盖每章序号；要逐集编号用 cue 或拆分

    # freeform 命名空间：com.apple.iTunes（ABS/Plex/Apple Books 事实标准）
    # + com.booklistener（BookListener 播放器）
    "series_namespaces": ("com.apple.iTunes", "com.booklistener"),
    # 是否在 com.apple.iTunes 下附小写 series/series-part 镜像（ABS/Plex 兼容）
    "series_lowercase_mirror": True,

    # ffmpeg 额外参数（一般不用改）
    "movflags": "+faststart",
}
# ========================================


def get_ffmpeg(cfg):
    if cfg.get("ffmpeg"):
        return cfg["ffmpeg"]
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def index_episodes(cfg):
    """扫目录，返回 {集号: 文件绝对路径}。"""
    pat = re.compile(cfg["episode_regex"])
    files = {}
    for fn in os.listdir(cfg["media_dir"]):
        if not fn.lower().endswith(tuple(e.lower() for e in cfg["audio_exts"])):
            continue
        m = pat.search(fn)
        if not m:
            continue
        ep = int(m.group(1))
        if ep in files:
            raise SystemExit(f"集号重复: ep{ep} -> {fn} 与 {os.path.basename(files[ep])}")
        files[ep] = os.path.join(cfg["media_dir"], fn)
    return files


def meta_of(path):
    a = MP4(path)
    title = a.get("\xa9nam", [None])[0]
    return a.info.length, title


def write_text(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def build_volume(cfg, files, vol, disc_no):
    name = vol["name"]
    s, e = vol["start"], vol["end"]
    eps = sorted(ep for ep in range(s, e + 1) if ep in files)
    missing = [ep for ep in range(s, e + 1) if ep not in files]
    if missing:
        print(f"  [WARN] {name} 缺集: {missing[:10]}{'...' if len(missing) > 10 else ''}")

    ff = get_ffmpeg(cfg)
    out_path = os.path.join(cfg["out_dir"], f"{name}.m4b")
    os.makedirs(cfg["out_dir"], exist_ok=True)
    list_path = os.path.join(cfg["out_dir"], f"_concat_{disc_no}.txt")
    meta_path = os.path.join(cfg["out_dir"], f"_meta_{disc_no}.txt")

    cum = 0.0
    list_lines = []
    meta_lines = [";FFMETADATA1"]
    for ep in eps:
        p = files[ep]
        dur, title = meta_of(p)
        t = title or f"第{ep:04d}集"
        rel = os.path.relpath(p, cfg["out_dir"]).replace("\\", "/")
        list_lines.append(f"file '{rel}'")
        meta_lines += [
            "[CHAPTER]", "TIMEBASE=1/1000",
            f"START={int(round(cum * 1000))}",
            f"END={int(round((cum + dur) * 1000))}",
            f"title={t}",
        ]
        cum += dur
    write_text(list_path, "\n".join(list_lines) + "\n")
    write_text(meta_path, "\n".join(meta_lines) + "\n")

    cmd = [ff, "-y", "-f", "concat", "-safe", "0", "-i", list_path,
           "-i", meta_path]
    cover = cfg.get("cover")
    has_cover = bool(cover and os.path.exists(cover))
    if has_cover:
        cmd += ["-i", cover]
    cmd += ["-map", "0:a"]                      # 只取音轨，避开 mjpeg/数据流
    if has_cover:
        cmd += ["-map", "2", "-disposition:v:0", "attached_pic"]
    cmd += ["-map_chapters", "1", "-map_metadata", "1",
            "-c", "copy"]
    if cfg.get("movflags"):
        cmd += ["-movflags", cfg["movflags"]]
    cmd += [out_path]

    print(f"  building {name}: {len(eps)} 集, {cum/3600:.2f}h")
    r = subprocess.run(cmd, capture_output=True, text=True)
    try:
        os.remove(list_path); os.remove(meta_path)
    except OSError:
        pass
    if r.returncode != 0:
        print("  [FFMPEG ERROR]\n", r.stderr[-1500:])
        return None

    # 标签（ffmpeg 只写了 alb/nam；系列层/stik/disk 用 mutagen 补写——
    # 实测 ffmpeg 拷贝即丢全部 freeform ---- 与 ©ser/©sno，不能依赖它）
    m = MP4(out_path)
    m["\xa9alb"] = [name]
    m["\xa9nam"] = [name]
    if cfg.get("series"):
        series = cfg["series"]
        namespaces = cfg.get("series_namespaces", ("com.apple.iTunes", "com.booklistener"))
        lower = cfg.get("series_lowercase_mirror", True) and "com.apple.iTunes" in namespaces
        m["\xa9ser"] = [series]                                  # 非标准冗余
        for ns in namespaces:
            m[f"----:{ns}:SERIES"] = [MP4FreeForm(series.encode("utf-8"))]
        if lower:
            m["----:com.apple.iTunes:series"] = [MP4FreeForm(series.encode("utf-8"))]
        part = str(disc_no)
        m["\xa9sno"] = [part]                                    # 非标准冗余
        for ns in namespaces:
            m[f"----:{ns}:SERIES-PART"] = [MP4FreeForm(part.encode("utf-8"))]
        if lower:
            m["----:com.apple.iTunes:series-part"] = [MP4FreeForm(part.encode("utf-8"))]
    if cfg.get("author"):
        m["aART"] = [cfg["author"]]
        m["\xa9aut"] = [cfg["author"]]
    if cfg.get("narrator"):
        m["\xa9ART"] = [cfg["narrator"]]
    if cfg.get("genre"):
        m["\xa9gen"] = [cfg["genre"]]
    if cfg.get("stik") is not None:
        m["stik"] = [int(cfg["stik"])]
    if cfg.get("disc_total"):
        m["disk"] = [(disc_no, int(cfg["disc_total"]))]
    if cfg.get("trkn"):
        m["trkn"] = [tuple(cfg["trkn"])]
    m.save()
    print(f"  done: {os.path.getsize(out_path)/1e9:.2f} GB")
    return out_path


def main():
    cfg = CONFIG
    if not cfg["volumes"]:
        raise SystemExit("请在 CONFIG['volumes'] 里填写卷定义")
    files = index_episodes(cfg)
    print(f"索引到 {len(files)} 个音频，集号 {min(files)}..{max(files)}")
    ok = 0
    for i, vol in enumerate(cfg["volumes"], 1):
        disc_no = vol.get("disc", i)
        if build_volume(cfg, files, vol, disc_no):
            ok += 1
    print(f"完成 {ok}/{len(cfg['volumes'])} 卷")


if __name__ == "__main__":
    main()
