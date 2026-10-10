# Simple Cloud Player

Android 本地 + 网盘音乐播放器（Kotlin + Jetpack Compose + Media3 ExoPlayer）。

## 功能
- 本地音频：MediaStore 扫描（mp3/m4a/flac/wav/ogg/aac）
- WebDAV：标准 RFC4918（Nextcloud / InfiniCLOUD / 中国科技云 / 自建 NAS），用户后台生成应用密码
- 阿里云盘：OAuth2 PKCE 扫码授权，全盘浏览，临时直链 Range 播放
- 前台播放 Service + MediaSession：锁屏控件、来电暂停、后台播放
- 完整文件 LRU 缓存（5GB 上限，适合 ≤200MB 音频）
- Room 媒体库：播放记录、收藏、缓存索引；凭证加密存储（EncryptedSharedPreferences）

## 目录结构
```
app/src/main/java/com/simple/musicplayer/
├── MainActivity.kt            # 入口 + OAuth deep link 回调
├── core/                      # 数据模型、AudioSource 接口、异常、工具
├── source/                    # LocalAudioSource / WebDavAudioSource / AliyunAudioSource
├── auth/                      # CredentialStore(加密) + AliyunAuthManager(PKCE)
├── net/                       # OkHttp 单例 + 429 退避
├── cache/                     # CacheManager(完整文件 LRU, 5GB)
├── db/                        # Room 数据库
├── player/                    # PlaybackController + MediaPlayService(前台播放+锁屏)
├── data/                      # MediaRepository 统一仓库
└── ui/                        # Compose UI + ViewModel
```

## 构建环境（Windows 本机已配置）
- JDK 21（Eclipse Temurin 21.0.12）：`C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1`
- Android SDK（cmdline-tools + platform-tools + platforms;android-34 + build-tools;34.0.0）：`C:\Users\e\AppData\Local\Android\Sdk`
- Gradle 8.7：`C:\Users\e\AppData\Local\Android\Tools\gradle-8.7`

## 构建命令
```powershell
$env:JAVA_HOME = "C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd SimpleCloudPlayer
gradle assembleDebug   # 产出 app/build/outputs/apk/debug/app-debug.apk
```

## 使用前配置
1. 阿里云盘：到阿里云开放平台注册应用，client_id 填入「网盘 → 阿里云盘」页，配置回调地址 `simplemusic://oauth/callback`；用户扫码授权后即可浏览。
2. WebDAV：在网盘后台开启 WebDAV 并生成应用密码，填入「网盘 → WebDAV 配置」。

## 已知边界（刻意裁剪）
- 百度网盘 / 天翼 / 移动云盘（传统 OAuth 需 client_secret）未内置，需自行扩展适配器。
- WebDAV 仅支持标准实现，小众私有 WebDAV 服务不保证兼容。
- 缓存为完整文件缓存，无分片边播缓存。
