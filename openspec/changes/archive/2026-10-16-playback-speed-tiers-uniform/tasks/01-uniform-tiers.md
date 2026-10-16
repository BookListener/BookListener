---
id: t1-uniform-tiers
status: done
---

档位与分布改造（v2.0.9）：
- `PlaybackSpeedTiers.TIERS` 加 1.75x → 7 档：0.5/0.75/1.0/1.25/1.5/1.75/2.0。
- `fraction(tier)` 改为按档位序号均匀：index/(size-1)；非档位值回退线性（防御性）。手写循环求索引（环境 FloatArray.indexOf 解析失败）。
- SpeedSheet Slider 改索引语义：value = 当前档索引、valueRange = 0f..6f、steps = 5；onValueChange 吸附索引 → dragValue = TIERS[index]；松手应用。
- 注释同步 v2.0.9（Slider 索引/均匀/标签对齐原理）。
