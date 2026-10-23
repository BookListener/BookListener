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
    var showTheme by remember { mutableStateOf(false) }
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

    if (showTheme) {
        // v2.1.19：主题子页（设计统一：返回箭头 + 标题 + 说明 + 分区块配置）。
        // 单页三区块：外观模式（白天/黑夜/自动）/ 玻璃效果 / 语言，区块间 HorizontalDivider。
        BackHandler(onBack = { showTheme = false })
        val glassActionBar by viewModel.glassActionBar.collectAsStateWithLifecycle()
        val glassPlayerPanel by viewModel.glassPlayerPanel.collectAsStateWithLifecycle()
        val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
        val currentTag = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showTheme = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = stringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ---- 区块一：外观模式（白天/黑夜/自动） ----
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.appearance_mode_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = stringResource(R.string.appearance_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                val modeOptions = listOf(
                    AppearanceMode.LIGHT to stringResource(R.string.appearance_light),
                    AppearanceMode.DARK to stringResource(R.string.appearance_dark),
                    AppearanceMode.FOLLOW_SYSTEM to stringResource(R.string.appearance_auto)
                )
                modeOptions.forEach { (mode, label) ->
                    val selected = appearanceMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setAppearanceMode(mode) }
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

            // ---- 区块二：玻璃效果（沿用两开关，逻辑零改动） ----
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_glass_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
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
                        Switch(checked = glassActionBar, onCheckedChange = { viewModel.setGlassActionBar(it) })
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
                        Switch(checked = glassPlayerPanel, onCheckedChange = { viewModel.setGlassPlayerPanel(it) })
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- 区块三：语言（沿用三态，逻辑零改动） ----
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_language_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                val langOptions = listOf(
                    "" to stringResource(R.string.language_follow_system),
                    "zh" to stringResource(R.string.language_zh),
                    "en" to stringResource(R.string.language_en)
                )
                langOptions.forEach { (tag, label) ->
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

        // v2.1.19：主题入口（设计统一：主页仅列举配置项，外观/玻璃/语言收进子页）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showTheme = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Palette,
                contentDescription = stringResource(R.string.settings_theme_row)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_theme_row), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_theme_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.settings_theme_row)
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
