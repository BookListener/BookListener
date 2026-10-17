package com.booklistener.core

import com.booklistener.R

/**
 * 界面/事件提示文案集中定义（霰弹收敛 A3）：
 * 所有空态与事件消息单点维护，禁止在 UI/ViewModel 中散落重复字符串。
 * v2.0.14：常量改为 Android 资源 id（values 英文 / values-zh 中文双语言）；
 * 读取处 Compose 用 stringResource(id)，非 Composable 用 context.getString(id, args)。
 */
object MessageCatalog {
    // 目录/条目视图空态（v2.0.12：文案与样式统一为「主句 + 操作引导」两行）
    val EMPTY_DIR_AUDIO = R.string.empty_dir_audio
    val EMPTY_HOME = R.string.empty_home
    val EMPTY_MANAGE = R.string.empty_manage
    val EMPTY_SEARCH = R.string.empty_search

    // 播放详情空态
    val EMPTY_PLAYER_TITLE = R.string.empty_player_title
    val EMPTY_PLAYER_GUIDE = R.string.empty_player_guide

    // 事件消息模板（getString(id, args) 传入参数）
    val MSG_FOUND = R.string.msg_found
    val MSG_ADDED = R.string.msg_added
    val MSG_REFRESHED = R.string.msg_refreshed
    val MSG_REFRESH_CONFIRM_TITLE = R.string.msg_refresh_confirm_title
    val MSG_REFRESH_CONFIRM_TEXT = R.string.msg_refresh_confirm_text

    // v1.6.7：定时关闭
    val MSG_SLEEP_TIMER_DONE = R.string.msg_sleep_timer_done
    val MSG_GLOBAL_SLEEP_DONE = R.string.msg_global_sleep_done
    val MSG_SLEEP_END_UNKNOWN = R.string.msg_sleep_end_unknown
}
