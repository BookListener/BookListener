package com.booklistener.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 网盘配置页：WebDAV 表单（地址/账号/应用密码）+ 阿里云盘 PKCE 授权。
 * 阿里云盘授权通过系统浏览器完成，回调由 MainActivity 的 deep link 转发。
 */
@Composable
fun CloudConfigScreen(
    viewModel: CloudConfigViewModel = viewModel(),
    playerViewModel: PlayerViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("WebDAV 配置", style = MaterialTheme.typography.titleLarge)
        Text(
            "适用于 InfiniCLOUD / 中国科技云 / Nextcloud / 自建 NAS，在网盘后台生成应用密码填入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.webDavUrl,
            onValueChange = viewModel::onWebDavUrlChange,
            label = { Text("WebDAV 地址") },
            placeholder = { Text("https://dav.example.com/dav/") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        OutlinedTextField(
            value = state.webDavUser,
            onValueChange = viewModel::onWebDavUserChange,
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        OutlinedTextField(
            value = state.webDavPass,
            onValueChange = viewModel::onWebDavPassChange,
            label = { Text("应用密码") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            onClick = { viewModel.saveWebDav() },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("保存 WebDAV")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        Text("阿里云盘", style = MaterialTheme.typography.titleLarge)
        Text(
            if (state.aliyunAuthorized) "已授权，可直接浏览"
            else "需先在阿里云开放平台注册应用获取 client_id，再扫码授权",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.aliyunClientId,
            onValueChange = viewModel::onAliyunClientIdChange,
            label = { Text("阿里云 client_id") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { viewModel.saveAliyunClientId() }) {
                Text("保存 client_id")
            }
            if (state.aliyunAuthorized) {
                TextButton(onClick = { viewModel.clearAliyunAuth() }) {
                    Text("清除授权")
                }
            }
        }
        if (!state.aliyunAuthorized && state.aliyunClientId.isNotBlank()) {
            Button(
                onClick = {
                    val url = viewModel.buildAliyunAuthorizeUrl()
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("跳转授权")
            }
        }

        state.savedMsg?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp)
            )
            LaunchedEffect(msg) {
                viewModel.consumeSavedMsg()
            }
        }

        Text(
            text = "提示：阿里云盘授权回调地址为 booklistener://oauth/callback，需在开放平台配置；" +
                "百度网盘等传统 OAuth 平台需自行搭建服务端，本版本暂不内置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
