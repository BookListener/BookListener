package com.booklistener.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioSourceException
import com.booklistener.data.MediaRepository
import com.booklistener.source.WebDavAudioSource
import com.booklistener.source.WebDavCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudConfigState(
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPass: String = "",
    val webDavConfigured: Boolean = false,
    val webDavTesting: Boolean = false,
    val webDavTestOk: Boolean? = null,
    val webDavTestResult: String? = null,
    val savedMsg: String? = null
)

/**
 * 网盘配置 ViewModel：仅 WebDAV。
 * 保存时凭证加密落盘，并立即对根目录发一次 PROPFIND 验证登录是否成功，
 * 结果（成功/401/网络错误）展示在配置页。
 */
class CloudConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)

    private val _state = MutableStateFlow(CloudConfigState())
    val state: StateFlow<CloudConfigState> = _state.asStateFlow()

    init {
        val cred = repo.credentialStore.webDav()
        _state.update {
            it.copy(
                webDavUrl = cred?.baseUrl ?: "",
                webDavUser = cred?.username ?: "",
                webDavPass = cred?.password ?: "",
                webDavConfigured = cred != null
            )
        }
    }

    fun onWebDavUrlChange(v: String) = _state.update { it.copy(webDavUrl = v) }
    fun onWebDavUserChange(v: String) = _state.update { it.copy(webDavUser = v) }
    fun onWebDavPassChange(v: String) = _state.update { it.copy(webDavPass = v) }

    /** 保存并测试连接：凭证落盘后立刻 PROPFIND 验证登录状态，更新测试结果。 */
    fun saveAndTestWebDav() {
        val s = _state.value
        if (s.webDavUrl.isBlank() || s.webDavUser.isBlank() || s.webDavPass.isBlank()) {
            _state.update { it.copy(savedMsg = "WebDAV 地址、用户名、密码不能为空") }
            return
        }
        val cred = WebDavCredential(s.webDavUrl.trim(), s.webDavUser.trim(), s.webDavPass)
        repo.credentialStore.setWebDav(cred)
        _state.update {
            it.copy(
                webDavConfigured = true,
                webDavTesting = true,
                webDavTestOk = null,
                webDavTestResult = null,
                savedMsg = "WebDAV 配置已保存，正在测试连接…"
            )
        }
        viewModelScope.launch {
            val source = WebDavAudioSource { cred }
            val result = source.listDirectory("")
            _state.update {
                it.copy(
                    webDavTesting = false,
                    savedMsg = null,
                    webDavTestOk = result.isSuccess,
                    webDavTestResult = formatTestResult(result)
                )
            }
        }
    }

    /** 仅验证登录是否成功，不修改表单（用于确认已保存配置仍可用）。 */
    fun testWebDavOnly() {
        val s = _state.value
        if (s.webDavUrl.isBlank() || s.webDavUser.isBlank() || s.webDavPass.isBlank()) {
            _state.update { it.copy(savedMsg = "请先填写 WebDAV 地址、用户名、密码") }
            return
        }
        val cred = WebDavCredential(s.webDavUrl.trim(), s.webDavUser.trim(), s.webDavPass)
        _state.update {
            it.copy(webDavTesting = true, webDavTestOk = null, webDavTestResult = null, savedMsg = null)
        }
        viewModelScope.launch {
            val source = WebDavAudioSource { cred }
            val result = source.listDirectory("")
            _state.update {
                it.copy(
                    webDavTesting = false,
                    webDavTestOk = result.isSuccess,
                    webDavTestResult = formatTestResult(result)
                )
            }
        }
    }

    fun consumeSavedMsg() = _state.update { it.copy(savedMsg = null) }

    private fun formatTestResult(result: Result<List<AudioItem>>): String {
        return result.fold(
            onSuccess = { items -> "登录成功：可访问 ${items.size} 个音频文件" },
            onFailure = { e ->
                when (e) {
                    is AudioSourceException.AuthFailed ->
                        "登录失败：账号或应用密码不正确（HTTP 401/403）"
                    is AudioSourceException.NetworkLimit ->
                        "连接失败：请求过于频繁（HTTP 429），请稍后重试"
                    else ->
                        "连接失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        )
    }
}
