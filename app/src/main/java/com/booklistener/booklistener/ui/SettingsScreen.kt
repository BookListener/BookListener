package com.booklistener.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.RadioButton
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.booklistener.R
import com.booklistener.ui.icons.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 设置页（v1.5.1）：顶层"文件夹管理"入口（点开列举已添加内容并提供删除、右上角"+"添加），
 * 下接网盘配置 / 缓存管理 / 关于信息。
 */
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel
) {
    var showCloud by remember { mutableStateOf(false) }
    var showFolderManage by remember { mutableStateOf(false) }
    var showGlobalSleep by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }
    var showGlass by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (showGlobalSleep) {
        // 系统返回键：全局定时关闭 → 设置主页
        BackHandler(onBack = { showGlobalSleep = false })
        val globalMinutes by viewModel.globalSleepMinutes.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showGlobalSleep = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_timer_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = stringResource(R.string.settings_timer_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                listOf(0, 15, 30, 45, 60).forEach { minutes ->
                    val selected = globalMinutes == minutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setGlobalSleepMinutes(minutes)
                                showGlobalSleep = false
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (minutes) {
                                0 -> stringResource(R.string.common_off)
                                else -> stringResource(R.string.common_minutes, minutes)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text(
                                text = stringResource(R.string.settings_timer_current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        return
    }

    if (showCloud) {
        // 系统返回键：网盘配置页 → 设置主页
        BackHandler(onBack = { showCloud = false })
        CloudConfigScreen(viewModel = viewModel, onBack = { showCloud = false })
        return
    }

    if (showFolderManage) {
        // 系统返回键：文件夹管理页 → 设置主页
        BackHandler(onBack = { showFolderManage = false })
        FolderManageScreen(viewModel = viewModel, onBack = { showFolderManage = false })
        return
    }

    if (showCache) {
        // 系统返回键：缓存管理子页 → 设置主页
        BackHandler(onBack = { showCache = false })
        val fullFileCache by viewModel.fullFileCacheEnabled.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showCache = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_cache_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_cache_full),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_cache_full_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fullFileCache,
                        onCheckedChange = { viewModel.setFullFileCacheEnabled(it) }
                    )
                }
                Text(
                    text = stringResource(R.string.settings_cache_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.clearCache()
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.settings_cache_clear_all))
                }
            }
        }
        return
    }

    if (showAbout) {
        // 系统返回键：关于子页 → 设置主页
        BackHandler(onBack = { showAbout = false })
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showAbout = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_about_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = "BookListener v${currentVersionName()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        return
    }

    if (showGlass) {
        // v2.0.11：毛玻璃特效子页（设计统一：开关收进内部菜单，主页仅列举配置项，结构与缓存子页同构）
        // 系统返回键：毛玻璃特效 → 设置主页
        BackHandler(onBack = { showGlass = false })
        val glassActionBar by viewModel.glassActionBar.collectAsStateWithLifecycle()
        val glassPlayerPanel by viewModel.glassPlayerPanel.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showGlass = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_glass_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = stringResource(R.string.settings_glass_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_glass_action_bar), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.settings_glass_action_bar_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = glassActionBar,
                        onCheckedChange = { viewModel.setGlassActionBar(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_glass_player_panel), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.settings_glass_player_panel_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = glassPlayerPanel,
                        onCheckedChange = { viewModel.setGlassPlayerPanel(it) }
                    )
                }
            }
        }
        return
    }

    if (showLanguage) {
        // v2.0.14：语言子页（设计统一：返回箭头 + 标题 + 说明 + 单选行，同全局定时子页结构；
        // 语言属列举项，开关类配置（毛玻璃组件）仍收在各自子页内，主页不放开关）
        BackHandler(onBack = { showLanguage = false })
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showLanguage = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            // 当前语言 tag："" 跟随系统 / "zh" 简体中文 / "en" English（AppCompatDelegate 自动持久化）
            val currentTag = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
            val options = listOf(
                "" to stringResource(R.string.language_follow_system),
                "zh" to stringResource(R.string.language_zh),
                "en" to stringResource(R.string.language_en)
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                options.forEach { (tag, label) ->
                    val selected = currentTag == tag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppCompatDelegate.setApplicationLocales(
                                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                    else LocaleListCompat.forLanguageTags(tag)
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(selected = selected, onClick = null)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 文件夹管理（顶层入口：点开列举 + 删除，右上角"+"添加）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showFolderManage = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Folder,
                contentDescription = stringResource(R.string.settings_folders_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_folders_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_folders_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_folders_row)
            )
        }
        HorizontalDivider()

        // 网盘配置（仅 WebDAV）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCloud = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Cloud,
                contentDescription = stringResource(R.string.settings_cloud_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_cloud_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_cloud_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_cloud_row)
            )
        }
        HorizontalDivider()

        // v1.6.7：定时关闭（全局）——每次开始播放倒计时，到点暂停；一次性定时优先
        val globalMinutes by viewModel.globalSleepMinutes.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showGlobalSleep = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Timer,
                contentDescription = stringResource(R.string.settings_timer_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_timer_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (globalMinutes <= 0) {
                        stringResource(R.string.settings_timer_row_off)
                    } else {
                        stringResource(R.string.settings_timer_row_on, globalMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_timer_row)
            )
        }
        HorizontalDivider()

        // 缓存管理（图标+文字行，点开子页）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCache = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Storage,
                contentDescription = stringResource(R.string.settings_cache_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_cache_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_cache_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_cache_row)
            )
        }
        HorizontalDivider()

        // v2.0.14：语言入口（设计统一：主页仅列举配置项，同缓存/毛玻璃模式）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLanguage = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Language,
                contentDescription = stringResource(R.string.settings_language_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_language_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_language_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_language_row)
            )
        }
        HorizontalDivider()

        // v2.0.11：毛玻璃特效入口（设计统一：主页仅列举配置项，开关收进子页，同缓存模式）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showGlass = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Palette,
                contentDescription = stringResource(R.string.settings_glass_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_glass_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_glass_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_glass_row)
            )
        }
        HorizontalDivider()

        // 关于（图标+文字行，点开子页）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAbout = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = stringResource(R.string.settings_about_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_about_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_about_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_about_row)
            )
        }
    }
}

/** 读取当前安装版本名（用于确认是否为最新构建，避免新旧包混淆） */
@Composable
private fun currentVersionName(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
