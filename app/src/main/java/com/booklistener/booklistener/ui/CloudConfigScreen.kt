package com.booklistener.ui

import androidx.compose.ui.res.stringResource
import com.booklistener.R
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
import androidx.compose.ui.text.style.TextAlign
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
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.cloud_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    viewModel.startNewWebDavForm()
                    addingNew = true
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.cloud_add),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (accounts.isEmpty()) {
                // v2.0.12：空态提示统一设计规格——bodyLarge + onSurfaceVariant + 顶部48dp + 水平居中
                Text(
                    text = stringResource(R.string.cloud_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp)
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
                                    contentDescription = stringResource(R.string.nav_delete) + " ${cred.host}",
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
        // 新建时 host 初值为空（cloud_add 仅作"添加"入口语义，不得作为输入值）
        val editingHost = selected?.host ?: ""
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
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = editingHost,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.cloud_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = form.url,
                    onValueChange = viewModel::webDavFormUrlChange,
                    label = { Text(stringResource(R.string.cloud_url)) },
                    placeholder = { Text("https://dav.example.com/dav/") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = form.user,
                    onValueChange = viewModel::webDavFormUserChange,
                    label = { Text(stringResource(R.string.cloud_user)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = form.pass,
                    onValueChange = viewModel::webDavFormPassChange,
                    label = { Text(stringResource(R.string.cloud_password)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = { viewModel.saveAndTestWebDav() },
                        enabled = !form.testing
                    ) {
                        Text(if (form.testing) stringResource(R.string.cloud_testing) else stringResource(R.string.cloud_save_test))
                    }
                    if (form.editing) {
                        TextButton(
                            onClick = { viewModel.testWebDavOnly() },
                            enabled = !form.testing
                        ) {
                            Text(stringResource(R.string.cloud_retest))
                        }
                        TextButton(
                            onClick = { pendingDelete = form.editingBaseUrl },
                            enabled = !form.testing
                        ) {
                            Text(stringResource(R.string.cloud_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (form.testing) {
                    Text(
                        text = stringResource(R.string.cloud_connecting),
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
            title = { Text(stringResource(R.string.cloud_delete_title)) },
            text = { Text(stringResource(R.string.cloud_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeWebDavAccount(baseUrl)
                    pendingDelete = null
                    selected = null
                    addingNew = false
                }) {
                    Text(stringResource(R.string.nav_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.nav_cancel))
                }
            }
        )
    }
}
