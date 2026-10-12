package com.booklistener.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.booklistener.core.AlbumStore
import com.booklistener.core.AlbumSummary
import com.booklistener.core.toAudioItem
import com.booklistener.core.AppLog
import com.booklistener.core.AudioItem
import com.booklistener.core.CoverManager
import com.booklistener.core.CoverScanner
import com.booklistener.core.EntryIndexStore
import com.booklistener.core.FolderEntry
import com.booklistener.core.FolderProgressLogic
import com.booklistener.core.FolderProgressStore
import com.booklistener.core.FolderScanner
import com.booklistener.core.IndexedFile
import com.booklistener.core.LastSessionLogic
import com.booklistener.core.MessageCatalog
import com.booklistener.core.OpenFileItemFactory
import com.booklistener.core.SavedEntry
import com.booklistener.core.sortIndexedFiles
import com.booklistener.core.TagResolver
import com.booklistener.core.SavedEntryStore
import com.booklistener.core.SearchHit
import com.booklistener.core.SourceType
import com.booklistener.core.toIndexedFile
import com.booklistener.core.UiState
import com.booklistener.core.folderDisplayName
import com.booklistener.core.searchEntries
import com.booklistener.data.MediaRepository
import com.booklistener.player.MediaPlayService
import com.booklistener.player.PlayableItem
import com.booklistener.player.PlaybackController
import com.booklistener.player.PlaybackState
import com.booklistener.source.WebDavAudioSource
import com.booklistener.source.WebDavCredential
import com.booklistener.source.WebDavDirEntry
import com.booklistener.source.normalizeWebDavUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** v1.6.7 一次性定时模式（详情页定时弹窗选中态） */
enum class SleepTimerMode { OFF, END_OF_TRACK, MINUTES }

/** v1.6.25 WebDAV 账号编辑表单状态（配置页账号管理） */
data class WebDavFormState(
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    val editing: Boolean = false,
    val editingBaseUrl: String = "",
    val testing: Boolean = false,
    val testOk: Boolean? = null,
    val testResult: String? = null,
    val savedMsg: String? = null
)

/** 主界面 ViewModel：已添加内容（本地文件夹 + 网盘源）浏览、搜索、播放控制入口。
 * 播放动作统一走 PlaybackController（进程单例），Service 由首次播放时拉起。
 *
 * 注意：必须保留单 Application 参数构造（@JvmOverloads 生成）——
 * AndroidViewModelFactory 反射按 (Application) 构造创建，缺它会 NoSuchMethodException 启动即崩。
 */
class PlayerViewModel @JvmOverloads constructor(
    app: Application,
    /** 测试注入：默认生产单例（全局 db）；UI 测试传 in-memory 避免跨用例串数据 */
    private val database: com.booklistener.db.AppDatabase? = null,
    /** 测试注入：默认加密 CredentialStore；JVM 单测传 secure=false 避免 AndroidKeyStore 缺失 */
    credentialStore: com.booklistener.auth.CredentialStore? = null
) : AndroidViewModel(app) {

    private val repo = MediaRepository(app, credentialStore)
    private val openFileFactory = OpenFileItemFactory { uri ->
        runCatching {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                    name to size
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private val _directoryState = MutableStateFlow<UiState<List<AudioItem>>>(UiState.Loading)
    val directoryState: StateFlow<UiState<List<AudioItem>>> = _directoryState.asStateFlow()

    private val _currentSource = MutableStateFlow(SourceType.LOCAL)
    val currentSource: StateFlow<SourceType> = _currentSource.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = PlaybackController.state

    private val _eventMsg = MutableStateFlow<String?>(null)
    val eventMsg: StateFlow<String?> = _eventMsg.asStateFlow()

    /** 一次性事件：切换条目后就绪时请求弹出播放详情（MainScreen 消费后复位） */
    private val _requestPlayerSheet = MutableStateFlow(false)
    val requestPlayerSheet: StateFlow<Boolean> = _requestPlayerSheet.asStateFlow()

    // ---- WebDAV 账号管理状态（v1.6.25 多账号；声明须在 init 之前） ----
    private val _webDavAccounts = MutableStateFlow<List<WebDavCredential>>(emptyList())
    val webDavAccounts: StateFlow<List<WebDavCredential>> = _webDavAccounts.asStateFlow()

    private val _webDavForm = MutableStateFlow(WebDavFormState())
    val webDavForm: StateFlow<WebDavFormState> = _webDavForm.asStateFlow()

    // ---- 专辑内标题显示模式（v1.6.3）：true=标签标题（默认）/ false=原始文件名 ----
    private val prefs = app.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
    private val _albumTitleMode = MutableStateFlow(prefs.getBoolean(KEY_ALBUM_TITLE_MODE, false))
    val albumTitleMode: StateFlow<Boolean> = _albumTitleMode.asStateFlow()

    fun setAlbumTitleMode(useRawName: Boolean) {
        prefs.edit().putBoolean(KEY_ALBUM_TITLE_MODE, useRawName).commit()
        _albumTitleMode.value = useRawName
    }

    // ---- v1.6.7 循环模式：顺序 / 单曲 / 列表（Media3 RepeatMode），持久化 ----
    private val _repeatMode = MutableStateFlow(prefs.getInt(KEY_REPEAT_MODE, androidx.media3.common.Player.REPEAT_MODE_OFF))
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    fun setRepeatMode(mode: Int) {
        _repeatMode.value = mode
        prefs.edit().putInt(KEY_REPEAT_MODE, mode).commit()
        PlaybackController.setRepeatMode(mode)
    }

    /** 三档轮换：顺序 → 单曲 → 列表 → 顺序 */
    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ONE
            androidx.media3.common.Player.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        setRepeatMode(next)
    }

    // ---- v1.6.7 定时关闭：一次性（设置时刻起倒计时，到点暂停）----
    private val _sleepOnceRemaining = MutableStateFlow<Long?>(null)
    val sleepOnceRemaining: StateFlow<Long?> = _sleepOnceRemaining.asStateFlow()
    private val _sleepOnceMode = MutableStateFlow(SleepTimerMode.OFF)
    val sleepOnceMode: StateFlow<SleepTimerMode> = _sleepOnceMode.asStateFlow()
    private val _sleepOnceMinutes = MutableStateFlow(0)
    val sleepOnceMinutes: StateFlow<Int> = _sleepOnceMinutes.asStateFlow()
    private var sleepOnceJob: kotlinx.coroutines.Job? = null

    fun setSleepTimerOnce(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimerOnce()
            return
        }
        _sleepOnceMode.value = SleepTimerMode.MINUTES
        _sleepOnceMinutes.value = minutes
        startSleepOnce(minutes * 60_000L)
    }

    /** 听完本章节：剩余 = 当前曲目时长 - 当前位置，到点暂停 */
    fun setSleepTimerToEndOfTrack() {
        val st = PlaybackController.state.value
        if (st.durationMs <= 0L) {
            _eventMsg.value = MessageCatalog.MSG_SLEEP_END_UNKNOWN
            return
        }
        _sleepOnceMode.value = SleepTimerMode.END_OF_TRACK
        _sleepOnceMinutes.value = 0
        startSleepOnce((st.durationMs - st.positionMs).coerceAtLeast(0L))
    }

    /**
     * 启动一次性倒计时（internal 暴露毫秒级剩余时长供测试注入）。
     * 用 delay 递减实现，不依赖系统时钟（Robolectric 虚拟推进可测）。
     */
    internal fun startSleepOnce(remainingMs: Long) {
        sleepOnceJob?.cancel()
        sleepOnceJob = viewModelScope.launch {
            var remain = remainingMs
            _sleepOnceRemaining.value = remain
            while (remain > 0) {
                delay(1000)
                remain -= 1000
                _sleepOnceRemaining.value = remain.coerceAtLeast(0)
            }
            PlaybackController.pause()
            _sleepOnceRemaining.value = null
            _sleepOnceMode.value = SleepTimerMode.OFF
            _sleepOnceMinutes.value = 0
            _eventMsg.value = MessageCatalog.MSG_SLEEP_TIMER_DONE
        }
    }

    fun cancelSleepTimerOnce() {
        sleepOnceJob?.cancel()
        _sleepOnceRemaining.value = null
        _sleepOnceMode.value = SleepTimerMode.OFF
        _sleepOnceMinutes.value = 0
    }

    // ---- v1.6.7 全局定时关闭：每次开始播放重置倒计时，到点暂停；一次性激活期间不触发 ----
    private val _globalSleepMinutes = MutableStateFlow(prefs.getInt(KEY_GLOBAL_SLEEP_MINUTES, 0))
    val globalSleepMinutes: StateFlow<Int> = _globalSleepMinutes.asStateFlow()
    private var globalSleepJob: kotlinx.coroutines.Job? = null

    fun setGlobalSleepMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_GLOBAL_SLEEP_MINUTES, minutes).commit()
        _globalSleepMinutes.value = minutes
        if (minutes <= 0) {
            globalSleepJob?.cancel()
            globalSleepJob = null
            return
        }
        // 设置时若正在播放，立即按当前播放会话开始计时
        if (PlaybackController.state.value.isPlaying) startGlobalSleepTimer(minutes)
    }

    /** 启动全局倒计时（internal 暴露分钟粒度供测试注入 0 分钟立即到点） */
    internal fun startGlobalSleepTimer(minutes: Int) {
        globalSleepJob?.cancel()
        globalSleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            if (_sleepOnceRemaining.value != null) return@launch
            PlaybackController.pause()
            _eventMsg.value = MessageCatalog.MSG_GLOBAL_SLEEP_DONE
        }
    }

    // ---- v1.6.7 AB 段重复：设 A/B 点，播放到 B 自动跳回 A；换曲自动清除 ----
    private val _abA = MutableStateFlow<Long?>(null)
    val abA: StateFlow<Long?> = _abA.asStateFlow()
    private val _abB = MutableStateFlow<Long?>(null)
    val abB: StateFlow<Long?> = _abB.asStateFlow()

    /**
     * AB 段单键切换：未设 A → 设 A（当前播放位置）；已设 A 未设 B → 设 B（须 > A）；
     * A/B 均已设置 → 从当前位置重新设 A（旧 B 失效）。
     */
    fun toggleAbPoint() {
        val pos = PlaybackController.state.value.positionMs
        val a = _abA.value
        val b = _abB.value
        when {
            a == null -> _abA.value = pos
            b == null && pos > a -> _abB.value = pos
            b != null -> {
                _abA.value = pos
                _abB.value = null
            }
            // 其余：B 未设且新位置 <= A，静默忽略
        }
    }

    fun clearAb() {
        _abA.value = null
        _abB.value = null
    }

    /** 播放位置到达 B 点时应跳回 A 循环（纯逻辑，可单测） */
    internal fun shouldLoopAb(positionMs: Long, abA: Long?, abB: Long?): Boolean =
        abA != null && abB != null && abB > abA && positionMs >= abB

    // ---- 文件夹浏览（SAF OpenDocumentTree）----
    // v1.6.0：扫描注入真实标签解析（title/artist/track/disc → 显示名与排序）
    private val tagResolver = TagResolver(app)
    private val folderScanner = FolderScanner(::queryFolderChildren, tagResolver = { uri, name ->
        tagResolver.resolve(android.net.Uri.parse(uri))
    })
    private val coverManager = CoverManager(app, CoverScanner(::queryFolderChildren))
    private val _folderItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val folderItems: StateFlow<List<AudioItem>> = _folderItems.asStateFlow()
    private val _inFolderView = MutableStateFlow(false)
    val inFolderView: StateFlow<Boolean> = _inFolderView.asStateFlow()

    // ---- WebDAV 目录导航（v1.6.27）：当前目录的子目录 + 路径栈（href，空=根） ----
    private val _webDavDirs = MutableStateFlow<List<WebDavDirEntry>>(emptyList())
    val webDavDirs: StateFlow<List<WebDavDirEntry>> = _webDavDirs.asStateFlow()
    private val _webDavPathStack = MutableStateFlow<List<String>>(emptyList())
    val webDavPathStack: StateFlow<List<String>> = _webDavPathStack.asStateFlow()

    // ---- 已添加内容（本地文件夹 + 网盘源，统一模型）----
    // v1.6.0：三个 Store 迁移到 Room（prefs 旧数据由 StoreMigration 首启搬运）
    private val appDb = database ?: com.booklistener.db.AppDatabase.getInstance(app)
    private val entryStore = SavedEntryStore(appDb.dao())
    private val indexStore = EntryIndexStore(appDb.dao())
    // v1.6.28：加载时过滤旧的"网盘根条目"（自动固化、uri 非完整 URL）——
    // 网盘改为只通过文件夹管理固化具体文件夹；账号凭证保留在 CredentialStore。
    // v1.6.30：存量网盘条目若把账号邮箱写进了 artist（旧固化逻辑），启动时清掉。
    private val _savedEntries = MutableStateFlow(
        entryStore.load()
            .filterNot { it.sourceType == SourceType.WEBDAV && !it.uri.startsWith("http") }
            .map { if (it.sourceType == SourceType.WEBDAV && it.artist != null) it.copy(artist = null) else it }
    )
    val savedEntries: StateFlow<List<SavedEntry>> = _savedEntries.asStateFlow()

    /** 兼容旧 API：本地固化文件夹 Uri 列表（由新模型同步派生，保证立即读取一致） */
    private val _savedFoldersCompat = MutableStateFlow(
        _savedEntries.value.filter { it.isLocal }.map { Uri.parse(it.uri) }
    )
    val savedFolders: StateFlow<List<Uri>> = _savedFoldersCompat.asStateFlow()

    /** 统一更新入口：同步维护新模型与兼容派生，避免异步时序 */
    private fun setEntries(entries: List<SavedEntry>) {
        _savedEntries.value = entries
        _savedFoldersCompat.value = entries.filter { it.isLocal }.map { Uri.parse(it.uri) }
    }

    // ---- 搜索（已添加内容：文件夹名 + 文件名）----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults.asStateFlow()

    // 当前打开的条目（用于文件夹视图标题名称）
    private val _currentFolderUri = MutableStateFlow<Uri?>(null)
    val currentFolderUri: StateFlow<Uri?> = _currentFolderUri.asStateFlow()

    // ---- v1.7.0 专辑层（曲目界面：按专辑聚合，db 分页）----
    private val albumStore = AlbumStore(appDb.dao())
    private val _recentAlbums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val recentAlbums: StateFlow<List<AlbumSummary>> = _recentAlbums.asStateFlow()
    private val _neverAlbums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val neverAlbums: StateFlow<List<AlbumSummary>> = _neverAlbums.asStateFlow()
    private val _albumsEnded = MutableStateFlow(false)
    val albumsEnded: StateFlow<Boolean> = _albumsEnded.asStateFlow()
    private var recentPage = 0
    private var neverPage = 0
    private var albumsLoading = false

    /** 当前打开的专辑（专辑视图标题 + 歌曲列表，db 加载不重扫） */
    private val _currentAlbum = MutableStateFlow<AlbumSummary?>(null)
    val currentAlbum: StateFlow<AlbumSummary?> = _currentAlbum.asStateFlow()
    private val _albumSongs = MutableStateFlow<List<IndexedFile>>(emptyList())
    val albumSongs: StateFlow<List<IndexedFile>> = _albumSongs.asStateFlow()
    private val _albumSongsEnded = MutableStateFlow(false)
    val albumSongsEnded: StateFlow<Boolean> = _albumSongsEnded.asStateFlow()
    private var songPage = 0
    private var songsLoading = false

    /** 重置并加载专辑两区首页（进入曲目 tab / 数据变更后调用） */
    fun refreshAlbums() {
        recentPage = 0
        neverPage = 0
        _albumsEnded.value = false
        _recentAlbums.value = emptyList()
        _neverAlbums.value = emptyList()
        loadAlbumsMore()
    }

    /** 分区滑动加载：已播放（新→旧）与未播放首页同时加载；触底后翻页 */
    fun loadAlbumsMore() {
        if (albumsLoading || _albumsEnded.value) return
        albumsLoading = true
        viewModelScope.launch {
            // 首页两区并行；后续翻页只翻已播放区（未播放区通常总量小，同样支持翻页）
            if (recentPage == 0 && neverPage == 0) {
                val r = withContext(Dispatchers.IO) { albumStore.recentPlayed(0) }
                val n = withContext(Dispatchers.IO) { albumStore.notPlayed(0) }
                _recentAlbums.value = r
                _neverAlbums.value = n
                recentPage = 1
                neverPage = 1
                _albumsEnded.value = r.size < AlbumStore.PAGE_SIZE && n.size < AlbumStore.PAGE_SIZE
                albumsLoading = false
            } else if (recentPage <= neverPage || true) {
                val next = withContext(Dispatchers.IO) { albumStore.recentPlayed(recentPage) }
                if (next.isEmpty()) {
                    _albumsEnded.value = true
                } else {
                    _recentAlbums.value = _recentAlbums.value + next
                    recentPage++
                    _albumsEnded.value = next.size < AlbumStore.PAGE_SIZE
                }
                albumsLoading = false
            } else {
                val next = withContext(Dispatchers.IO) { albumStore.notPlayed(neverPage) }
                if (next.isEmpty()) {
                    _albumsEnded.value = true
                } else {
                    _neverAlbums.value = _neverAlbums.value + next
                    neverPage++
                }
                albumsLoading = false
            }
        }
    }

    /**
     * 打开专辑：从 db 加载歌曲（不重扫、不自动播放），进入专辑视图。
     * 同时恢复"切换播放记录"行为：目标非当前正在播放队列时，暂停当前并切换为该专辑
     * 的独立播放记录（就绪不自动播放），并弹出播放详情；正在播放同一专辑则保持不打断。
     * 封面沿用来源条目 coverUri；专辑内歌曲全量排序后内存分页（100/页）。
     */
    fun openAlbum(album: AlbumSummary) {
        _currentAlbum.value = album
        songPage = 0
        _albumSongsEnded.value = false
        _albumSongs.value = emptyList()
        _inFolderView.value = true
        _currentFolderUri.value = Uri.parse(album.entryId)
        loadAlbumSongsMore()
        resolveCurrentAlbumCover(album)
        enterAlbumWithRecord(album)
    }

    /** 懒解析当前专辑封面（单专辑用文件夹图/多专辑用专辑内首曲内嵌封面，无则 null 占位） */
    private fun resolveCurrentAlbumCover(album: AlbumSummary) {
        if (album.coverUri != null) return
        val entry = savedEntryById(album.entryId) ?: return
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { albumStore.allSongs(album.entryId, album.album) }
            if (all.isEmpty()) return@launch
            val cover = coverManager.resolveAlbumCover(
                entry.coverUri, album.albumCountInEntry, all.first().uri
            )
            _currentAlbum.value = _currentAlbum.value?.copy(coverUri = cover)
        }
    }

    /** 切换播放上下文到目标专辑记录（db 全量歌曲，就绪不自动播放；正在播放同一专辑则不打断） */
    private fun enterAlbumWithRecord(album: AlbumSummary) {
        val entryId = album.entryId
        if (!shouldRestartOnEnter(entryId, PlaybackController.state.value.isPlaying)) return
        saveCurrentPlaybackProgress()
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { albumStore.allSongs(entryId, album.album) }
            if (all.isEmpty()) return@launch
            val sourceType = savedEntryById(entryId)?.sourceType ?: SourceType.LOCAL
            val items = all.map { it.toAudioItem(sourceType) }
            val record = progressStore.load(entryId)
            val (startIndex, startPos) = FolderProgressLogic.resolveStart(items.size, record)
            lastSavedIndex = startIndex
            lastSavedUri = entryId
            val entry = savedEntryById(entryId)
            val cover = entry?.let { coverManager.resolveAlbumCover(it.coverUri, album.albumCountInEntry, all.firstOrNull()?.uri) }
            MediaPlayService.start(getApplication())
            PlaybackController.prepareFolder(resolveAll(items, coverUri = cover), startIndex, startPos, entryId)
            // 切换专辑后自动弹出播放详情（展示就绪状态），但不直接开始播放
            _requestPlayerSheet.value = true
        }
    }

    /** 专辑歌曲滑动加载下一页 */
    fun loadAlbumSongsMore() {
        val album = _currentAlbum.value ?: return
        if (songsLoading || _albumSongsEnded.value) return
        songsLoading = true
        viewModelScope.launch {
            val pageItems = withContext(Dispatchers.IO) {
                albumStore.songs(album.entryId, album.album, songPage)
            }
            if (pageItems.isEmpty()) {
                _albumSongsEnded.value = true
            } else {
                _albumSongs.value = _albumSongs.value + pageItems
                songPage++
                _albumSongsEnded.value = pageItems.size < AlbumStore.PAGE_SIZE
            }
            songsLoading = false
        }
    }

    /** 打开来源条目的专辑视图（中间播放按钮后台切换用，db 索引构造专辑摘要，不重扫） */
    fun openEntryAlbum(entry: SavedEntry) {
        val files = indexStore.load()[entry.id] ?: emptyList()
        val albumCount = files.map { it.album ?: entry.displayName }.distinct().size
        val summary = AlbumSummary(
            album = files.firstNotNullOfOrNull { it.album } ?: entry.displayName,
            entryId = entry.id,
            artist = files.firstNotNullOfOrNull { it.artist } ?: entry.artist,
            songCount = files.size,
            totalDurationMs = files.mapNotNull { it.durationMs }.sum(),
            albumCountInEntry = albumCount.coerceAtLeast(1),
            coverUri = coverManager.resolveAlbumCover(
                entry.coverUri, albumCount.coerceAtLeast(1), files.firstOrNull()?.uri
            )
        )
        openAlbum(summary)
    }

    /** 播放专辑队列（点击专辑内歌曲 → 以该曲为起点整专播放，db 数据不入文件夹进度） */
    fun playAlbumFrom(album: AlbumSummary, startIndex: Int) {
        // 播放专辑时同步当前播放上下文（详情页/正在播放卡/通知封面定位）
        _currentAlbum.value = album
        resolveCurrentAlbumCover(album)
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { albumStore.allSongs(album.entryId, album.album) }
            if (all.isEmpty()) return@launch
            val sourceType = savedEntryById(album.entryId)?.sourceType ?: SourceType.LOCAL
            val items = all.map { it.toAudioItem(sourceType) }
            val entry = savedEntryById(album.entryId)
            val cover = entry?.let { coverManager.resolveAlbumCover(it.coverUri, album.albumCountInEntry, all.firstOrNull()?.uri) }
            MediaPlayService.start(getApplication())
            PlaybackController.setQueue(
                resolveAll(items, coverUri = cover),
                startIndex.coerceIn(0, items.lastIndex),
                ownerId = album.entryId
            )
        }
    }

    // ---- 条目独立播放进度 ----
    private val progressStore = FolderProgressStore(appDb.dao())
    private var lastSavedIndex = -1
    private var lastSavedUri: String? = null

    // 最近一次标记播放过的条目（节流，避免每次状态变化写盘）
    private var lastMarkedEntryId: String? = null

    companion object {
        private const val KEY_ALBUM_TITLE_MODE = "album_title_mode"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_GLOBAL_SLEEP_MINUTES = "global_sleep_minutes"
    }

    init {
        refreshDirectory()
        refreshWebDavAccounts()
        // 存量修正：早期 folderDisplayName 把路径当文件夹名，启动归一为纯文件夹名（写回 db）
        normalizeFolderDisplayNames()
        // v1.6.30：存量网盘条目把账号邮箱写进 artist 的，清空并写回（显示不再出现云盘账号）
        cleanStaleWebDavArtist()
        // 杀后台重启恢复：回到最近播放条目的条目视图（就绪、不自动播放、不弹详情）
        restoreLastSession()
        // 切歌/播放状态变化时即时保存进度（避免 5s 节流窗口丢失）；播放开始时更新条目 lastPlayedAt
        viewModelScope.launch {
            var lastIsPlaying = false
            var lastQueueIndex = -1
            PlaybackController.state.collect { st ->
                val uri = _currentFolderUri.value?.toString() ?: return@collect
                val idx = st.queueIndex
                if (st.isPlaying && idx >= 0 && st.currentItem != null) {
                    markCurrentEntryPlayed()
                    if (idx != lastSavedIndex || uri != lastSavedUri) {
                        progressStore.save(uri, idx, st.positionMs)
                        lastSavedIndex = idx
                        lastSavedUri = uri
                    }
                }
                // v1.7.0：时长补录（网盘或扫描未知时长 → 播放器拿到实际时长后回写 db）
                if (st.durationMs > 0L && st.currentItem != null && st.currentItem.durationMs == null) {
                    indexStore.updateDurationMs(uri, st.currentItem.path, st.durationMs)
                }
                // v1.6.7：全局定时关闭——每次开始播放重置倒计时
                if (st.isPlaying && !lastIsPlaying && _globalSleepMinutes.value > 0) {
                    startGlobalSleepTimer(_globalSleepMinutes.value)
                }
                lastIsPlaying = st.isPlaying
                // v1.6.7：换曲自动清除 AB 段（队列条目切换）
                if (idx != lastQueueIndex) {
                    if (_abA.value != null || _abB.value != null) clearAb()
                    lastQueueIndex = idx
                }
                // v1.6.7：AB 段循环——播放位置到达 B 跳回 A（不打断播放状态）
                if (shouldLoopAb(st.positionMs, _abA.value, _abB.value)) {
                    PlaybackController.seekTo(_abA.value ?: 0L)
                }
            }
        }
        // 播放中节流保存（每 5s，position 持续推进）
        viewModelScope.launch {
            while (true) {
                delay(5000)
                saveCurrentPlaybackProgress()
            }
        }
    }

    override fun onCleared() {
        saveCurrentPlaybackProgress()
        super.onCleared()
    }

    fun refreshDirectory() {
        viewModelScope.launch {
            _directoryState.value = UiState.Loading
            val result = repo.listDirectory(_currentSource.value, "")
            _directoryState.value = when {
                result.isFailure -> UiState.Error(result.exceptionOrNull()?.message ?: "load failed")
                result.getOrThrow().isEmpty() -> UiState.Empty
                else -> UiState.Success(result.getOrThrow())
            }
        }
    }

    fun switchSource(type: SourceType) {
        _currentSource.value = type
        refreshDirectory()
    }

    fun isAliyunAuthorized(): Boolean = repo.aliyunAuth.isAuthorized()

    /** 阿里云授权回调：从回调 Uri 提取 code 并完成 token 交换 */
    fun handleAliyunCallback(uri: Uri?) {
        val code = uri?.getQueryParameter("code") ?: return
        viewModelScope.launch {
            val result = repo.aliyunAuth.exchangeCode(code)
            _eventMsg.value = if (result.isSuccess) "阿里云盘授权成功" else "授权失败：${result.exceptionOrNull()?.message}"
            if (result.isSuccess) refreshDirectory()
        }
    }

    /** 播放目录（作为队列）：逐个解析真实播放 Uri 后入队播放 */
    fun playItems(items: List<AudioItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        MediaPlayService.start(getApplication())
        viewModelScope.launch {
            PlaybackController.setQueue(resolveAll(items), startIndex.coerceIn(0, items.lastIndex))
        }
    }

    /** 逐个解析条目为可播放项（resolve 失败用原始 path 兜底，保证整队可用） */
    private suspend fun resolveAll(items: List<AudioItem>, coverUri: String? = null): List<PlayableItem> {
        val playable = mutableListOf<PlayableItem>()
        for (item in items) {
            val uri = repo.resolver.resolve(item)
                .getOrElse { e ->
                    AppLog.e("resolve ${item.displayName} failed", e)
                    item.path
                }
            playable.add(PlayableItem(item, uri, coverUri))
        }
        return playable
    }

    /**
     * 打开单个本地音频文件（SAF 文件选择器或外部应用 ACTION_VIEW/ACTION_SEND 传入的 content:// Uri）。
     * 元数据（文件名/大小）经 ContentResolver 查询，不依赖 MediaStore，无需额外存储权限。
     */
    fun playUri(uri: Uri) {
        val item = openFileFactory.build(uri)
        playItems(listOf(item), 0)
    }

    // ---- 已添加内容管理 ----

    /**
     * 统一"进入条目视图并加载列表"模板（本地 SAF 扫描 / 网盘目录浏览共用，霰弹收敛 A1）：
     * 保存进度 → 固化基础状态 → 进入条目视图 → IO 加载 → 按播放层队列所属判定是否恢复队列
     * （同条目播放中不打断）→ 就绪不自动播放 → 可选项：自动弹详情 / 扫描后处理（索引/封面）。
     */
    private fun openEntryView(
        entryId: String,
        entryUri: Uri,
        emptyMessage: String,
        showSheet: Boolean,
        loadItems: suspend () -> List<AudioItem>,
        afterLoad: (List<AudioItem>) -> Unit = {}
    ) {
        saveCurrentPlaybackProgress()
        markOpened(entryId)
        _currentFolderUri.value = entryUri
        viewModelScope.launch {
            _inFolderView.value = true
            val items = withContext(Dispatchers.IO) {
                runCatching { loadItems() }
                    .getOrElse { e ->
                        AppLog.e("load entry items failed: $entryId", e)
                        emptyList()
                    }
            }
            _folderItems.value = items
            withContext(Dispatchers.IO) { afterLoad(items) }
            if (items.isNotEmpty()) {
                // 正在播放的同一个文件夹再次进入：仅展示列表，不重新 prepare（保持播放不暂停）。
                // 判定基于播放层队列所属（currentQueueOwnerId），而非易清空的 _currentFolderUri。
                if (shouldRestartOnEnter(entryId, PlaybackController.state.value.isPlaying)) {
                    // 恢复该文件夹的播放记录：定位到记录曲目/位置，不自动播放
                    enterFolderWithRecord(entryUri, items)
                    // 切换文件夹后自动弹出播放详情（展示就绪状态），但不直接开始播放
                    if (showSheet) _requestPlayerSheet.value = true
                }
            }
            _eventMsg.value = if (items.isEmpty()) emptyMessage else MessageCatalog.MSG_FOUND.format(items.size)
        }
    }

    /** 扫描 SAF 文件夹并固化条目（含封面/文件数/内容索引），进入文件夹视图就绪不播 */
    fun scanFolder(treeUri: Uri, showSheet: Boolean = true) {
        // 同步固化基础条目（保证 UI 与兼容 API 立即可见），异步加载后原位补充封面/文件数
        saveFolder(treeUri)
        openEntryView(
            entryId = treeUri.toString(),
            entryUri = treeUri,
            emptyMessage = MessageCatalog.EMPTY_DIR_AUDIO,
            showSheet = showSheet,
            loadItems = {
                loadFromIndex(treeUri.toString(), SourceType.LOCAL) ?: run {
                    val items = folderScanner.scan(treeUri.toString())
                    indexStore.save(treeUri.toString(), items.map { it.toIndexedFile() })
                    items
                }
            },
            afterLoad = { items ->
                val entry = buildLocalEntry(treeUri, items, _savedEntries.value.firstOrNull { it.id == treeUri.toString() })
                setEntries(entryStore.replace(_savedEntries.value, entry))
            }
        )
    }

    /** v1.6.14：从数据库索引读取条目文件清单（按展示序排序后转 AudioItem）；无数据返回 null 由调用方兜底 */
    private suspend fun loadFromIndex(entryId: String, sourceType: SourceType): List<AudioItem>? {
        val files = indexStore.loadEntry(entryId)
        if (files.isEmpty()) return null
        return sortIndexedFiles(files).map { it.toAudioItem(sourceType) }
    }

    /**
     * 进入条目视图时是否需要重新加载播放队列：
     * 队列已就绪（存在可播放内容）且队列所属 == 目标条目 id → false（保持现有会话，播放/暂停均不打断）；
     * 切换文件夹 / 队列未就绪 / 无队列归属 → true（走记录恢复，就绪不自动播放）。
     * v1.6.9：判定源由 isPlaying 改为 queueReady——暂停态点击封面恢复播放是异步的，
     * 旧判定在暂停态恒判重启并覆盖刚恢复的播放（点击暂停封面进详情再次停止播放的根因）。
     */
    fun shouldRestartOnEnter(targetId: String, isPlaying: Boolean): Boolean =
        !(PlaybackController.currentQueueOwnerId == targetId && PlaybackController.queueReady)

    /** 按条目 id 查找已添加条目（播放层队列所属 → 定位到对应文件夹） */
    fun savedEntryById(id: String): SavedEntry? =
        _savedEntries.value.firstOrNull { it.id == id }

    /** 打开已添加条目：本地走 SAF 扫描，网盘走数据源目录浏览；showSheet=false 用于启动静默恢复
     *  v1.6.14：优先从 db 索引读取；db 无数据时兜底浏览一次并写库（强制重扫走文件夹管理页刷新） */
    // ---- WebDAV 账号管理（v1.6.25 多账号：列表/添加/编辑/删除/测试） ----

    private fun refreshWebDavAccounts() {
        _webDavAccounts.value = repo.credentialStore.webDavAccounts()
    }

    fun webDavFormUrlChange(v: String) = _webDavForm.update { it.copy(url = v) }
    fun webDavFormUserChange(v: String) = _webDavForm.update { it.copy(user = v) }
    fun webDavFormPassChange(v: String) = _webDavForm.update { it.copy(pass = v) }

    /** 打开"添加账号"空表单 */
    fun startNewWebDavForm() {
        _webDavForm.value = WebDavFormState()
    }

    /** 打开已有账号编辑表单 */
    fun startEditWebDavForm(cred: WebDavCredential) {
        _webDavForm.value = WebDavFormState(
            url = cred.baseUrl,
            user = cred.username,
            pass = cred.password,
            editing = true,
            editingBaseUrl = cred.normalizedUrl
        )
    }

    fun cancelWebDavForm() {
        _webDavForm.value = WebDavFormState()
    }

    fun consumeWebDavSavedMsg() = _webDavForm.update { it.copy(savedMsg = null) }

    /** 保存并测试连接：凭证落盘（v1.6.28：不再自动固化根条目）+ 立即 PROPFIND 验证登录 */
    fun saveAndTestWebDav() {
        val f = _webDavForm.value
        if (f.url.isBlank() || f.user.isBlank() || f.pass.isBlank()) {
            _webDavForm.update { it.copy(savedMsg = "WebDAV 地址、用户名、密码不能为空") }
            return
        }
        val cred = WebDavCredential(f.url.trim(), f.user.trim(), f.pass)
        repo.credentialStore.addWebDavAccount(cred)
        refreshWebDavAccounts()
        _webDavForm.update {
            it.copy(
                editing = true,
                editingBaseUrl = cred.normalizedUrl,
                testing = true,
                testOk = null,
                testResult = null,
                savedMsg = "已保存，正在测试连接…"
            )
        }
        testWebDavWith(cred)
    }

    /** 仅验证当前表单输入能否登录（不落盘、不改条目） */
    fun testWebDavOnly() {
        val f = _webDavForm.value
        if (f.url.isBlank() || f.user.isBlank() || f.pass.isBlank()) {
            _webDavForm.update { it.copy(savedMsg = "请先填写 WebDAV 地址、用户名、密码") }
            return
        }
        val cred = WebDavCredential(f.url.trim(), f.user.trim(), f.pass)
        _webDavForm.update { it.copy(testing = true, testOk = null, testResult = null, savedMsg = null) }
        testWebDavWith(cred)
    }

    private fun testWebDavWith(cred: WebDavCredential) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = WebDavAudioSource { cred }
            val result = source.probeDirectory(cred.baseUrl)
            _webDavForm.update {
                it.copy(
                    testing = false,
                    savedMsg = null,
                    testOk = result.isSuccess,
                    testResult = formatWebDavTestResult(result)
                )
            }
        }
    }

    private fun formatWebDavTestResult(result: Result<Pair<List<AudioItem>, Int>>): String {
        return result.fold(
            onSuccess = { (items, dirs) ->
                when {
                    items.isNotEmpty() -> "登录成功：可访问 ${items.size} 个音频文件"
                    dirs > 0 -> "登录成功：根目录无音频，发现 $dirs 个子目录（音频在子目录中，进入后可见）"
                    else -> "登录成功：该目录为空（无音频、无子目录）"
                }
            },
            onFailure = { e ->
                when (e) {
                    is com.booklistener.core.AudioSourceException.AuthFailed ->
                        "登录失败：账号或应用密码不正确（HTTP 401/403）"
                    is com.booklistener.core.AudioSourceException.NetworkLimit ->
                        "连接失败：请求过于频繁（HTTP 429），请稍后重试"
                    else -> "连接失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        )
    }

    /** 删除 WebDAV 账号：移除凭证 + 级联清理该账号全部曲目条目（旧根条目 + 固化的文件夹）/索引/进度/封面 */
    fun removeWebDavAccount(baseUrl: String) {
        repo.credentialStore.removeWebDavAccount(baseUrl)
        val prefix = "webdav://" + normalizeWebDavUrl(baseUrl)
        _savedEntries.value.filter { it.id.startsWith(prefix) }
            .forEach { clearSavedEntry(it) }
        refreshWebDavAccounts()
        if (_webDavForm.value.editingBaseUrl == normalizeWebDavUrl(baseUrl)) {
            cancelWebDavForm()
        }
        _eventMsg.value = "WebDAV 账号已删除"
    }

    /**
     * v1.6.28：网盘账号为凭证，不再自动固化为条目；需在文件夹管理中
     * 通过目录树选择具体文件夹固化（addWebDavFolder）。此处仅保留账号。
     */
    fun addWebDavEntry(cred: WebDavCredential) {
        repo.credentialStore.addWebDavAccount(cred)
        refreshWebDavAccounts()
    }

    /** 固化 WebDAV 文件夹条目：folderUrl 为完整目录 URL（账号根或子目录），同一账号可固化多个 */
    fun addWebDavFolder(cred: WebDavCredential, folderUrl: String) {
        val id = webDavFolderEntryId(folderUrl)
        val existing = _savedEntries.value.firstOrNull { it.id == id }
        val name = android.net.Uri.decode(
            folderUrl.trimEnd('/').substringAfterLast('/')
        ).ifEmpty { cred.host }
        val entry = SavedEntry(
            id = id,
            displayName = name,
            uri = folderUrl,
            sourceType = SourceType.WEBDAV,
            coverUri = existing?.coverUri,
            fileCount = existing?.fileCount ?: 0,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            // v1.6.30：作者信息不取自云盘账号（此前会显示成邮箱）；网盘音频作者由 tag 解析落索引
            artist = null,
            lastOpenedAt = existing?.lastOpenedAt ?: 0L
        )
        setEntries(entryStore.upsert(_savedEntries.value, entry))
        // 固化即预扫一次索引（文件夹管理文件列表立即可见；曲目页不聚合网盘）
        prefillWebDavIndex(folderUrl, entry)
    }

    /** WebDAV 文件夹条目 id（v1.6.28）：完整目录 URL 去 scheme 前缀，账号下多个文件夹各自独立 */
    fun webDavFolderEntryId(folderUrl: String): String = "webdav://" + folderUrl

    /** 固化/进入前预扫：PROPFIND 一次写索引（含播放进度补录所需的时长字段），完成后刷新曲目分区 */
    private fun prefillWebDavIndex(folderUrl: String, entry: SavedEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val (_, list) = repo.listWebDavDirEntries(folderUrl).getOrThrow()
                indexStore.save(entry.id, list.map { it.toIndexedFile() })
            }
            withContext(Dispatchers.Main) { refreshAlbums() }
        }
    }

    fun openSavedEntry(entry: SavedEntry, showSheet: Boolean = true) {
        if (entry.isLocal) {
            scanFolder(Uri.parse(entry.uri), showSheet = showSheet)
            return
        }
        if (entry.sourceType == SourceType.WEBDAV) {
            // 目录导航：进入网盘条目时重置到根目录
            _webDavPathStack.value = emptyList()
            _webDavDirs.value = emptyList()
        }
        openEntryView(
            entryId = entry.id,
            entryUri = Uri.parse(entry.id),
            emptyMessage = MessageCatalog.EMPTY_DIR_AUDIO,
            showSheet = showSheet,
            loadItems = {
                when (entry.sourceType) {
                    // 网盘目录会变化：每次进入直接 PROPFIND（子目录实时可见），同时写索引供专辑聚合
                    SourceType.WEBDAV -> {
                        val (dirs, list) = repo.listWebDavDirEntries(entry.uri).getOrThrow()
                        _webDavDirs.value = dirs
                        indexStore.save(entry.id, list.map { it.toIndexedFile() })
                        list
                    }
                    else -> loadFromIndex(entry.id, entry.sourceType) ?: run {
                        val items = repo.listDirectory(entry.sourceType, entry.uri).getOrThrow()
                        indexStore.save(entry.id, items.map { it.toIndexedFile() })
                        items
                    }
                }
            }
        )
    }

    // ---- WebDAV 目录导航（v1.6.27；v1.6.28 基于完整 URL 逐层浏览）----

    /** 进入 WebDAV 子目录：路径栈 push 并加载该目录内容（不自动播放、不固化条目） */
    fun enterWebDavDir(href: String) {
        _webDavPathStack.update { it + href }
        loadWebDavDirContent(href)
    }

    /** 返回 WebDAV 上一级目录；已在根目录时退出条目视图 */
    fun backWebDavDirOrExit() {
        val stack = _webDavPathStack.value
        if (stack.isEmpty()) {
            exitFolderView()
            return
        }
        val newStack = stack.dropLast(1)
        _webDavPathStack.value = newStack
        // 上一级 = 栈顶（栈空 = 条目根目录 URL）
        val parent = newStack.lastOrNull() ?: _currentFolderUri.value?.toString()?.removePrefix("webdav://").orEmpty()
        if (parent.isEmpty()) return
        loadWebDavDirContent(parent)
    }

    private fun loadWebDavDirContent(url: String) {
        viewModelScope.launch {
            val (dirs, items) = withContext(Dispatchers.IO) {
                runCatching { repo.listWebDavDirEntries(url).getOrThrow() }
                    .getOrElse { e ->
                        AppLog.e("load webdav dir failed: $url", e)
                        emptyList<WebDavDirEntry>() to emptyList<AudioItem>()
                    }
            }
            _webDavDirs.value = dirs
            _folderItems.value = items
            _eventMsg.value = if (dirs.isEmpty() && items.isEmpty())
                MessageCatalog.EMPTY_DIR_AUDIO
            else
                MessageCatalog.MSG_FOUND.format(items.size)
        }
    }

    // ---- WebDAV 文件夹选择器（v1.6.28：文件夹管理 → 添加 → 网盘目录树选文件夹固化）----

    private val _pickerUrl = MutableStateFlow<String?>(null)
    val pickerUrl: StateFlow<String?> = _pickerUrl.asStateFlow()
    private val _pickerStack = MutableStateFlow<List<String>>(emptyList())
    val pickerStack: StateFlow<List<String>> = _pickerStack.asStateFlow()
    private val _pickerDirs = MutableStateFlow<List<WebDavDirEntry>>(emptyList())
    val pickerDirs: StateFlow<List<WebDavDirEntry>> = _pickerDirs.asStateFlow()
    private val _pickerItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val pickerItems: StateFlow<List<AudioItem>> = _pickerItems.asStateFlow()
    private val _pickerLoading = MutableStateFlow(false)
    val pickerLoading: StateFlow<Boolean> = _pickerLoading.asStateFlow()

    /** 进入网盘目录选择器：从账号根目录开始浏览（账号已在凭证库） */
    fun openWebDavPicker(cred: WebDavCredential) {
        _pickerStack.value = emptyList()
        loadPickerDir(cred.baseUrl)
    }

    /** 选择器进入子目录 */
    fun enterPickerDir(href: String) {
        _pickerStack.update { it + href }
        loadPickerDir(href)
    }

    /** 选择器返回上一级（根目录时清空回到账号列表，由 UI 处理关闭） */
    fun backPickerDir() {
        val stack = _pickerStack.value
        if (stack.isEmpty()) {
            _pickerUrl.value = null
            _pickerDirs.value = emptyList()
            _pickerItems.value = emptyList()
            return
        }
        val newStack = stack.dropLast(1)
        _pickerStack.value = newStack
        loadPickerDir(newStack.lastOrNull() ?: _pickerUrl.value.orEmpty())
    }

    /** 保存选择器当前目录为固化条目（凭证复用账号库） */
    fun savePickerFolder(cred: WebDavCredential) {
        val url = _pickerUrl.value ?: cred.baseUrl
        addWebDavFolder(cred, url)
        closePicker()
    }

    /** 关闭选择器并清理状态 */
    fun closePicker() {
        _pickerUrl.value = null
        _pickerStack.value = emptyList()
        _pickerDirs.value = emptyList()
        _pickerItems.value = emptyList()
    }

    private fun loadPickerDir(url: String) {
        _pickerLoading.value = true
        _pickerUrl.value = url
        viewModelScope.launch {
            val (dirs, items) = withContext(Dispatchers.IO) {
                runCatching { repo.listWebDavDirEntries(url).getOrThrow() }
                    .getOrElse { e ->
                        AppLog.e("load webdav picker dir failed: $url", e)
                        emptyList<WebDavDirEntry>() to emptyList<AudioItem>()
                    }
            }
            _pickerDirs.value = dirs
            _pickerItems.value = items
            _pickerLoading.value = false
        }
    }

    /**
     * 杀后台重启恢复：定位最近播放条目并回到其条目视图（就绪、暂停、不弹详情）。
     * 扫描在 IO 线程异步执行；未播放过任何条目则不做任何事。
     */
    private fun restoreLastSession() {
        val recent = LastSessionLogic.pickRecent(_savedEntries.value) ?: return
        openSavedEntry(recent, showSheet = false)
    }

    /** 删除已添加条目（含进度记录、内容索引、本地封面缓存） */
    fun clearSavedEntry(entry: SavedEntry) {
        setEntries(entryStore.remove(_savedEntries.value, entry.id))
        indexStore.remove(entry.id)
        progressStore.remove(entry.id)
        if (entry.coverUri?.startsWith("file://") == true) {
            runCatching { File(Uri.parse(entry.coverUri).path!!).delete() }
        }
    }

    /**
     * 添加网盘源条目（v1.6.25：多账号后 WebDAV 走 addWebDavEntry 选账号；本方法仅兼容旧调用，
     * WEBDAV 分支取第一个已配置账号）。
     */
    fun addCloudSource(type: SourceType) {
        val ok = when (type) {
            SourceType.WEBDAV -> repo.credentialStore.webDavAccounts().isNotEmpty()
            SourceType.ALIYUN -> repo.aliyunAuth.isAuthorized()
            else -> false
        }
        if (!ok) {
            _eventMsg.value = "请先在网盘配置中完成" + cloudDisplayName(type) + "配置/授权"
            return
        }
        when (type) {
            SourceType.WEBDAV -> {
                // v1.6.28：网盘不再自动固化根条目，改由文件夹管理目录树选文件夹
                _eventMsg.value = "请在文件夹管理中选择要添加的网盘文件夹"
            }
            SourceType.ALIYUN -> {
                val entry = SavedEntry(
                    id = "aliyun://root",
                    displayName = cloudDisplayName(type),
                    uri = "",
                    sourceType = type
                )
                setEntries(entryStore.upsert(_savedEntries.value, entry))
                _eventMsg.value = "已添加 ${entry.displayName}"
            }
            else -> return
        }
    }

    // ---- 兼容旧 API（DirectoryScreen 旧测试依赖，语义不变）----

    /** 固化本地文件夹（多个，重复保存不生效），最新保存的排在最前 */
    fun saveFolder(uri: Uri) {
        val current = _savedEntries.value
        if (current.any { it.id == uri.toString() }) return
        val entry = SavedEntry(
            id = uri.toString(),
            displayName = folderDisplayName(uri),
            uri = uri.toString(),
            sourceType = SourceType.LOCAL
        )
        setEntries(entryStore.upsert(current, entry))
    }

    /** 删除指定固化文件夹，其余保留；联动删除该文件夹的播放进度记录 */
    fun clearSavedFolder(uri: Uri) {
        val entry = _savedEntries.value.find { it.id == uri.toString() } ?: return
        clearSavedEntry(entry)
    }

    /** 从固化文件夹直接进入（无需重新选择） */
    fun openSavedFolder(uri: Uri) {
        scanFolder(uri)
    }

    // ---- 搜索 ----

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _searchResults.value = searchEntries(query, _savedEntries.value, indexStore.load())
    }

    // ---- 播放进度 ----

    /** 消费弹层请求（由 MainScreen 在弹出后调用复位） */
    fun consumePlayerSheetRequest() {
        _requestPlayerSheet.value = false
    }

    /** 加载条目记录并就绪（不播）；点播放后从记录位置续播 */
    private fun enterFolderWithRecord(folderUri: Uri, items: List<AudioItem>) {
        val record = progressStore.load(folderUri.toString())
        val (startIndex, startPos) = FolderProgressLogic.resolveStart(items.size, record)
        lastSavedIndex = startIndex
        lastSavedUri = folderUri.toString()
        val entry = savedEntryById(folderUri.toString())
        // v1.6.22：修复"无封面专辑串用其他专辑图"（老问题回归）。
        // 文件夹视图进入的封面规则与专辑入口共用 resolveAlbumCover：
        // 单专辑文件夹 → 文件夹封面；多专辑文件夹 → 记录定位曲目的内嵌封面（解析不到返回 null，绝不串图）
        val cover = entry?.let { e ->
            coverManager.resolveAlbumCover(
                e.coverUri, coverManager.albumCountOf(items), items.getOrNull(startIndex)?.path
            )
        }
        MediaPlayService.start(getApplication())
        viewModelScope.launch {
            PlaybackController.prepareFolder(
                resolveAll(items, coverUri = cover), startIndex, startPos, folderUri.toString()
            )
        }
    }

    /** 保存当前条目的播放进度（无当前条目或未播放时不存） */
    fun saveCurrentPlaybackProgress() {
        val uri = _currentFolderUri.value ?: return
        val st = PlaybackController.state.value
        if (st.queueIndex < 0 || st.currentItem == null) return
        progressStore.save(uri.toString(), st.queueIndex, st.positionMs)
        lastSavedIndex = st.queueIndex
        lastSavedUri = uri.toString()
    }

    /** 播放开始时把当前条目标记为"已播放过"（更新 lastPlayedAt，支撑最近播放排序） */
    private fun markCurrentEntryPlayed() {
        val currentId = _currentFolderUri.value?.toString() ?: return
        if (lastMarkedEntryId == currentId) return
        lastMarkedEntryId = currentId
        setEntries(
            entryStore.touchLastPlayed(
                _savedEntries.value, currentId, System.currentTimeMillis()
            )
        )
    }

    /** 进入条目视图时记录"最近打开"（更新 lastOpenedAt，支撑杀后台恢复到浏览过的会话） */
    private fun markOpened(id: String) {
        setEntries(
            entryStore.touchOpened(_savedEntries.value, id, System.currentTimeMillis())
        )
    }

    /** 退出条目视图（退出前保存当前进度） */
    fun exitFolderView() {
        saveCurrentPlaybackProgress()
        _webDavPathStack.value = emptyList()
        _webDavDirs.value = emptyList()
        _inFolderView.value = false
        _folderItems.value = emptyList()
        _currentFolderUri.value = null
        _currentAlbum.value = null
    }

    /** 退出专辑视图（返回曲目主界面并刷新专辑分区） */
    fun exitAlbumView() {
        exitFolderView()
        refreshAlbums()
    }

    // ---- 文件夹管理：文件列表（v1.7.0，db 加载）----

    private val _manageFiles = MutableStateFlow<List<IndexedFile>?>(null)
    val manageFiles: StateFlow<List<IndexedFile>?> = _manageFiles.asStateFlow()

    fun loadManageFiles(entryId: String) {
        viewModelScope.launch {
            _manageFiles.value = withContext(Dispatchers.IO) {
                indexStore.load()[entryId] ?: emptyList()
            }
        }
    }

    fun clearManageFiles() {
        _manageFiles.value = null
    }

    /**
     * 刷新条目数据库（v1.6.3，文件夹管理文件列表"刷新"按钮）：
     * 重读该条目全部文件（本地重扫文件头/网盘重列目录）→ 重建内容索引（标签/时长/原始文件名）
     * → 本地条目重算封面与作者（保留 lastPlayedAt/lastOpenedAt/进度）→ 刷新专辑聚合与当前文件列表。
     * v1.6.28 修复：网盘刷新曾走占位 source（无凭证）导致 AuthFailed 后空列表清空索引。
     */
    fun refreshEntry(entry: SavedEntry) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    if (entry.isLocal) {
                        folderScanner.scan(entry.uri)
                    } else if (entry.sourceType == SourceType.WEBDAV) {
                        repo.listWebDavDirEntries(entry.uri).getOrThrow().second
                    } else {
                        repo.listDirectory(entry.sourceType, entry.uri).getOrThrow()
                    }
                }.getOrElse { e ->
                    AppLog.e("refresh entry failed: ${entry.id}", e)
                    emptyList()
                }
            }
            indexStore.save(entry.id, items.map { it.toIndexedFile() })
            if (entry.isLocal) {
                val newEntry = buildLocalEntry(Uri.parse(entry.uri), items, prev = entry)
                setEntries(entryStore.replace(_savedEntries.value, newEntry))
            }
            refreshAlbums()
            loadManageFiles(entry.id)
            _eventMsg.value = MessageCatalog.MSG_REFRESHED.format(items.size)
        }
    }

    /**
     * 存量归一化：v1.7.0 前 folderDisplayName 会提取出 "BOOK/哈利·波特与魔法石" 这类路径名，
     * 导致无 tag 时专辑名兜底成路径。启动时对 local 条目按新规则重算并逐条写回。
     */
    private fun normalizeFolderDisplayNames() {
        var list = _savedEntries.value
        val toFix = list.filter { entry ->
            entry.isLocal && entry.displayName != folderDisplayName(Uri.parse(entry.uri))
        }
        if (toFix.isEmpty()) return
        toFix.forEach { entry ->
            list = entryStore.replace(list, entry.copy(displayName = folderDisplayName(Uri.parse(entry.uri))))
        }
        setEntries(list)
    }

    /** v1.6.30：存量网盘条目曾把云盘账号邮箱写入 artist，启动清空并写回（显示层不再出现账号） */
    private fun cleanStaleWebDavArtist() {
        var list = _savedEntries.value
        val toFix = list.filter { it.sourceType == SourceType.WEBDAV && it.artist != null }
        if (toFix.isEmpty()) return
        toFix.forEach { entry ->
            list = entryStore.replace(list, entry.copy(artist = null))
        }
        setEntries(list)
    }

    // ---- 封面 ----

    /**
     * 构建本地条目：目录名 + 封面（文件夹图片优先 → 首曲内嵌封面 → 无）+ 文件数 + 作者（首曲元数据）。
     * 在 IO 线程调用；内嵌封面/作者仅本地 SAF 文件可提取。
     * prev 为扫描前的原条目：必须保留其 lastPlayedAt/lastOpenedAt（用户行为数据），
     * 否则清后台重启后的 replace 会把"正在播放"条目清零成"尚未播放"。
     */
    private fun buildLocalEntry(treeUri: Uri, items: List<AudioItem>, prev: SavedEntry? = null): SavedEntry {
        val uriStr = treeUri.toString()
        val cover = coverManager.resolveFolderCover(uriStr, items.firstOrNull()?.path)
        val artist = coverManager.resolveArtist(items.firstOrNull()?.path)
        return SavedEntry(
            id = uriStr,
            displayName = folderDisplayName(treeUri),
            uri = uriStr,
            sourceType = SourceType.LOCAL,
            coverUri = cover,
            fileCount = items.size,
            artist = artist,
            lastPlayedAt = prev?.lastPlayedAt ?: 0L,
            lastOpenedAt = prev?.lastOpenedAt ?: 0L
        )
    }

    // ---- SAF 查询 ----

    /** SAF 树目录的子项查询（ContentResolver，DocumentsContract 规范） */
    private fun queryFolderChildren(treeUriStr: String, parentDocId: String): List<FolderEntry> {
        val treeUri = Uri.parse(treeUriStr)
        val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val result = mutableListOf<FolderEntry>()
        val resolver = getApplication<Application>().contentResolver
        resolver.query(queryUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val mime = c.getString(mimeIdx) ?: ""
                result.add(
                    FolderEntry(
                        displayName = c.getString(nameIdx) ?: "",
                        mimeType = mime,
                        docId = c.getString(idIdx) ?: "",
                        isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mime,
                        size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    )
                )
            }
        }
        return result
    }

    fun togglePlayPause() {
        PlaybackController.togglePlayPause()
        // 暂停瞬间立即保存当前位置（缩小 5s 节流窗口的丢失范围）
        if (!PlaybackController.state.value.isPlaying) saveCurrentPlaybackProgress()
    }
    fun seekTo(ms: Long) = PlaybackController.seekTo(ms)
    fun next() = PlaybackController.next()
    fun previous() = PlaybackController.previous()

    fun clearCache() {
        viewModelScope.launch {
            repo.clearCache()
            _eventMsg.value = "缓存已清除"
        }
    }

    fun cacheCurrentItem() {
        val item = playbackState.value.currentItem ?: return
        viewModelScope.launch {
            val result = repo.cacheItem(item)
            _eventMsg.value = if (result.isSuccess) "已缓存到本地" else "缓存失败：${result.exceptionOrNull()?.message}"
        }
    }

    fun consumeEvent() {
        _eventMsg.update { null }
    }

    private fun cloudDisplayName(type: SourceType): String = when (type) {
        SourceType.WEBDAV -> "WebDAV"
        SourceType.ALIYUN -> "阿里云盘"
        SourceType.LOCAL -> "本地"
    }
}
