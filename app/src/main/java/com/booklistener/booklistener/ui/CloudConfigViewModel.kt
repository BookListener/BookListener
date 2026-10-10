package com.booklistener.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.booklistener.data.MediaRepository
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
    val aliyunClientId: String = "",
    val aliyunAuthorized: Boolean = false,
    val savedMsg: String? = null
)

/**
 * 网盘配置 ViewModel：WebDAV 凭证保存、阿里云 client_id 设置与授权跳转。
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
                aliyunClientId = repo.credentialStore.aliyunClientId(),
                aliyunAuthorized = repo.aliyunAuth.isAuthorized()
            )
        }
    }

    fun onWebDavUrlChange(v: String) = _state.update { it.copy(webDavUrl = v) }
    fun onWebDavUserChange(v: String) = _state.update { it.copy(webDavUser = v) }
    fun onWebDavPassChange(v: String) = _state.update { it.copy(webDavPass = v) }
    fun onAliyunClientIdChange(v: String) = _state.update { it.copy(aliyunClientId = v) }

    fun saveWebDav() {
        val s = _state.value
        if (s.webDavUrl.isBlank() || s.webDavUser.isBlank() || s.webDavPass.isBlank()) {
            _state.update { it.copy(savedMsg = "WebDAV 地址、用户名、密码不能为空") }
            return
        }
        viewModelScope.launch {
            repo.credentialStore.setWebDav(
                WebDavCredential(s.webDavUrl.trim(), s.webDavUser.trim(), s.webDavPass)
            )
            _state.update { it.copy(savedMsg = "WebDAV 配置已保存") }
        }
    }

    fun saveAliyunClientId() {
        val id = _state.value.aliyunClientId.trim()
        if (id.isBlank() || id == "YOUR_ALIYUN_CLIENT_ID") {
            _state.update { it.copy(savedMsg = "请先到阿里云开放平台注册应用获取 client_id") }
            return
        }
        repo.credentialStore.setAliyunClientId(id)
        _state.update { it.copy(savedMsg = "client_id 已保存") }
    }

    fun clearAliyunAuth() {
        repo.credentialStore.clearAliyun()
        _state.update { it.copy(aliyunAuthorized = false, savedMsg = "已清除阿里云授权") }
    }

    fun buildAliyunAuthorizeUrl(): String = repo.aliyunAuth.buildAuthorizeUrl()

    fun consumeSavedMsg() = _state.update { it.copy(savedMsg = null) }
}
