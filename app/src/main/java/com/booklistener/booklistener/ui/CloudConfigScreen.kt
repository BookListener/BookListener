package com.booklistener.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.booklistener.source.WebDavCredential

/**
 * 网盘配置页（v1.6.25 多账号管理，交互与文件夹管理一致）：
 * - 列表视图：返回箭头 + 标题 + 右上角"+"添加；每行显示主机名/用户名，行尾删除（二次确认）
 * - 编辑视图：点击行进入编辑（或"+"新建），表单含地址/用户名/密码，
 *   "保存并测试连接"落盘并立刻 PROPFIND 验证登录，成功/失败原因直接展示
 */
@Composable
fun CloudConfigScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val accounts by viewModel.webDavAccounts.collectAsStateWithLifecycle()
    val form by viewModel.webDavForm.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<WebDavCredential?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val inEditor = selected != null || addingNew

    // 系统返回键：编辑视图 → 列表；列表 → 设置页
    BackHandler(onBack = {
        if (inEditor) {
            viewModel.cancelWebDavForm()
            selected = null
            addingNew = false
        } else {
            onBack()
        }
    })

    if (!inEditor) {
        // ---- 列表视图 ----
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onBack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "WebDAV 账号",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    viewModel.startNewWebDavForm()
                    addingNew = true
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加 WebDAV 账号",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (accounts.isEmpty()) {
                Text(
                    text = "尚未配置 WebDAV 账号\n点击右上角 + 添加",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 48.dp)
                )
            } else {
                LazyColumn {
                    items(accounts, key = { it.normalizedUrl }) { cred ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.startEditWebDavForm(cred)
                                    selected = cred
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cred.host,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${cred.username} · ${cred.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { pendingDelete = cred.normalizedUrl }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "删除 ${cred.host}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    } else {
        // ---- 编辑/新建视图 ----
        val editingHost = selected?.host ?: "添加 WebDAV 账号"
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    viewModel.cancelWebDavForm()
                    selected = null
                    addingNew = false
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = editingHost,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "适用于 InfiniCLOUD / 中国科技云 / Nextcloud / 自建 NAS，在网盘后台生成应用密码填入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = form.url,
                    onValueChange = viewModel::webDavFormUrlChange,
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text("https://dav.example.com/dav/") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = form.user,
                    onValueChange = viewModel::webDavFormUserChange,
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = form.pass,
                    onValueChange = viewModel::webDavFormPassChange,
                    label = { Text("应用密码") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = { viewModel.saveAndTestWebDav() },
                        enabled = !form.testing
                    ) {
                        Text(if (form.testing) "测试中…" else "保存并测试连接")
                    }
                    if (form.editing) {
                        TextButton(
                            onClick = { viewModel.testWebDavOnly() },
                            enabled = !form.testing
                        ) {
                            Text("重新测试")
                        }
                        TextButton(
                            onClick = { pendingDelete = form.editingBaseUrl },
                            enabled = !form.testing
                        ) {
                            Text("删除账号", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (form.testing) {
                    Text(
                        text = "正在连接服务器验证登录…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                form.testResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (form.testOk) {
                            true -> Color(0xFF2E7D32)
                            false -> Color(0xFFC62828)
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                form.savedMsg?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    LaunchedEffect(msg) {
                        viewModel.consumeWebDavSavedMsg()
                    }
                }
            }
        }
    }

    // 删除账号二次确认
    pendingDelete?.let { baseUrl ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 WebDAV 账号") },
            text = { Text("将同时移除该账号对应的曲目条目与播放记录，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeWebDavAccount(baseUrl)
                    pendingDelete = null
                    selected = null
                    addingNew = false
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}
