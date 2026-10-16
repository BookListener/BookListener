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
