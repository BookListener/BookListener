---
id: t3-date-locale
status: in_progress
---

日期与序数本地化（v2.0.14）：
- 定位全部日期显示点：播放详情（"07月12日"）、曲目列表（"2026年07月12日 09点08分"）、文件夹列表、网盘目录等。
- 日期 pattern 入资源：`values`（en）"MMM d" / "MMM d, yyyy HH:mm" 等，`values-zh`（zh）"MM月dd日" / "yyyy年MM月dd日 HH点mm分"；用 `SimpleDateFormat(pattern, locale)` 按有效 locale 输出（getString 取 pattern）。
- 序数参数化：`player_track_of` = zh "第%1$d首/共%2$d首" / en "Track %1$d of %2$d"；替换现有硬编码拼接。
- 数字格式（千分位）首版不做。
