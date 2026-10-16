package com.booklistener.core

/**
 * 界面/事件提示文案集中定义（霰弹收敛 A3）：
 * 所有空态与事件消息单点维护，禁止在 UI/ViewModel 中散落重复字符串。
 */
object MessageCatalog {
    // 目录/条目视图空态（v2.0.12：文案与样式统一为「主句 + 操作引导」两行）
    const val EMPTY_DIR_AUDIO = "目录内未发现可播放的音频文件"
    const val EMPTY_HOME = "尚未添加任何播放内容\n请到设置中添加播放文件夹或网盘内容"
    const val EMPTY_MANAGE = "尚未添加任何播放内容\n点击右上角「+」添加文件夹或网盘"
    const val EMPTY_SEARCH = "未找到匹配内容"

    // 播放详情空态
    const val EMPTY_PLAYER_TITLE = "还没有正在播放的内容"
    const val EMPTY_PLAYER_GUIDE = "请先添加播放文件夹并进入，\n或在已添加内容中点击条目开始播放"

    // 事件消息模板
    const val MSG_FOUND = "找到 %d 个可播放音频"
    const val MSG_ADDED = "已添加 %s"
    const val MSG_REFRESHED = "已刷新 %d 个音频"
    const val MSG_REFRESH_CONFIRM_TITLE = "刷新数据库"
    const val MSG_REFRESH_CONFIRM_TEXT = "将重新读取该目录全部音频（标签/时长/封面），保留播放进度。确定刷新？"

    // v1.6.7：定时关闭
    const val MSG_SLEEP_TIMER_DONE = "定时关闭：已暂停播放"
    const val MSG_GLOBAL_SLEEP_DONE = "全局定时关闭：已暂停播放"
    const val MSG_SLEEP_END_UNKNOWN = "当前曲目时长未知，无法使用「听完本章节」"
}
