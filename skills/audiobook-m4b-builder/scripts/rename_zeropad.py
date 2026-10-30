#!/usr/bin/env python3
"""
中文数字卷名 -> 补零阿拉伯数字，修正文件名排序。

问题：卷一/卷二/…/卷十/卷十一 在字符串排序里按 Unicode 码点排，
结果是 一、七、三、九、二、五、八、六、十、十一、四，完全乱序。
Windows 的「自然排序」只处理阿拉伯数字，救不了。

修法：第NN卷（补零到两位），字符串排序即数值排序。

用法：
    python rename_zeropad.py            # dry-run，打印新旧名与排序结果
    python rename_zeropad.py --apply    # 执行（先过冲突检测）
"""
import os
import re
import sys

# ================= 改这里 =================
CONFIG = {
    "dir": r"E:/path/to/audiobook/m4b",
    # 只处理匹配此正则的文件；命名组：prefix / cn / ext
    # cn 同时接受中文数字与阿拉伯数字，便于对已补零的文件重复运行
    "pattern": r"^(?P<prefix>.*?)\s*第(?P<cn>[零一二三四五六七八九十百\d]+)卷(?P<ext>.*)$",
    # 补零宽度
    "width": 2,
    # 卷名前缀（用于生成新名时保持可读）
    "vol_word": "卷",
}
# ========================================

CN_DIGIT = {"零": 0, "一": 1, "二": 2, "三": 3, "四": 4,
            "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def cn2int(s):
    """中文数字转整数，支持 十一 / 二十一 / 一百零五 这类写法。"""
    if not s:
        raise ValueError("空的中文数字")
    if s.isdigit():
        return int(s)

    total, section, num = 0, 0, 0
    for ch in s:
        if ch in CN_DIGIT:
            num = CN_DIGIT[ch]
        elif ch == "十":
            section += (num or 1) * 10
            num = 0
        elif ch == "百":
            section += (num or 1) * 100
            num = 0
        elif ch == "千":
            section += (num or 1) * 1000
            num = 0
        else:
            raise ValueError(f"无法解析的中文数字: {s}")
    total = section + num
    if total == 0 and "零" in s:
        return 0
    return total


def build_map(cfg):
    pat = re.compile(cfg["pattern"])
    pairs = []
    for fn in sorted(os.listdir(cfg["dir"])):
        m = pat.match(fn)
        if not m:
            continue
        n = cn2int(m.group("cn"))
        new = f"{m.group('prefix').strip()} 第{n:0{cfg['width']}d}{cfg['vol_word']}{m.group('ext')}"
        pairs.append((fn, new))
    return pairs


def main():
    cfg = CONFIG
    do_apply = "--apply" in sys.argv
    pairs = build_map(cfg)
    if not pairs:
        raise SystemExit("没有匹配到需要改名的文件（检查 CONFIG['pattern']）")

    todo = [(a, b) for a, b in pairs if a != b]
    print(f"匹配 {len(pairs)} 个，需改名 {len(todo)} 个\n")
    for a, b in pairs:
        mark = "==" if a == b else "->"
        print(f"  {a}\n  {mark} {b}\n")

    # 排序预览
    before = sorted(a for a, _ in pairs)
    after = sorted(b for _, b in pairs)
    print("改名前排序：")
    for x in before:
        print("  " + x)
    print("\n改名后排序：")
    for x in after:
        print("  " + x)

    if not do_apply:
        print("\n未改动任何文件。确认无误后加 --apply 重新运行。")
        return

    if not todo:
        print("\n无需改名。")
        return

    # 冲突检测：目标名不能已存在（除非它本身也在待改名集合里）
    existing = set(os.listdir(cfg["dir"]))
    renaming = {a for a, _ in todo}
    for _, b in todo:
        if b in existing and b not in renaming:
            raise SystemExit(f"目标名已存在，已中止：{b}")

    # 两段式改名，避免 A->B 时 B 仍占坑
    tmp = []
    for i, (a, b) in enumerate(todo):
        t = os.path.join(cfg["dir"], f".__rename_{i}.tmp")
        os.rename(os.path.join(cfg["dir"], a), t)
        tmp.append((t, b))
    for t, b in tmp:
        os.rename(t, os.path.join(cfg["dir"], b))
    print(f"\n已重命名 {len(todo)} 个文件。")


if __name__ == "__main__":
    main()
