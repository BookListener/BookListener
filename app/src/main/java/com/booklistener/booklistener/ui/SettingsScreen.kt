package com.booklistener.ui
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
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "定时关闭（全局）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = "每次开始播放后开始倒计时，到点自动暂停；一次性定时生效期间不重复触发。",
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
                                0 -> "关闭"
                                else -> "$minutes 分钟"
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
                                text = "当前",
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
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "缓存",
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
                            text = "完整文件缓存",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "开启后，网络音频会先整体下载到本地再播放，\n" +
                                "断网也能继续，适合 ≤200MB 的文件；\n" +
                                "关闭则直接在线播放，不占用本地空间",
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
                    text = "缓存上限 5GB，超出后自动按最近使用淘汰",
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
                    Text("清除全部缓存")
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
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "关于",
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
                text = "数据源：本地 / WebDAV\n" +
                    "音频格式：mp3 / m4a / flac / wav / ogg / aac",
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
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "毛玻璃特效",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = "主页底部动作条与播放详情面板的液态玻璃效果（模糊+折射）。关闭后对应区域使用纯色背景，更省电。",
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
                        Text("主页动作条玻璃", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "主页底部动作条的液态玻璃效果（模糊+折射）",
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
                        Text("播放详情面板玻璃", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "播放详情面板的液态玻璃效果（模糊+折射）",
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
                contentDescription = "文件夹管理"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("文件夹管理", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "管理已添加的播放文件夹与网盘内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "进入文件夹管理"
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
                contentDescription = "网盘配置"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("网盘配置", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "WebDAV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "进入网盘配置"
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
                contentDescription = "定时关闭"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("定时关闭（全局）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (globalMinutes <= 0) {
                        "关闭（每次开始播放后倒计时，到点暂停）"
                    } else {
                        "每次开始播放后 $globalMinutes 分钟暂停"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "设置定时关闭"
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
                contentDescription = "缓存管理"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("缓存管理", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "网络音频本地缓存设置与缓存清理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "进入缓存管理"
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
                contentDescription = "毛玻璃特效"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("毛玻璃特效", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "主页动作条与播放详情面板的液态玻璃效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "进入毛玻璃特效设置"
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
                contentDescription = "关于"
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("关于", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "版本、数据源与音频格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "查看关于"
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
