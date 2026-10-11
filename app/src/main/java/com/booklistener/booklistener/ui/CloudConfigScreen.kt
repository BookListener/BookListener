package com.booklistener.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 网盘配置页：仅 WebDAV。
 * 填写地址/用户名/应用密码后点击"保存并测试连接"，凭证落盘并立即验证登录，
 * 界面直接展示登录是否成功及失败原因；已保存配置可单独"重新测试"。
 */
@Composable
fun CloudConfigScreen(
    viewModel: CloudConfigViewModel = viewModel(),
    playerViewModel: PlayerViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(
                onClick = { viewModel.saveAndTestWebDav() },
                enabled = !state.webDavTesting
            ) {
                Text(if (state.webDavTesting) "测试中…" else "保存并测试连接")
            }
            if (state.webDavConfigured) {
                TextButton(
                    onClick = { viewModel.testWebDavOnly() },
                    enabled = !state.webDavTesting
                ) {
                    Text("重新测试")
                }
            }
        }

        if (state.webDavTesting) {
            Text(
                text = "正在连接服务器验证登录…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        state.webDavTestResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.webDavTestOk) {
                    true -> Color(0xFF2E7D32)
                    false -> Color(0xFFC62828)
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 10.dp)
            )
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
    }
}
