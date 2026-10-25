# Proposal

## Why

`works.coverUri` 是作品封面权威源（v2.1.23/24/25 全 App 依赖），但内嵌封面来源存的是 `file://…/cache/covers/{hash}.jpg`（`CacheDirs.coverCacheDir` = `context.cacheDir/covers`）。cacheDir 是易失层：系统低存储可自动清除、"清除缓存"（不清数据）也会删——此时数据库完好但封面路径悬空，CoverImage/通知栏 decodeFile 返回 null 静默回退占位，且无自愈（恢复只能等下次全量重扫）。目录图片封面（content:// 指向真实文件）不受影响，问题只在"内嵌封面提取产物"。

根因（证据等级：已核实，CoverManager.kt L60-66 / CacheDirs.kt L19 / ScanCoordinator.kt L133-134 / CommonUi.kt L48-51 / MediaPlayService.kt L122 逐一确认）：**持久层（DB）引用了易失层（cacheDir）的派生缓存路径，生命周期耦合错误**。

## What Changes

内嵌封面提取产物从 `cacheDir/covers` 移入 `filesDir/covers`——与 Room 数据库同清理边界（只有"清除数据"/卸载才删，而那时数据库一并删除，孤儿窗口归零）：

1. `CacheDirs`：新增 `coverFilesDir(context) = context.filesDir.resolve(COVERS_DIR_NAME)`（`covers` 常量复用）；`coverCacheDir` 保留定义仅供迁移读取旧位置。
2. `CoverManager.extractEmbeddedCover`：写入目录改用 `coverFilesDir`。
3. 新增 `CoverDirMigration`（MainActivity.onCreate 在 StoreMigration 后同步调用，prefs 幂等标记）：
   - 旧 `cache/covers` 存在且有文件 → 复制到 `files/covers`（先复制后改库，避免中途失败悬空）→ `UPDATE works SET coverUri = REPLACE(coverUri, '/cache/covers/', '/files/covers/') WHERE coverUri LIKE '%/cache/covers/%'` → 删除旧目录。
   - 无旧目录/无文件 → 直接置标记跳过，DB 不动。
4. `AppDao`：新增 `migrateCoverCachePrefix()` 前缀改写 SQL。

## Impact

- `core/CacheDirs.kt`：+coverFilesDir（1 常量方法）
- `core/CoverManager.kt`：写入目录一行
- `core/StoreMigration.kt`：+CoverDirMigration 对象（或同文件独立对象）
- `db/AppDao.kt`：+migrateCoverCachePrefix
- `MainActivity.kt`：+1 行调用
- `audio_cache`（cacheDir/audio_cache）不动：无持久引用，清缓存丢缓存合理
- 测试：新增 CoverDirMigrationTest（Robolectric：搬移/前缀改写/旧目录清理/幂等）；CoverManager 提取路径断言（Robolectric 无法提取内嵌封面，仅断言无封面返回 null + coverFilesDir 路径）
- 版本：2.1.25 → 2.1.26（patch）

## 不纳入本次

- 通知栏/锁屏封面加载失败时的自愈逻辑（解码失败重提内嵌）——本方案已消除孤儿窗口，无需自愈。
