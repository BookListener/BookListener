package com.booklistener.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 项目自绘图标集。
 *
 * 背景：material-icons-extended 依赖包含数千个图标类，R8 release 在 4G cgroup 环境下
 * 处理该输入会 OOM / 极慢。本项目实际只用其中 10 个图标，故将它们的 Material 官方
 * 24x24 path 数据内联为 ImageVector（Apache 2.0，与官方同源），
 * 依赖切换为 material-icons-core（仅含基础图标）。
 *
 * Icon() 默认 tint 覆盖 fill 色，故 fill 统一用 Color.Black 不影响显示。
 */
object AppIcons {

    private fun build(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()

    val MusicNote: ImageVector by lazy {
        build(
            "MusicNote",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4" +
                "s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"
        )
    }

    val Cloud: ImageVector by lazy {
        build(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 " +
                "2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 " +
                "0,-2.64 -2.05,-4.78 -4.65,-4.96z"
        )
    }

    val Folder: ImageVector by lazy {
        build(
            "Folder",
            "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 " +
                "2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
        )
    }

    val Pause: ImageVector by lazy {
        build("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    }

    val SkipNext: ImageVector by lazy {
        build("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    }

    /**
     * 竖条 + 等腰三角形（顶点水平居中朝左），Material 官方 skip_previous 语义。
     * 注意：官方 path 为 "M6,6h2v12H6zm3.5,6l8.5,6V6z"，其中「z 闭合后紧跟相对移动 m」在
     * 部分旧安卓版本的 path 解析中相对基准出错，导致三角形渲染成直角三角形（顶点被顶到左上角）。
     * 故改写为两个独立子路径 + 纯绝对命令，几何等价（竖条 x6-8、三角顶点 (9.5,12) 等腰），
     * 规避该解析兼容性问题；同时加 UT 断言解析后节点坐标锁定几何。
     */
    val SkipPrevious: ImageVector by lazy {
        build(
            "SkipPrevious",
            "M6,6L8,6L8,18L6,18ZM9.5,12L18,18L18,6Z"
        )
    }

    val Flag: ImageVector by lazy {
        build("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    }

    val Repeat: ImageVector by lazy {
        build(
            "Repeat",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z"
        )
    }

    val RepeatOne: ImageVector by lazy {
        build(
            "RepeatOne",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z" +
                "M13,15V9h-1L9,10.5V12h1.5v3H13z"
        )
    }

    val Timer: ImageVector by lazy {
        build(
            "Timer",
            "M15,1H9v2h6V1zm-4,13h2V8h-2v6zm8.03,-6.61l1.42,-1.42c-0.43,-0.51 " +
                "-0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 " +
                "-9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
                "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
        )
    }

    /** 缓存管理：Material 官方 "storage" 24x24 path */
    val Storage: ImageVector by lazy {
        build(
            "Storage",
            "M2,20h20v-4H2V20zM4,17h2v2H4V17zM2,4v4h20V4H2zM4,6h2V4H4V6z" +
                "M2,12h20v-2H2V12zM4,9h2v2H4V9z"
        )
    }

    /** 关于：Material 官方 "info" 24x24 path */
    val Info: ImageVector by lazy {
        build(
            "Info",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
        )
    }
}
