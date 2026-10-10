package com.booklistener.source

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.booklistener.core.AudioSortKey
import com.booklistener.core.AudioMetaDefaults
import com.booklistener.core.AudioItemFactory
import com.booklistener.core.AudioExt
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioMeta
import com.booklistener.core.AudioSource
import com.booklistener.core.AudioSourceException
import com.booklistener.core.SourceType

/**
 * 本地音频数据源：通过 MediaStore 扫描设备内音频文件。
 * listDirectory(path) 的 path 参数仅用于区分视图，实际读取走系统媒体库。
 */
class LocalAudioSource(private val context: Context) : AudioSource {

    override suspend fun listDirectory(path: String): Result<List<AudioItem>> = runCatching {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val collection = if (path.isEmpty()) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            Uri.parse(path)
        }
        val items = mutableListOf<Pair<AudioItem, AudioSortKey>>()
        context.contentResolver.query(
            collection, projection, null, null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                if (!AudioExt.isAudio(name)) continue
                val size = cursor.getLong(sizeCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                // v1.6.0：优先标签标题，回退去扩展名文件名；音轨号参与排序（disc 曲库无）
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.').takeUnless { it.isEmpty() } ?: name
                val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() }
                val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() }
                val track = cursor.getInt(trackCol).takeIf { it > 0 }
                val durationMs = cursor.getLong(durationCol).takeIf { it > 0L }
                items.add(
                    AudioItemFactory.fromMediaStore(
                        id = id,
                        displayName = title,
                        uri = uri,
                        size = size,
                        album = album,
                        artist = artist,
                        track = track,
                        durationMs = durationMs,
                        rawName = name
                    ) to AudioSortKey(
                        displayName = title,
                        disc = null,
                        track = track
                    )
                )
            }
        }
        // 出口统一排序：track（有则按音轨号）→ 名称自然序，稳定排序保留原序
        items.sortedWith(compareBy(AudioSortKey.byName) { it.second }).map { it.first }
    }

    override suspend fun getPlayUri(item: AudioItem): Result<String> = runCatching {
        if (item.sourceType != SourceType.LOCAL) {
            throw AudioSourceException.NotFound("item not local")
        }
        item.path
    }

    override suspend fun refreshCredential(): Result<Unit> = Result.success(Unit)

    override suspend fun getMeta(item: AudioItem): Result<AudioMeta> = runCatching {
        val uri = Uri.parse(item.path)
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val title = cursor.getString(0) ?: item.displayName
                val artist = cursor.getString(1) ?: AudioMetaDefaults.UNKNOWN_ARTIST
                val duration = cursor.getLong(2)
                return@runCatching AudioMeta(title, artist, duration)
            }
        }
        AudioMeta(item.displayName, AudioMetaDefaults.UNKNOWN_ARTIST, 0L)
    }
}
