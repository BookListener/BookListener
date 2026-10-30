#!/usr/bin/env python3
"""
批量写入 m4b 的三段式标签（作品层 / 系列层 / 部层 / 集层）。

设计要点：
- 系列层正解是 freeform ---- 原子（mutagen key 形如 "----:com.apple.iTunes:SERIES"），
  共 6 个：com.apple.iTunes 大写 SERIES/SERIES-PART + 小写 series/series-part 镜像
  + com.booklistener 大写 SERIES/SERIES-PART。©ser/©sno 为非标准冗余，一并写入。
- freeform 值必须 MP4FreeForm(bytes)，直接赋 str 会在 save() 时报
  TypeError: can't concat str to bytes（mutagen 1.48.1 实测）。
- ffmpeg 实测丢弃全部 freeform 与 ©ser/©sno，系列层只能用 mutagen 写。
- album(©alb) 必须是「每卷自己的名字」，不要把所有卷并成同一个专辑。
- 卷号放 disk / SERIES-PART / ©sno；**单文件 m4b 不写 trkn**（foobar 等会用文件级
  trkn 覆盖每章序号，详见 references/m4b-tag-model.md「trkn 陷阱」）。

用法：
    python tag_m4b.py            # dry-run，只打印将要写什么
    python tag_m4b.py --apply    # 真正写入
"""
import os
import re
import sys

from mutagen.mp4 import MP4, MP4FreeForm

# ================= 改这里 =================
CONFIG = {
    # m4b 所在目录（不递归）
    "out_dir": r"E:/path/to/audiobook/m4b",

    # 从文件名抽取部号的正则（第一个捕获组=部号整数）
    # 例："凡人修仙传 第03卷 制符之道.m4b" -> 3
    "disc_regex": r"第(\d+)卷",

    # 不属于正片的册（预告/番外）：文件名包含任一关键词即视为「独立册」
    "special_keywords": ("预告", "番外", "序章"),

    # ---- 作品层（所有文件共享）----
    "author": "忘语",          # aART 专辑艺术家 + ©aut 作者
    "narrator": "光合积木",     # ©ART 表演者（演播）
    "genre": "有声书",          # ©gen
    "stik": 2,                 # 2 = 有声书

    # ---- 系列层 ----
    "series": "凡人修仙传",      # 系列名（写入 6 freeform + ©ser）
    # 独立册的系列序号；None 表示独立册不写系列层（完全脱离系列）
    "special_series_no": "0",
    # freeform 命名空间：com.apple.iTunes（ABS/Plex/Apple Books 事实标准）
    # + com.booklistener（BookListener 播放器）
    "series_namespaces": ("com.apple.iTunes", "com.booklistener"),
    # 是否在 com.apple.iTunes 下附小写 series/series-part 镜像（ABS/Plex 兼容）
    "series_lowercase_mirror": True,
    "disc_total": 11,          # disk=(n, disc_total)

    # ---- 集层 ----
    "trkn": None,             # 不写：foobar 等会用文件级 trkn 覆盖每章序号（详见 m4b-tag-model.md「trkn 陷阱」）

    # 是否清掉其它可疑残留字段（一般不需要改）
    "strip_keys": (),
}
# ========================================

TAG_NAMES = {
    "\xa9nam": "标题",
    "\xa9alb": "专辑(部名)",
    "\xa9ART": "表演者(演播)",
    "aART": "专辑艺术家(作者)",
    "\xa9aut": "作者",
    "\xa9gen": "流派",
    "\xa9ser": "系列名(冗余)",
    "\xa9sno": "系列序号(冗余)",
    "----:com.apple.iTunes:SERIES": "系列名(freeform)",
    "----:com.apple.iTunes:SERIES-PART": "系列序号(freeform)",
    "----:com.apple.iTunes:series": "系列名(小写镜像)",
    "----:com.apple.iTunes:series-part": "系列序号(小写镜像)",
    "----:com.booklistener:SERIES": "系列名(booklistener)",
    "----:com.booklistener:SERIES-PART": "系列序号(booklistener)",
    "disk": "碟号",
    "trkn": "曲目号",
    "stik": "媒体类型",
}

# 系列层全部 key（含冗余 ©ser/©sno），独立册脱离系列时用于删除残留
SERIES_KEYS = [
    "\xa9ser", "\xa9sno",
    "----:com.apple.iTunes:SERIES", "----:com.apple.iTunes:SERIES-PART",
    "----:com.apple.iTunes:series", "----:com.apple.iTunes:series-part",
    "----:com.booklistener:SERIES", "----:com.booklistener:SERIES-PART",
]


def title_of(path):
    return os.path.splitext(os.path.basename(path))[0]


def is_special(cfg, path):
    base = title_of(path)
    return any(k in base for k in cfg["special_keywords"])


def disc_of(cfg, path):
    m = re.search(cfg["disc_regex"], title_of(path))
    return int(m.group(1)) if m else None


def freeform(value):
    """freeform ---- 的值必须包成 MP4FreeForm(bytes)；直接赋 str 会 TypeError。"""
    return MP4FreeForm(str(value).encode("utf-8"))


def series_tags(cfg, part):
    """系列层 8 atom；part=None 表示只写系列名不写序号。series 为空则返回 {}。"""
    series = cfg.get("series")
    if not series:
        return {}
    tags = {"\xa9ser": [series]}
    namespaces = cfg.get("series_namespaces", ("com.apple.iTunes",))
    for ns in namespaces:
        tags[f"----:{ns}:SERIES"] = [freeform(series)]
    if cfg.get("series_lowercase_mirror", True) and "com.apple.iTunes" in namespaces:
        tags["----:com.apple.iTunes:series"] = [freeform(series)]

    if part is not None:
        p = str(part)
        tags["\xa9sno"] = [p]
        for ns in namespaces:
            tags[f"----:{ns}:SERIES-PART"] = [freeform(p)]
        if cfg.get("series_lowercase_mirror", True) and "com.apple.iTunes" in namespaces:
            tags["----:com.apple.iTunes:series-part"] = [freeform(p)]
    return tags


def plan_for(cfg, path):
    """返回要写入的 {mutagen_key: value}；value=None 表示删除该 key。"""
    base = title_of(path)
    tags = {"\xa9nam": [base], "\xa9alb": [base]}

    if cfg.get("author"):
        tags["aART"] = [cfg["author"]]
        tags["\xa9aut"] = [cfg["author"]]
    if cfg.get("narrator"):
        tags["\xa9ART"] = [cfg["narrator"]]
    if cfg.get("genre"):
        tags["\xa9gen"] = [cfg["genre"]]
    if cfg.get("stik") is not None:
        tags["stik"] = [int(cfg["stik"])]
    if cfg.get("trkn"):
        tags["trkn"] = [tuple(cfg["trkn"])]

    if is_special(cfg, path):
        sno = cfg.get("special_series_no")
        if sno is None:
            tags.update({k: None for k in SERIES_KEYS})   # 完全脱离系列：删残留
        else:
            tags.update(series_tags(cfg, sno))
            if cfg.get("disc_total"):
                tags["disk"] = [(int(sno), int(cfg["disc_total"]))]
    else:
        n = disc_of(cfg, path)
        if n is None:
            raise SystemExit(f"无法从文件名抽部号（改 disc_regex）: {os.path.basename(path)}")
        tags.update(series_tags(cfg, n))
        if cfg.get("disc_total"):
            tags["disk"] = [(n, int(cfg["disc_total"]))]

    for k in cfg.get("strip_keys", ()):
        tags[k] = None
    return tags


def fmt(v):
    """MP4FreeForm 是 bytes 子类，展示前先解码。"""
    if isinstance(v, (bytes, bytearray)):
        return v.decode("utf-8", "replace")
    return str(v)


def show(mp4, tags):
    for k, v in tags.items():
        label = TAG_NAMES.get(k, k)
        cur = mp4.get(k)
        cur_s = fmt(cur[0]) if cur else "-"
        action = "删除" if v is None else "写入"
        v_s = fmt(v[0]) if isinstance(v, list) and v else v
        print(f"      {label:<18} {k:<38} {cur_s:<20} -> {action} {v_s}")


def apply(rec, tags):
    for k, v in tags.items():
        if v is None:
            rec.pop(k, None)
        else:
            rec[k] = v
    rec.save()


def main():
    cfg = CONFIG
    do_apply = "--apply" in sys.argv
    files = sorted(
        os.path.join(cfg["out_dir"], fn)
        for fn in os.listdir(cfg["out_dir"])
        if fn.lower().endswith(".m4b")
    )
    if not files:
        raise SystemExit(f"{cfg['out_dir']} 下没有 .m4b")

    print(f"共 {len(files)} 个文件，模式：{'写入' if do_apply else 'dry-run'}\n")
    for p in files:
        print(f"  {os.path.basename(p)}")
        rec = MP4(p)
        tags = plan_for(cfg, p)
        show(rec, tags)
        if do_apply:
            apply(rec, tags)
        print()

    if not do_apply:
        print("未改动任何文件。确认无误后加 --apply 重新运行。")
    else:
        print("完成。用 verify_m4b.py 复核。")


if __name__ == "__main__":
    main()
