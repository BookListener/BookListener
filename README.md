# BookListener

Android 三源音频播放器（Kotlin + Jetpack Compose + Media3 ExoPlayer）。
面向「本地 + 网盘」的听书/听歌场景：本地文件夹、WebDAV、阿里云盘三源统一浏览与播放，单文件适配 ≤200MB 的有声书/音频。

## 项目背景

- **起源**：需要一款能在无网/弱网环境播放本地音频的工具，逐步演进为三源统一的播放器。
- **定位**：以"文件夹/专辑"为组织单元，重视播放进度连续性——每个文件夹独立记忆进度，杀后台后恢复现场。
- **自研为主**：标签解析（优先标签、缺失降级文件名）、自然排序、专辑聚合、封面管理、播放进度等核心逻辑全部自研实现，不做黑盒依赖。
- **迭代方式**：真机实测逐版迭代，每个功能改动均配套单元测试；项目历史从 v1.4.x 演进至 v1.6.x，2026-10 起更名为 **BookListener**，包名 `com.booklistener`。

## 功能

### 三源播放
- **本地**：SAF 添加/固化文件夹，下次一键直达；列举目录内可播放文件
- **WebDAV**：标准 RFC4918（Nextcloud / InfiniCLOUD / 中国科技云 / 自建 NAS），应用密码登录
- **阿里云盘**：OAuth2 PKCE 扫码授权，全盘浏览，临时直链 Range 播放

### 曲目与专辑
- 标签解析优先（音轨号/专辑/作者/标题/时长），缺失降级文件名；均支持自然排序
- 曲目界面按专辑聚合展示（专辑名/作者/封面），点开为播放列表，数据库分页加载（≤100 条/页，滑动续载）
- 专辑无标签时兜底用文件夹名（非路径）
- 正在播放条目高亮定位（进入专辑/杀后台恢复共用同一段定位逻辑）

### 播放体验
- 每文件夹独立播放进度，切换文件夹不自动开始播放
- 循环三档：顺序 / 单曲 / 列表
- AB 段重复（单键设点 + 图标清除）
- 定时关闭：一次性（15/30/45/60 分钟）与全局设置，优先级：一次性 > 全局
- 前台播放 Service + MediaSession：锁屏控件、来电暂停、后台播放
- 播放详情：封面旋转/暂停遮罩、作者·专辑 `|` 连接居中、长文本焦点滚动、正在播放定位

### 工程能力
- Room 媒体库：文件夹、专辑索引、播放记录、进度持久化
- 完整文件 LRU 缓存（5GB 上限）
- 凭证加密存储（EncryptedSharedPreferences）
- 180 项单元测试（JUnit4 + Robolectric + Compose UI Test）

## 如何使用

### 安装
直接安装 APK 即可（release 为 R8 压缩包，debug 含调试信息）。包名 `com.booklistener`，更换主版本包名后旧数据不迁移，需重新添加文件夹并授权。

### 构建
```bash
# 环境：JDK 21 + Android SDK 34 + Gradle 8.7
export JAVA_HOME=<你的 JDK21 路径>
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug      # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease    # release APK → app/build/outputs/apk/release/
./gradlew testDebugUnitTest  # 运行 180 项单元测试
```

### 使用前配置
1. **本地**：「文件夹管理 → 添加文件夹」选择目录，之后可直接进入播放
2. **WebDAV**：网盘后台开启 WebDAV 并生成应用密码，填入「设置 → 网盘 → WebDAV」
3. **阿里云盘**：到阿里云开放平台注册应用，`client_id` 填入「设置 → 网盘 → 阿里云盘」，**授权回调地址配置为 `booklistener://oauth/callback`**，扫码授权后即可浏览

> 注意：深链 scheme 为 `booklistener://`。若在开放平台改过回调地址，App 端 scheme 需保持一致，否则授权回调进不来。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/ARCHITECTURE.md` | 项目结构、模块职责、关键设计、技术栈、构建环境 |
| `BASELINE.md` | 构建/运行基线（含崩溃日志位置） |
| `DESIGN-标签解析与排序及Room迁移.md` | 标签解析 + 自然排序 + Room 迁移的设计文档 |
| `PLAN-曲目界面重构-v1.5.0.md` | 曲目界面专辑化重构计划 |
| `REVIEW-霰弹式代码排查.md` | 代码审查：霰弹式修改排查与收敛记录 |

## 已知边界（刻意裁剪）

- 百度网盘 / 天翼 / 移动云盘（传统 OAuth 需 client_secret）未内置，需自行扩展适配器
- WebDAV 仅支持标准实现，小众私有实现不保证兼容
- 缓存为完整文件缓存，无分片边播缓存
- 深链 `booklistener://` 仅用于阿里云盘 OAuth 回调
