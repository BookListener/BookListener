package com.booklistener.core

/**
 * v2.0.1 播放速度档位：0.5x-2.0x。
 * 纯逻辑（不依赖 ViewModel 实例/播放器），可独立 JVM 单测。
 * 档位循环取"大于当前值的最小档"，末档回到 0.5；
 * 不在档位内的值取最近大于它的档（防御持久化脏数据）。
 */
object PlaybackSpeedTiers {
    val TIERS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val MIN: Float = TIERS.first()
    val MAX: Float = TIERS.last()

    /** 当前速度的下一档（末档回 0.5）；不在档位内取大于它的最近档 */
    fun next(current: Float): Float =
        TIERS.firstOrNull { it > current + 0.001f } ?: TIERS.first()

    /** 离 speed 最近的档位（Slider 拖动吸附用，保证滑块位置与下方档位标志一致） */
    fun nearest(speed: Float): Float =
        TIERS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f

    /** 档位文字：1.0 → "1.0x"、1.25 → "1.25x"、0.75 → "0.75x" */
    fun label(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    /** 档位在 Slider 行程上的线性比例：0.5→0%、1.25→50%、2.0→100%（纯逻辑，可 JVM 单测） */
    fun fraction(tier: Float): Float =
        (tier - MIN) / (MAX - MIN)
}
