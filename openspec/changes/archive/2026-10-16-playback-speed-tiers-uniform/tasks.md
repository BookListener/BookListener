## 01-uniform-tiers
---
id: t1-uniform-tiers
status: done
---

档位与分布改造（v2.0.9）：
- `PlaybackSpeedTiers.TIERS` 加 1.75x → 7 档：0.5/0.75/1.0/1.25/1.5/1.75/2.0。
- `fraction(tier)` 改为按档位序号均匀：index/(size-1)；非档位值回退线性（防御性）。手写循环求索引（环境 FloatArray.indexOf 解析失败）。
- SpeedSheet Slider 改索引语义：value = 当前档索引、valueRange = 0f..6f、steps = 5；onValueChange 吸附索引 → dragValue = TIERS[index]；松手应用。
- 注释同步 v2.0.9（Slider 索引/均匀/标签对齐原理）。

## 02-tests-and-deliver
---
id: t2-tests-and-deliver
status: done
---

测试 + 交付：
- PlaybackSpeedTiersTest 升级 7 用例：循环顺序含 1.75、nearest 含 1.75（1.65→1.75）、label 加 1.75x、fraction_evenlyAcrossTiers 均匀断言（0/1/6/2/6/3/6/4/6/5/6/1）、多屏宽标签刻度对齐（fraction 改动后自动覆盖）。
- `testDebugUnitTest` 233 用例全绿 + `assembleDebug` 构建通过。
- 版本 2.0.8 → 2.0.9（versionCode 62 → 63）。
- git 提交 e893088；APK `BookListener-v2.0.9-speed-tiers-even.apk` 交付，用户真机验收"确认"。
- 归档：`openspec archive playback-speed-tiers-uniform --yes` 生成基线 spec。

