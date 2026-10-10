package com.booklistener.source

import com.booklistener.auth.AliyunAuthManager
import com.booklistener.core.NaturalOrder
import com.booklistener.core.AudioMetaDefaults
import com.booklistener.core.AudioItemFactory
import com.booklistener.core.AliyunConfig
import com.booklistener.core.AudioExt
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioMeta
import com.booklistener.core.AudioSource
import com.booklistener.core.AudioSourceException
import com.booklistener.core.SourceType
import com.booklistener.net.OkHttpHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阿里云盘开放 API 数据源（PKCE 授权）。
 * 文件列表 / 下载直链都走官方 openFile 接口，返回可 Range 的临时链接。
 */
class AliyunAudioSource(private val auth: AliyunAuthManager) : AudioSource {

    private val jsonMedia = "application/json".toMediaTypeOrNull()

    override suspend fun listDirectory(path: String): Result<List<AudioItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth.getAccessToken().getOrThrow()
            // path 为 parent_file_id；空串表示根目录 "root"
            val parentId = path.ifEmpty { "root" }
            val payload = JSONObject()
                .put("drive_id", auth.driveId())
                .put("parent_file_id", parentId)
                .put("limit", 100)
                .put("order_by", "name")
                .put("order_direction", "ASC")
                .put("fields", "*")
            val request = Request.Builder()
                .url(AliyunConfig.FILE_LIST_URL)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            OkHttpHolder.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapError(resp.code, text)
                val json = JSONObject(text)
                val array: JSONArray = json.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<AudioItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name")
                    if (!AudioExt.isAudio(name)) continue
                    val display = name.substringBeforeLast('.').takeUnless { it.isEmpty() } ?: name
                    items.add(
                        AudioItemFactory.fromAliyun(
                            fileId = obj.optString("file_id"),
                            displayName = display,
                            size = obj.optLong("size", 0L),
                            rawName = name
                        )
                    )
                }
                items.sortedWith(compareBy(NaturalOrder.bySortKey) { it.displayName })
            }
        }
    }

    override suspend fun getPlayUri(item: AudioItem): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (item.sourceType != SourceType.ALIYUN) {
                throw AudioSourceException.NotFound("item not aliyun")
            }
            val token = auth.getAccessToken().getOrThrow()
            val payload = JSONObject()
                .put("drive_id", auth.driveId())
                .put("file_id", item.path)
                .put("expire_sec", 900)
            val request = Request.Builder()
                .url(AliyunConfig.DOWNLOAD_URL)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            OkHttpHolder.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapError(resp.code, text)
                val json = JSONObject(text)
                val url = json.optString("url")
                if (url.isEmpty()) throw AudioSourceException.NetworkError("empty download url", null)
                url
            }
        }
    }

    override suspend fun refreshCredential(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            auth.getAccessToken().getOrThrow()
            Unit
        }
    }

    override suspend fun getMeta(item: AudioItem): Result<AudioMeta> = runCatching {
        AudioMeta(item.displayName, AudioMetaDefaults.UNKNOWN_ARTIST, 0L)
    }

    private fun mapError(code: Int, body: String): AudioSourceException {
        return when (code) {
            401, 403 -> AudioSourceException.AuthFailed("aliyun auth failed ($code)")
            429 -> AudioSourceException.NetworkLimit("aliyun rate limited (429)")
            404 -> AudioSourceException.NotFound("aliyun file not found")
            else -> AudioSourceException.NetworkError("aliyun error $code: $body", null)
        }
    }
}
