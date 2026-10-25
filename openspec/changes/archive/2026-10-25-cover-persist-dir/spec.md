# Spec: cover-persist-dir

## 背景

内嵌封面提取产物（`file://…/cache/covers/*.jpg`）被 `works.coverUri` 持久引用，但 cacheDir 是易失层（系统低存储自动清、"清除缓存"即删），导致"清缓存不清数据"后封面路径悬空、静默回退占位、无自愈。根因是**持久层引用易失层派生缓存路径**。

## 目标位置

内嵌封面提取产物 MUST 写入 `context.filesDir.resolve("covers")`（Android 11+ 实路径 `/data/user/0/com.booklistener/files/covers/`，随 applicationId 变化）。该目录与 Room 数据库（`files/databases/`）同生命周期：清除缓存不删、清除数据/卸载连库一起删，**孤儿窗口归零**。

## Requirements

### Requirement: 提取产物写入 filesDir/covers

`CoverManager.extractEmbeddedCover` 提取内嵌封面 MUST 写入 `CacheDirs.coverFilesDir(app)` 并返回 `file://` uri；提取失败/无内嵌封面 MUST 返回 null（行为不变）。`CacheDirs` MUST 新增 `coverFilesDir(context)`（`filesDir.resolve(COVERS_DIR_NAME)`）；旧 `coverCacheDir`（`cacheDir/covers`）保留定义，仅供迁移读取。

#### Scenario: 无封面音频

- **WHEN** 音频无内嵌封面，调用 extractEmbeddedCover
- **THEN** 返回 null，不创建任何文件（现状回归）

### Requirement: 启动迁移（幂等）

MainActivity.onCreate MUST 在 `StoreMigration.run` 后调用 `CoverDirMigration.run`：
- 旧 `cache/covers` 不存在或为空 → 直接置 prefs 标记跳过，DB 不动。
- 旧目录有文件 → **先复制**到 `files/covers`（同名覆盖）→ 执行 `UPDATE works SET coverUri = REPLACE(coverUri, '/cache/covers/', '/files/covers/') WHERE coverUri LIKE '%/cache/covers/%'` → **再删除旧目录**。
- 已置标记（prefs FLAG）→ 跳过，幂等。

#### Scenario: 有旧缓存文件

- **WHEN** cache/covers 下有 a.jpg，works 表有 `file://…/cache/covers/a.jpg`
- **THEN** 迁移后 a.jpg 在 files/covers 且文件内容一致，works 表该行 coverUri 前缀改写为 `/files/covers/`，cache/covers 已删除

#### Scenario: 无旧目录

- **WHEN** cache/covers 不存在
- **THEN** 仅置标记，works 表零改动，无异常

#### Scenario: 重复执行

- **WHEN** 迁移标记已存在，再次调用
- **THEN** 直接返回，不重复搬移/改写

### Requirement: audio_cache 不动

`cacheDir/audio_cache`（完整音频缓存）MUST NOT 迁移——无持久字段引用，继续留在 cacheDir。

## 验收

1. 迁移用例（Robolectric）：搬移内容一致、前缀改写、旧目录删除、无旧目录跳过、幂等——全部通过。
2. 既有全量 UT 全绿（应 273+）。
3. 编译通过；versionCode 95 / versionName '2.1.26'；debug 包交付。
