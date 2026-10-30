## 01-parsefreeform-namespace
# 实施：parseFreeForm 保留命名空间（键格式 `----:<issuer>:<name>`）

## 任务

^- [x] 1.1 `core/Mp4IlstReader.kt`：`parseFreeForm` 输出键改 `----:<issuer>:<name>`——payload 按 `\0` 拆分，issuer 取第一段（非空时保留）；issuer 为空保持 `----:<name>` 旧格式。
^- [x] 1.2 同步既有测试断言（TagFieldsFlowTest 等含 `----:` 键的 extras 断言）。

## 验收

- 编译通过；自由格式键含命名空间；空 issuer 键不变。

## 02-tagfields-mapping
# 实施：TagFields 系列字段自由格式优先 + 同源配对

## 任务

^- [x] 2.1 `core/TagFields.kt`：`series()` MP4 链路首选 `----:*:SERIES`/`----:*:series`（按命名空间分组、命名空间内大小写不敏感），缺失降级 `©ser`；mp3 链路不变（`TXXX:SERIES` → `TIT1`）。
^- [x] 2.2 `seriesNumber()` MP4 链路首选 `----:*:SERIES-PART`/`----:*:series-part`，缺失降级 `©sno`；mp3 链路不变（`TXXX:SERIESNUMBER`）。
^- [x] 2.3 同源配对：同一命名空间成对提供则同取；只提供其一 → 另一字段回退字段级标准降级链；禁止跨命名空间拼凑。

## 验收

- 编译通过；flow 测试断言结构化列产物（见 03）。

## 03-flow-tests
# 实施：flow 测试覆盖命名空间与优先级场景

## 任务

^- [x] 3.1 同命名空间成对命中（`----:nsA:SERIES` + `----:nsA:SERIES-PART`）。
^- [x] 3.2 跨命名空间禁止：名取 nsA、nsB 的序号不采用 → 回退 `©sno`；无 `©sno` → 序号空。
^- [x] 3.3 仅小写镜像命中（`----:nsA:series` / `----:nsA:series-part`）。
^- [x] 3.4 自由格式与标准原子并存 → 自由格式优先。
^- [x] 3.5 自由格式缺失 → 降级 `©ser`/`©sno`。
^- [x] 3.6 自由格式仅序号 → 序号取自由格式、名回退 `©ser`。
^- [x] 3.7 mp3 回归：`TXXX:SERIES`/`TIT1` 行为不变。

## 验收

- 全量 UT（Robolectric + Room in-memory）通过，无回归。

## 04-docs-version-delivery
# 实施：文档同步、版本与交付归档

## 任务

^- [x] 4.1 SPECS §九 同步：系列字段解析优先级与命名空间同源配对、键格式说明（docs/TAG-FIELDS.md 已先行更新）。
^- [x] 4.2 版本：`app/build.gradle` versionCode 递增 1 / versionName `2.2.22`（已确认变更，仅 patch）。
^- [x] 4.3 全量 UT 通过；`assembleDebug` 构建；交付 `BookListener-v2.2.22-series-freeform.apk`（置于仓库根，经 present_files 交付）。
^- [x] 4.4 git 提交（`-c user.name=BookListener -c user.email=BookListener@local`）；`openspec archive 2026-10-30-m4a-m4b-series-freeform-priority` 归档（spec 合入 `openspec/specs/`）。

## 验收

- 全量 UT 全绿；APK 核验 versionCode/versionName 2.2.22；归档完成；提交完成。
