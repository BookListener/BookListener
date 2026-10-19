# Tasks

## 1. 解析器与数据层

- [ ] 1.1 新增 `core/CueParser.kt`（纯函数：CueTrack 数据类 + parse，多 FILE 段上下文 / INDEX 00/01 / MM:SS:FF→ms 75 帧 / PREGAP 忽略 / 无 INDEX 跳过 / 末轨 end=null），并新增 CueParserTest 覆盖标准专辑、多 FILE 段、畸形容错、时间换算，验证全绿
- [ ] 1.2 新增 `db/StoreEntities.kt` 或新实体文件 `CueSheetEntity`（cueUri PK, entryId, audioUri, workId）+ AppDao cue 查询（getCueSheetByUri / getCueSheetsByEntry / listVirtualTracksByWork / deleteCueSheetByEntry / upsertCueSheet），并新增 DAO 级测试验证增删查
- [ ] 1.3 AppDatabase 升 version=5 + `MIGRATION_4_5`（CREATE TABLE cue_sheets），并新增 Migration45Test 验证 v4 库迁移后 cue_sheets 存在且旧表完好

## 2. 扫描集成与吸收

- [ ] 2.1 在扫描写入路径前置 cue 预处理：目录发现 .cue → 读文本 → CueParser 解析 → 整轨定位（FILE 声明优先 + 同名扩展名探测 flac/ape/wav/mp3/m4a/dsf/dff）；定位成功 → 虚拟轨行生成（uri=`<audioUri>#track_<startMs>`，album/artist 取 cue）并**吸收整轨**（整轨 uri 不入 entry_index）；失败 → 整轨按普通条目；cue_sheets upsert（本地 SAF + WebDAV 共用 readText 抽象），并新增 ScanCueTest 验证吸收成功/解析失败退化/二次扫描 workId 稳定
- [ ] 2.2 专辑/曲目消费侧验证：虚拟轨行参与 works 聚合（cue 专辑 key=`local:<performer?:entryName>|<album?:cueBaseName>`）与 albumSongs 分页，无特殊分支即可正确展示

## 3. 播放与进度

- [ ] 3.1 PlayerViewModel 虚拟轨播放：MediaItem + ClippingConfiguration(start,end) + 显式 mimeType（ape/dsf/dff）+ mediaId=虚拟 uri；播放虚拟轨时按 uri 反查 workId 存进度（复用 v4 WorkStore 路径），并新增/更新 WorkStore 相关测试验证虚拟 uri 进度存取
- [ ] 3.2 cue 编辑导致 startMs 变化的近似恢复：验证 orderIndex 回退路径（matchMode=0）对虚拟轨同样生效，更新 FolderProgressLogic 相关测试

## 4. UI：内容文件入口与虚拟轨列表

- [ ] 4.1 内容文件列表（FolderManageScreen）：显示含 .cue 行（数据源=entry_index 行 + cue_sheets 行的 .cue 文件列表）；常规行保持无点击；.cue 行特殊点击 → 打开虚拟轨列表页，并新增 UI 测试验证点击 cue 开列表、点击音频行无响应
- [ ] 4.2 虚拟轨列表页：按 workId 查虚拟轨行渲染（标题=轨名/表演者/时长），纯浏览不关联播放/入队；列表命名对齐（专辑/曲目/内容管理/内容文件/虚拟轨）落地 i18n 资源与 GLOSSARY 更新，验证字符串与术语一致
- [ ] 4.3 版本升 minor v2.2.0 + CHANGELOG 行为变更说明（cue 吸收语义/整轨退化/进度近似回退），验证 CHANGELOG 与版本号一致

## 5. 回归与交付

- [ ] 5.1 全量单测回归（新增 CueParserTest / ScanCueTest / Migration45Test / UI 用例 + 既有 256 用例），验证 0 失败且 schema 5.json 生成入库
- [ ] 5.2 构建 debug APK（v2.2.0）交付 + git 提交（含 openspec cue-virtual-tracks 全套），验证包可安装

## Workflow follow-up

- 用户验收后 `openspec archive cue-virtual-tracks`（归档到 openspec/changes/archive/ + 主 spec 生成）
- release/R8 需人工确认后才构建（本项目规则）
