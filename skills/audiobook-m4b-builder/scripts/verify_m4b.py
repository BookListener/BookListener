#!/usr/bin/env python3
"""
校验 m4b：章节数、时长、媒体类型、封面尺寸、三段式标签。

两个关键点：
1. mutagen 的 MP4.chapters 最多只读到 255 章（读取上限，非文件缺陷），
   所以章节数绝不能拿 len(rec.chapters) 校验。
2. 机器上不一定装了 ffprobe（imageio-ffmpeg 只带 ffmpeg），
   因此章节数优先用 ffprobe，缺失时用 `ffmpeg -f ffmetadata -` 兜底。

用法：
    python verify_m4b.py <目录>
    python verify_m4b.py <目录> --expect 54,46,144   # 按文件名顺序比对应有章节数
"""
import json
import os
import re
import subprocess
import sys

from mutagen.mp4 import MP4

DEFAULT_DIR = r"E:/path/to/audiobook/m4b"


# ---------- 外部工具定位 ----------

def find_ffmpeg():
    for k in ("FFMPEG", "FFMPEG_BIN"):
        if os.environ.get(k) and os.path.exists(os.environ[k]):
            return os.environ[k]
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def find_ffprobe():
    p = os.environ.get("FFPROBE")
    if p and os.path.exists(p):
        return p
    ff = find_ffmpeg()
    cand = os.path.join(os.path.dirname(ff), "ffprobe.exe"
                        if os.name == "nt" else "ffprobe")
    if os.path.exists(cand):
        return cand
    return None


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


# ---------- 章节计数 ----------

def chapters_via_ffprobe(path):
    out = run([find_ffprobe(), "-v", "error", "-print_format", "json",
               "-show_chapters", path]).stdout
    return len(json.loads(out or "{}").get("chapters", []))


def chapters_via_ffmpeg(path):
    """ffmpeg -f ffmetadata 输出里每个 [CHAPTER] 段即一章。"""
    out = run([find_ffmpeg(), "-v", "error", "-i", path,
               "-f", "ffmetadata", "-"]).stdout
    return out.count("[CHAPTER]")


def count_chapters(path):
    if find_ffprobe():
        return chapters_via_ffprobe(path), "ffprobe"
    return chapters_via_ffmpeg(path), "ffmpeg"


# ---------- 封面尺寸 ----------

def jpeg_dims(buf):
    """从 JPEG 字节流里找 SOF 段读宽高；失败返回 None。"""
    i, n = 2, len(buf)
    while i + 9 < n:
        if buf[i] != 0xFF:
            i += 1
            continue
        m = buf[i + 1]
        if m in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            h = (buf[i + 5] << 8) | buf[i + 6]
            w = (buf[i + 7] << 8) | buf[i + 8]
            return w, h
        if m in (0xD8, 0xD9) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        i += 2 + ((buf[i + 2] << 8) | buf[i + 3])
    return None


def png_dims(buf):
    if len(buf) > 24 and buf[:8] == b"\x89PNG\r\n\x1a\n":
        return int.from_bytes(buf[16:20], "big"), int.from_bytes(buf[20:24], "big")
    return None


# ---------- 单项检查 ----------

# 系列层 key：正解是 freeform ----，©ser/©sno 为非标准冗余
SERIES_NAME_KEYS = [
    "----:com.apple.iTunes:SERIES",
    "----:com.apple.iTunes:series",
    "----:com.booklistener:SERIES",
]
SERIES_PART_KEYS = [
    "----:com.apple.iTunes:SERIES-PART",
    "----:com.apple.iTunes:series-part",
    "----:com.booklistener:SERIES-PART",
]


def text_of(v):
    """取第一个值并解码：MP4FreeForm 是 bytes 子类，普通 atom 是 str。"""
    if not v:
        return None
    x = v[0]
    if isinstance(x, (bytes, bytearray)):
        return x.decode("utf-8", "replace")
    return str(x)


def hhmm(sec):
    return f"{int(sec // 3600)}:{int(sec % 3600 // 60):02d}"


def check(path, expect=None, verbose=False):
    rec = MP4(path)
    dur = rec.info.length
    nch, how = count_chapters(path)

    flag = ""
    if expect is not None:
        flag = "  OK" if nch == expect else f"  <== 期望 {expect}，不符！"

    cover = "无"
    if "covr" in rec and len(rec["covr"]):
        b = bytes(rec["covr"][0])
        d = jpeg_dims(b) or png_dims(b)
        cover = f"{d[0]}x{d[1]}" if d else f"?({len(b)}B)"

    tags = {
        "标题": rec.get("\xa9nam", [None])[0],
        "专辑": rec.get("\xa9alb", [None])[0],
        "系列": text_of(rec.get("----:com.apple.iTunes:SERIES")) or rec.get("\xa9ser", [None])[0],
        "部号": text_of(rec.get("----:com.apple.iTunes:SERIES-PART")) or rec.get("\xa9sno", [None])[0],
        "碟号": rec.get("disk", [None])[0],
        "曲目": rec.get("trkn", [None])[0],
        "作者": rec.get("aART", [None])[0],
        "演播": rec.get("\xa9ART", [None])[0],
        "流派": rec.get("\xa9gen", [None])[0],
        "类型": rec.get("stik", [None])[0],
    }

    print(os.path.basename(path))
    print(f"  章节 {nch}{flag}（{how}）  时长 {hhmm(dur)} ({dur/3600:.2f}h)  "
          f"封面 {cover}  {os.path.getsize(path)/1e9:.2f} GB")
    line = "  ".join(f"{k}={v}" for k, v in tags.items() if v is not None)
    print("  " + line)

    # 一致性提醒
    warns = []
    if tags["类型"] != 2:
        warns.append("stik≠2，播放器可能不当有声书处理")
    if tags["碟号"] and tags["部号"] and str(tags["碟号"][0]) != str(tags["部号"]):
        warns.append(f"disk[0]={tags['碟号'][0]} 与系列序号={tags['部号']} 不一致")
    if tags["系列"]:
        missing = [k for k in SERIES_NAME_KEYS + SERIES_PART_KEYS if k not in rec]
        if missing:
            warns.append(f"缺 freeform: {', '.join(missing)}")
    if warns:
        print("  [WARN] " + "；".join(warns))

    if verbose:
        chs = list(rec.chapters)[:3]
        if chs:
            print("  首章: " + " | ".join((c.title or "") for c in chs))
    return nch, dur


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    d = args[0] if args else DEFAULT_DIR
    expect = None
    if "--expect" in sys.argv:
        i = sys.argv.index("--expect")
        expect = [int(x) for x in sys.argv[i + 1].split(",")]

    if not os.path.isdir(d):
        raise SystemExit(f"目录不存在: {d}")
    files = sorted(os.path.join(d, fn) for fn in os.listdir(d)
                   if fn.lower().endswith(".m4b"))
    if not files:
        raise SystemExit(f"{d} 下没有 .m4b")

    print(f"共 {len(files)} 册；章节计数用 {'ffprobe' if find_ffprobe() else 'ffmpeg(ffmetadata)'}\n")
    total = 0.0
    bad = 0
    for idx, p in enumerate(files):
        exp = expect[idx] if expect and idx < len(expect) else None
        nch, dur = check(p, exp, verbose=True)
        if exp is not None and nch != exp:
            bad += 1
        total += dur
        print()

    print(f"合计 {len(files)} 册，{total/3600:.2f} 小时")
    if expect:
        if len(expect) != len(files):
            print(f"[WARN] --expect 给了 {len(expect)} 个值，文件有 {len(files)} 个")
        print("章节数全部相符" if bad == 0 else f"[FAIL] {bad} 册章节数不符")
        sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
