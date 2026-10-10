package com.booklistener.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.booklistener.source.WebDavCredential

/**
 * 敏感凭证加密存储：
 * - 阿里云盘 refresh_token / access_token
 * - WebDAV 地址/用户名/密码
 * - 阿里云 client_id（用户可自行替换）
 * 统一走 EncryptedSharedPreferences，避免明文落盘。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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

    // ---- WebDAV ----
    fun setWebDav(cred: WebDavCredential) {
        prefs.edit()
            .putString(KEY_WEBDAV_URL, cred.baseUrl)
            .putString(KEY_WEBDAV_USER, cred.username)
            .putString(KEY_WEBDAV_PASS, cred.password)
            .apply()
    }

    fun webDav(): WebDavCredential? {
        val url = prefs.getString(KEY_WEBDAV_URL, null) ?: return null
        val user = prefs.getString(KEY_WEBDAV_USER, null) ?: return null
        val pass = prefs.getString(KEY_WEBDAV_PASS, null) ?: return null
        return WebDavCredential(url, user, pass)
    }

    fun clearWebDav() {
        prefs.edit()
            .remove(KEY_WEBDAV_URL)
            .remove(KEY_WEBDAV_USER)
            .remove(KEY_WEBDAV_PASS)
            .apply()
    }

    private companion object {
        const val KEY_ALIYUN_ACCESS = "aliyun_access"
        const val KEY_ALIYUN_REFRESH = "aliyun_refresh"
        const val KEY_ALIYUN_EXPIRES = "aliyun_expires"
        const val KEY_ALIYUN_DRIVE_ID = "aliyun_drive_id"
        const val KEY_ALIYUN_CLIENT_ID = "aliyun_client_id"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USER = "webdav_user"
        const val KEY_WEBDAV_PASS = "webdav_pass"
    }
}
