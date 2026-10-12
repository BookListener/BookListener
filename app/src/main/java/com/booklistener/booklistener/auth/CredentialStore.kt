package com.booklistener.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.booklistener.source.WebDavCredential
import com.booklistener.source.normalizeWebDavUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 敏感凭证加密存储：
 * - 阿里云盘 refresh_token / access_token
 * - WebDAV 地址/用户名/密码
 * - 阿里云 client_id（用户可自行替换）
 * 统一走 EncryptedSharedPreferences，避免明文落盘。
 */
class CredentialStore(
    context: Context,
    /** 测试注入：false 时使用普通 SharedPreferences（JVM 单元测试无 AndroidKeyStore） */
    private val secure: Boolean = true
) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        if (!secure) {
            appContext.getSharedPreferences("secure_credentials_plain", Context.MODE_PRIVATE)
        } else {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "secure_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // ---- 阿里云盘 ----
    fun setAliyunTokens(accessToken: String?, refreshToken: String, expiresAtMs: Long) {
        prefs.edit()
            .putString(KEY_ALIYUN_ACCESS, accessToken)
            .putString(KEY_ALIYUN_REFRESH, refreshToken)
            .putLong(KEY_ALIYUN_EXPIRES, expiresAtMs)
            .apply()
    }

    fun aliyunAccessToken(): String? = prefs.getString(KEY_ALIYUN_ACCESS, null)
    fun aliyunRefreshToken(): String? = prefs.getString(KEY_ALIYUN_REFRESH, null)
    fun aliyunExpiresAt(): Long = prefs.getLong(KEY_ALIYUN_EXPIRES, 0L)

    fun setAliyunClientId(clientId: String) {
        prefs.edit().putString(KEY_ALIYUN_CLIENT_ID, clientId).apply()
    }

    fun aliyunClientId(): String = prefs.getString(KEY_ALIYUN_CLIENT_ID, null)
        ?: "YOUR_ALIYUN_CLIENT_ID"

    fun clearAliyun() {
        prefs.edit()
            .remove(KEY_ALIYUN_ACCESS)
            .remove(KEY_ALIYUN_REFRESH)
            .remove(KEY_ALIYUN_EXPIRES)
            .remove(KEY_ALIYUN_DRIVE_ID)
            .apply()
    }

    fun setDriveId(driveId: String) {
        prefs.edit().putString(KEY_ALIYUN_DRIVE_ID, driveId).apply()
    }

    fun driveId(): String = prefs.getString(KEY_ALIYUN_DRIVE_ID, "root") ?: "root"

    // ---- WebDAV 多账号（v1.6.25：JSON 列表，单账号旧键自动迁移） ----

    fun webDavAccounts(): List<WebDavCredential> {
        val raw = prefs.getString(KEY_WEBDAV_ACCOUNTS, null)
        if (raw.isNullOrEmpty()) {
            val legacy = webDavLegacy()
            if (legacy != null) {
                val list = listOf(legacy)
                writeWebDavAccounts(list)
                prefs.edit()
                    .remove(KEY_WEBDAV_URL)
                    .remove(KEY_WEBDAV_USER)
                    .remove(KEY_WEBDAV_PASS)
                    .apply()
                return list
            }
            return emptyList()
        }
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        WebDavCredential(
                            baseUrl = o.getString("url"),
                            username = o.getString("user"),
                            password = o.getString("pass")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 添加/更新账号（同归一化地址覆盖密码与用户名），返回保存后的账号列表 */
    fun addWebDavAccount(cred: WebDavCredential): List<WebDavCredential> {
        val norm = normalizeWebDavUrl(cred.baseUrl)
        val list = webDavAccounts().toMutableList()
        val idx = list.indexOfFirst { normalizeWebDavUrl(it.baseUrl) == norm }
        if (idx >= 0) list[idx] = cred else list.add(cred)
        writeWebDavAccounts(list)
        return list
    }

    /** 按归一化地址删除账号 */
    fun removeWebDavAccount(baseUrl: String): List<WebDavCredential> {
        val norm = normalizeWebDavUrl(baseUrl)
        val list = webDavAccounts().filterNot { normalizeWebDavUrl(it.baseUrl) == norm }
        writeWebDavAccounts(list)
        return list
    }

    private fun writeWebDavAccounts(list: List<WebDavCredential>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("url", it.baseUrl)
                    .put("user", it.username)
                    .put("pass", it.password)
            )
        }
        prefs.edit().putString(KEY_WEBDAV_ACCOUNTS, arr.toString()).apply()
    }

    // ---- WebDAV 兼容旧 API（单账号；多账号后返回第一个，写入按归一化地址覆盖） ----

    @Deprecated("多账号请使用 webDavAccounts()/addWebDavAccount()")
    fun setWebDav(cred: WebDavCredential) {
        addWebDavAccount(cred)
    }

    @Deprecated("多账号请使用 webDavAccounts()")
    fun webDav(): WebDavCredential? = webDavAccounts().firstOrNull()

    @Deprecated("多账号请使用 removeWebDavAccount()")
    fun clearWebDav() {
        prefs.edit()
            .remove(KEY_WEBDAV_ACCOUNTS)
            .remove(KEY_WEBDAV_URL)
            .remove(KEY_WEBDAV_USER)
            .remove(KEY_WEBDAV_PASS)
            .apply()
    }

    private fun webDavLegacy(): WebDavCredential? {
        val url = prefs.getString(KEY_WEBDAV_URL, null) ?: return null
        val user = prefs.getString(KEY_WEBDAV_USER, null) ?: return null
        val pass = prefs.getString(KEY_WEBDAV_PASS, null) ?: return null
        return WebDavCredential(url, user, pass)
    }

    private companion object {
        const val KEY_ALIYUN_ACCESS = "aliyun_access"
        const val KEY_ALIYUN_REFRESH = "aliyun_refresh"
        const val KEY_ALIYUN_EXPIRES = "aliyun_expires"
        const val KEY_ALIYUN_DRIVE_ID = "aliyun_drive_id"
        const val KEY_ALIYUN_CLIENT_ID = "aliyun_client_id"
        const val KEY_WEBDAV_ACCOUNTS = "webdav_accounts"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USER = "webdav_user"
        const val KEY_WEBDAV_PASS = "webdav_pass"
    }
}
