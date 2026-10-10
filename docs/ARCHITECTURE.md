# BookListener 架构、设计与技术栈

> 本文档承载项目的结构、模块职责、关键设计与技术栈细节；入门与使用见根目录 `README.md`。

## 1. 技术栈

| 层 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin | 1.9.24（kapt 注解处理） |
| UI | Jetpack Compose + Material3 | BOM 2024.06.00，kotlinCompilerExtensionVersion 1.5.14 |
| 播放 | Media3 (ExoPlayer) | 1.4.0（exoplayer / extractor / ui / datasource-okhttp / session） |
| 网络 | OkHttp | 4.12.0 |
| 持久化 | Room | 2.7.0（runtime / kapt compiler / ktx） |
| 异步 | kotlinx-coroutines | 1.8.1（Android） |
| 安全存储 | security-crypto (EncryptedSharedPreferences) | 1.1.0-alpha06 |
| JSON | org.json | 20240303 |
| 测试 | JUnit4 + Robolectric + Compose UI Test | 4.13.2 / 4.12.2 / compose-ui-test |

- minSdk **26** / targetSdk **34** / compileSdk **34**
- release：`minifyEnabled + shrinkResources`（R8），当前以 debug 签名本地验证，正式发布应替换 release keystore
- 强制 kotlin-stdlib 1.9.24，避免传递依赖解析到 2.1.x 导致元数据不兼容

## 2. 项目结构

```
app/src/main/java/com/booklistener/
├── MainActivity.kt           # 入口 + 阿里云盘 OAuth deep link 回调（booklistener://oauth/callback）
├── core/                     # 领域核心：模型、三源接口、标签解析、自然排序、封面、进度、扫描
│   ├── Models.kt             # AudioItem / IndexedFile / 专辑聚合模型
│   ├── AudioSource.kt        # 统一音频源接口（本地/WebDAV/阿里）
│   ├── TagResolver.kt        # 标签解析：优先标签，缺失降级文件名
│   ├── NaturalOrder.kt       # 文件名/专辑名自然排序（数字段按数值比较）
│   ├── CoverManager.kt       # 封面管理单一抽象（主页/详情/曲目三处引用）
│   ├── CoverScanner.kt       # 专辑封面扫描与缓存
│   ├── FolderScanner.kt      # 文件夹扫描
│   ├── FolderProgressLogic/Store.kt  # 每文件夹独立播放进度
│   ├── LastSessionLogic.kt   # 杀后台恢复现场
│   ├── SavedEntry.kt         # 固化文件夹/网盘条目
│   ├── AlbumStore.kt         # 专辑聚合查询（分页）
│   ├── AudioItemFactory.kt   # AudioItem 工厂统一构造
│   ├── OpenFileItemFactory.kt# 外部打开文件适配
│   ├── CacheDirs.kt / CrashLogger.kt / Utils.kt / MessageCatalog.kt
├── source/                   # 三源实现
│   ├── LocalAudioSource.kt   # SAF 本地
│   ├── WebDavAudioSource.kt  # RFC4918
│   └── AliyunAudioSource.kt  # 阿里云盘直链
├── auth/                     # CredentialStore(加密) + AliyunAuthManager(PKCE)
├── net/                      # OkHttpHolder 单例（含 429 退避）
├── cache/                    # CacheManager（完整文件 LRU，5GB）
├── db/                       # Room：AppDatabase / AppDao / DbEntities / StoreEntities
├── data/                     # MediaRepository 统一仓库
└── player/                   # 播放内核
    ├── PlaybackController.kt # 播放控制 + 状态 StateFlow（进度/队列/循环/AB/定时）
    ├── PlaybackQueueLogic.kt # 队列构建与恢复
    ├── PlaybackState.kt      # 播放状态模型
    ├── DefaultSourceResolver.kt  # 复合数据源解析（file:// / http / 直链）
    └── MediaPlayService.kt   # 前台播放 Service + MediaSession（锁屏控件/来电暂停）
```

UI 层（`ui/`）：`MainScreen`（曲目/专辑主页）、`DirectoryScreen`（文件夹详情）、`FolderManageScreen`（文件夹管理）、`PlayerDetailSheet`（播放详情弹层）、`SettingsScreen`（设置/网盘配置）、`CloudConfigScreen`（网盘配置）、`LongTextUi` / `MarqueeText`（长文本方案 B）、`PlayerViewModel`。

## 3. 关键设计

### 3.1 三源统一
- `AudioSource` 接口抽象浏览/列举/打开；`DefaultSourceResolver` 按 URI 前缀把播放请求路由到对应数据源（本地 file://、WebDAV http、阿里直链）
- 阿里云盘走 OAuth2 PKCE（无 client_secret），授权回调深链 `booklistener://oauth/callback`
- 凭证经 EncryptedSharedPreferences 加密存储

### 3.2 标签解析与排序
- `TagResolver` 解析音轨号/专辑/作者/标题/时长；**优先标签，缺失降级文件名**
- 排序：有音轨号按音轨号（考虑专辑号），否则按文件名；统一走 `NaturalOrder` 自然排序（数字段数值比较，不按字典序）
- 专辑无标签时兜底用**文件夹名**（非路径）

### 3.3 持久化（Room）
- `entry_index` 索引表含 6 扩展列：专辑 / 作者 / 标题 / 时长 / 音轨号 / 专辑号（v2→v3 迁移）
- 播放进度、固化文件夹、网盘配置落库；历史 prefs-JSON 数据经 `StoreMigration` 迁移
- 专辑列表从数据库分页加载（≤100 条/页，滑动续载），播放列表非每次重扫

### 3.4 播放体验
- **每文件夹独立播放进度**：切换文件夹只切换记录不自动播放；点击播放后从该文件夹记录继续
- **循环三档**：顺序 / 单曲 / 列表（图标切换）
- **AB 段重复**：单键设点 + 图标清除
- **定时关闭**：一次性（从设置时刻倒计时）+ 全局（从最后一次开始播放倒计时），一次性优先级更高
- **杀后台恢复**：重新进入定位到正在播放条目（条目视图，与专辑视图共用 `rememberScrollToPlaying` 定位逻辑）
- **长文本方案 B**：列表省略 + 长按弹层看全文 + 焦点位置向左缓慢滚动；行尾来源图标与时长同行右对齐（16dp）；时长支持到小时位

### 3.5 封面管理
- `CoverManager` 单一抽象，主页 / 播放详情 / 曲目列表三处直接引用，避免霰弹式复制

## 4. 构建与测试环境

### 云电脑（当前交付环境）
- JDK 21（Temurin 21.0.12.1）+ Android SDK 34（platforms;android-34、build-tools;34.0.0）+ Gradle 8.7
- `gradle.properties`：`-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC`、`workers.max=1`
  - **原因**：云电脑内存硬限 4G（cgroup）且无 swap，堆配置过高（如 3G+768m）会使 R8 高峰撞限 → 页面回收风暴表现为 R8 卡死；回落 2G 堆 + SerialGC 低并发后稳定
- 构建命令：
  ```bash
  export JAVA_HOME=<jdk21 路径> && export PATH=$JAVA_HOME/bin:$PATH
  ./gradlew testDebugUnitTest   # 180 项单测（JUnit4 + Robolectric）
  ./gradlew assembleRelease     # R8 压缩 release
  ```
- 测试覆盖：标签解析/自然排序/进度逻辑/队列/恢复/定时递减等（Robolectric 下 wall-clock 不可测，倒计时逻辑用 delay 递减并注入 remainingMs）

### Windows 本机（历史环境，已配置）
- JDK 21：`C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1`
- Android SDK：`C:\Users\e\AppData\Local\Android\Sdk`（platforms;android-34 + build-tools;34.0.0）
- Gradle 8.7：`C:\Users\e\AppData\Local\Android\Tools\gradle-8.7`

## 5. 已知边界

- 百度网盘 / 天翼 / 移动云盘（传统 OAuth 需 client_secret）未内置，可扩展 `AudioSource` 适配
- WebDAV 仅支持标准 RFC4918 实现
- 缓存为完整文件 LRU（5GB），无分片边播缓存
- 深链 `booklistener://` 仅承载阿里云盘 OAuth 回调，改动需同步阿里开放平台配置
- release 当前使用 debug 签名，正式分发需配置独立 keystore
