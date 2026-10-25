# Tasks

- [x] proposal.md / spec.md 编写
- [x] CacheDirs.kt：新增 coverFilesDir
- [x] CoverManager.kt：extractEmbeddedCover 写入目录改 coverFilesDir
- [x] AppDao.kt：新增 migrateCoverCachePrefix（REPLACE 前缀改写）
- [x] StoreMigration.kt：新增 CoverDirMigration（复制→改库→删旧，prefs 幂等）
- [x] MainActivity.kt：onCreate 接入 CoverDirMigration
- [x] CoverDirMigrationTest（Robolectric：搬移/前缀改写/旧目录删除/无旧目录/幂等）
- [x] 全量 UT 回归（应 273+ 全绿）+ 编译
- [x] 升 patch 2.1.26（versionCode 95）+ assembleDebug
- [x] SPECS.md 补条文 + 归档（openspec）+ git 提交 + 交付
