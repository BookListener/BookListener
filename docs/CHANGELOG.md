# BookListener 版本迭代历史

> 项目早期名为 Simple Cloud Player（`com.simple.musicplayer`），2026-10 起更名 **BookListener**、包名 `com.booklistener`。
> 变更按版本倒序排列。

## v2.1.8（2026-10）修复：封面点击进入正在播放的专辑会暂停
- 根因：enterAlbumWithRecord 调 shouldRestartOnEnter("work:$workId")，但该方法内部把 work: 前缀反查为 entryId，
  与 "work:<id>" 比较永远不等 → 永远返回 true → 每次 openAlbum 都 prepareFolder(autoPlay=false) → 暂停。
  这是 v2.1.2 引入的潜伏 bug，v2.1.6/v2.1.7 只修了封面点击的作品定位，没修进入专辑时的重启判断。
- 修复：新增 PlayerViewModel.shouldRestartForWork(workId)，用 resolveCurrentWorkId 直接比较作品级维度；
  正在播放同一作品且 queueReady → 不重启（保持播放），否则重启（暂停并加载目标作品记录）。
- 排查确认：shouldRestartOnEnter 的另两个调用点（openEntryView/resumePlaybackOfEntry）传 entryId，类型匹配无此问题。
- 新增 shouldRestartForWork 测试 5 用例（同作品不重启/不同作品重启/条目级队列按曲目反查/无主队列按曲目反查/队列未就绪重启）。
- PlaybackController 新增 setStateForTest 测试辅助（Robolectric 中 player=null 时模拟 currentItem）。
- 全量 296 用例通过。
## v2.1.7（2026-10）修复：封面点击作品定位补全（条目级队列/无主队列）
- 排查发现 v2.1.6 只覆盖了作品级队列（ownerId="work:<id>"），另有两种播放路径未覆盖：
  ① 条目级队列（ownerId=entryId，网盘 enterFolderWithRecord）→ 旧实现走 openEntryAlbum 取条目下第一个作品
  ② 无主队列（ownerId=null，搜索结果/文件夹视图点击单曲 playItems）→ 旧实现 ownerId 为 null 直接 return，封面点击无响应
- 修复：新增 PlayerViewModel.resolveCurrentWorkId(ownerId, currentPath) 统一三种队列归属的作品定位——
  work: 前缀直接取；其余从当前播放曲目 uri 反查 workId（与 saveWorkProgress 同一反查路径）
- openCurrentWorkAlbum 重构为调用 resolveCurrentWorkId，覆盖所有播放路径
- openEntryAlbum 标记 @Deprecated（已无调用点，取首个作品的逻辑是跳错专辑根源）
- 新增 CoverClickWorkResolutionTest：resolveCurrentWorkId 三分支 + openCurrentWorkAlbum 端到端 + 同专辑多作者合并联动，共 6 用例
- 全量 291 用例通过
## v2.1.6（2026-10）修复：点击正在播放的专辑封面跳转到第一个专辑
- 根因：封面点击经 queueOwnerEntry 反查到来源条目后，openEntryAlbum 取该条目下 works.firstOrNull()（第一个作品），而非正在播放的作品；同条目多专辑文件夹时必然跳错
- 修复：新增 PlayerViewModel.openCurrentWorkAlbum()，从 PlaybackController ownerId（"work:<workId>"）直接定位正在播放的作品，构造 AlbumSummary 后 openAlbum；非作品级队列（文件夹播放）回退 openEntryAlbum 兼容旧逻辑
- MainScreen 封面点击改为调 openCurrentWorkAlbum()，保留 tabIndex=0 切专辑 tab
- 全量 285 用例通过
## v2.1.5（2026-10）修复：专辑列表遮挡 + 文件夹管理命名回退 + 同专辑多作者合并
- 专辑列表底部遮挡：主页专辑聚合/专辑曲目/搜索结果三处 LazyColumn 加底部内容留白（88dp），最后一行不再被底部播放控制栏盖住，且可滚动到完全可见
- 文件夹管理命名回退：入口页标题恢复"文件夹管理"（不再叫"内容管理"）；点击进入目录后标题即目录名（原本的目录）
- 同专辑多作者合并：作品键 local 分支去掉 albumArtist/artist——同一专辑不同表演者（如"周杰伦"与"周杰伦 with 五月天阿信"的同名专辑曲目）聚合为一个专辑，曲目各自保留表演者；重扫时旧拆分作品的最近播放进度自动迁移到合并专辑，不静默丢失
- 测试：WorkBackfillTest/ScanUpsertTest 更新 + 新增合并与进度迁移用例；全量 285 用例通过
## v2.1.4（2026-10）修复：cue 中文乱码 + 内容文件列表混入虚拟轨行
- cue 中文乱码：cue 规范无强制编码，中文 cue 多为 GBK/GB18030。新增 core/CueTextDecoder（BOM 剥离 → 严格 UTF-8 校验 → GB18030 回退 → 兜底），本地 SAF 与 WebDAV 读取点统一接入，中文 cue 完整还原（含专辑/曲目/表演者名与整轨定位）
- 内容文件列表：过滤虚拟轨行（rawName 形如 "整轨.cue#1"）——只显示原始文件（cue/整轨/普通文件），.cue 行点击后仍可在弹层浏览可播放曲目列表；虚拟轨仅在该入口展示
- 测试：CueTextDecoderTest（GBK/UTF-8/UTF-8 BOM/纯 ASCII）+ ScanCueMixedTest 回归，全量通过
## v2.1.3（2026-10）修复：cue+普通音频混合文件夹切换专辑列表闪退
- **根因**：主页专辑聚合/搜索的 LazyColumn key 用 `entryId_album`——同一条目下两个不同作品（cue 虚拟轨作品 + 普通 flac 作品）展示 title 撞名时 key 重复，Compose 抛 duplicate key 崩溃。cue 无 TITLE（虚拟轨走条目级作品键）且 flac 内嵌专辑名等于文件夹名时必然触发
- 修复：专辑列表/搜索 key 改用 `workId`（works 主键，全局唯一）；封面读取本有 runCatching 保护，数据层 SQL 已验证无异常
- 测试：ScanCueMixedTest 新增混合目录撞名场景（两作品同 title、workId 互异），全量 281 用例回归通过
## v2.1.2（2026-10）修复：点击正在播放的封面无法进入专辑曲目列表
- **根因**：作品级播放进度改造后队列所属（queueOwnerId）语义为 `"work:<workId>"`，但封面点击/详情/进文件夹判定仍按「条目 id」查找来源条目，恒匹配失败 → 点击封面只弹详情面板、后台不切换专辑曲目列表（静默失败）
- 修复：DAO 新增 `getEntryIdByWorkId`（作品 → 来源条目，跨条目合并取 MIN，与 AlbumStore 口径一致）；VM 新增 `queueOwnerEntry`（事件回调反查）与 `queueEntryForDisplay`（组合期内存兜底，回退当前专辑来源条目）；`shouldRestartOnEnter` 改按反查条目 id 比较——进入正在播放的文件夹不再误走记录恢复
- 影响面一并修复：底部封面兜底条目、播放详情面板来源条目信息、进文件夹"保持当前会话"判定
- 测试：新增 WorkEntryLookupTest 3 用例（反查命中/跨条目 MIN/无记录 null），全量 279 用例回归通过

## v2.1.1（2026-10）CUE 整轨虚拟分轨（spec: cue-virtual-tracks）
- 新增 CUE 解析（`core/CueParser`）：多 FILE 段上下文、INDEX 00/01、MM:SS:FF→ms（75 帧/秒）、PREGAP 忽略、无 INDEX 跳过、末轨播到文件尾
- 整轨**吸收**与退化：目录含 .cue 且成功定位整轨（FILE 声明优先 + 同名扩展名探测 flac/wav/mp3/m4a）时，专辑/曲目层只显示虚拟轨；解析失败或未定位时整轨退化为普通条目
- 虚拟轨作为普通媒体条目入库（`<整轨uri>#track_<起始ms>` 唯一 uri），按既有作品键聚合；新增 `cue_sheets` 表（MIGRATION_4_5，仅建表无搬移）维护 cue → (条目, 整轨, 作品) 关联
- 虚拟轨播放 = Media3 **ClippingConfiguration 裁剪窗口**（零切割、位完美保真）；进度按 (作品, 虚拟 uri) 内容引用记录，杀后台/重进精确恢复；cue 编辑致 startMs 漂移时按 v4 orderIndex 近似恢复并诚实标注
- 内容文件列表（内容管理 → 条目）忠实显示所有文件（含 .cue 与整轨，常规行无点击）；.cue 行可点击浏览其**虚拟轨列表**（纯浏览，不触发播放/入队）
- 列表命名统一（定稿）：主页聚合=专辑、专辑内=曲目、文件夹管理页=内容管理、其内文件列表=内容文件、cue 分轨列表=虚拟轨（i18n 资源 + GLOSSARY 同步）
- **已知限制**：ape/dsf/dff 无 Media3 官方解码器（未引入 FFmpeg 扩展），不纳入吸收探测，整轨保持普通条目
- **行为变更**：有 cue 且定位成功的目录，整轨不再单独出现在专辑/曲目层（吸收语义升级）；无 cue 目录行为不变
- 测试：新增 CueParser / ScanCue / VirtualTrackUri / Migration45 共 17 用例，全量 273 用例回归通过
- 注：本次按项目默认策略升 patch（用户未确认 minor）；如需 v2.2.0 再升版重建

## v2.1.0（2026-10）数据库重构 v4：作品实体化 + 进度内容引用
- 新增 `works`（作品/专辑为一等实体）：tag 优先跨文件夹合并同名专辑；无标签/网盘按「条目+文件夹名」独立；`key` 唯一索引，扫描 **upsert by key**（禁止整删重建，作品 id 稳定）
- 新增 `work_progress`：进度锚点从「位置序号」改为「(作品, 曲目 uri) 内容引用 + orderIndex 近似回退」，uri 失配时按位置近似恢复并诚实标注（可能定位不准）
- 老表处置：`folder_progress` / `play_record` **停写保留不删**（回滚能力）；`cache_index` 搬家为 `cloud_cache`；`favorite` 收藏粒度升到作品（映射不到的保留 NULL 标未知，不静默删除）
- `entry_index` +4 列：`workId` / `sortRank`（条目内展示序下标，SQL 整数排序）/ `sizeBytes`（二期指纹用）/ `albumArtist`
- 一次 v3→v4 迁移回填（作品聚合/排序下标/老进度/收藏作品级），升级不丢进度
- **行为变更**：单文件夹多专辑从「一个进度」变「每专辑各自进度」；同名专辑跨文件夹合并后进度合并为一个；文件大量改名退化为位置近似并提示
- 测试：新增 WorkBackfill / WorkStore / ScanUpsert（防进度断链）/ Migration34 迁移回填 4 组用例，全量 256 用例回归通过

## v1.6.12（2026-10）
- 文档名称统一（README/BASELINE 全量改净），新增用户向 README 与架构文档
- 深链 scheme `simplemusic://` → `booklistener://`（manifest、回调常量、云盘配置页、文档同步）
- 修复云电脑 release 构建 R8 频繁卡死：JVM 堆回落 2048m + SerialGC（根因：cgroup 4G 内存硬限，3G 堆高峰撞限触发页面回收风暴）

## v1.6.11（2026-10）
- 包名迁移 `com.simple.musicplayer` → `com.booklistener`（83 个文件：package/import/注释/构建配置/目录结构全量落地）
- 注：applicationId 变更 = 全新应用身份，旧数据不迁移，需重新添加文件夹并授权

## v1.6.10（2026-10）
- 项目更名 **BookListener**：应用名、主题、UA、设置页、文档、根工程名统一

## v1.6.9
- 作者·专辑一行展示，`|` 连接符恒居行中心
- 杀后台恢复与进入专辑共用同一段"正在播放定位"逻辑（含深分页/到底也能置顶）
- 点击暂停封面进入详情不再被"进入专辑"再次停止播放（队列就绪且同文件夹判定）

## v1.6.8
- 循环模式文字改图标；定时关闭改底部弹层（不开启/听完本章节/15/30/60/90/自定义/关闭）
- AB 段单键设点 + 图标清除，与设点调换位置
- 专辑行加超淡细分隔线；底部封面增大 50%，播放中旋转、暂停时遮罩+播放键、点击弹出详情

## v1.6.7
- 播放详情页信息排版优化；循环三档（顺序/单曲/列表）
- 定时关闭（一次性=从设置时刻倒计时；全局=从最后一次开始播放倒计时，一次性优先）
- 打开正在播放自动定位到当前曲目；AB 段重复（单键设点）

## v1.6.4
- 超长文本方案 B 全量落地：列表省略 + 长按弹层 + 焦点滚动 + 行尾来源图标与时长同行右对齐（支持小时位）

## v1.6.1
- 曲目界面专辑化：显示专辑名（非文件夹名），点开为播放列表
- 播放列表从数据库分页加载（≤100 条/页滑动续载），不再每次重扫
- 文件夹管理页：显示文件夹路径、文件列表（文件名+扩展名）、删除二次确认
- 专辑/作者/标题/时长等标签信息全部落库

## v1.6.0
- 标签解析：优先标签（音轨号/专辑/作者/标题/时长），缺失降级文件名
- 自然排序：文件名/专辑名按数字段数值比较；有音轨号优先按音轨号（考虑专辑号）
- 持久化迁移：SharedPreferences-JSON → **Room**（含 v2→v3 迁移、entry_index 6 扩展列）

## v1.5.x
- 曲目界面重构：添加文件夹入设置、顶部搜索框（文件夹/文件搜索）、文件夹显示目录名+封面
- 分区展示"正在播放 / 尚未播放"（按最近播放倒序）
- 文件夹管理页（列举/删除/右上角添加入口）；曲目界面去删除按钮
- 界面美化：标题字号、返回箭头、状态栏深色、搜索框淡化、条目增高/封面加大/作者信息
- 安卓返回键支持（返回而非退出）；杀后台后恢复上次播放进度与正在播放态
- 封面管理抽象为独立类；长文本滚动（MarqueeText）
- 霰弹式代码排查与收敛（条目视图/行渲染/空态/缓存目录/AudioItem 工厂等统一模板）

## v1.4.x
- 文件夹固化（支持多个、不覆盖）、列举目录内可播放文件
- 每文件夹独立播放进度：切换文件夹只切换记录不自动播放，点击播放后从记录继续
- 播放详情弹层（点击主页封面向上弹出）；网盘配置移入设置页
- 本地/WebDAV/阿里云盘三源统一；SAF 打开文件夹/文件
- 进度条正确展示播放进度；锁屏通知/前台播放 Service + MediaSession

## 早期（v1.0–v1.3）
- 基础本地播放器：文件选择/打开、播放控制、进度条
- 引入 WebDAV 与阿里云盘源；播放队列与复合数据源（file:// / http / 直链）
