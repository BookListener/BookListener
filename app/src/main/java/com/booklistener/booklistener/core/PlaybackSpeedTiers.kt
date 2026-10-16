package com.booklistener.core

/**
 * v2.0.1 播放速度档位：0.5x-2.0x；v2.0.9 新增 1.75x，共 7 档。
 * 纯逻辑（不依赖 ViewModel 实例/播放器），可独立 JVM 单测。
 * 档位循环取"大于当前值的最小档"，末档回到 0.5；
 * 不在档位内的值取最近大于它的档（防御持久化脏数据）。
 */
object PlaybackSpeedTiers {
    val TIERS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val MIN: Float = TIERS.first()
    val MAX: Float = TIERS.last()

    /** 当前速度的下一档（末档回 0.5）；不在档位内取大于它的最近档 */
    fun next(current: Float): Float =
        TIERS.firstOrNull { it > current + 0.001f } ?: TIERS.first()

    /** 离 speed 最近的档位（拖动吸附用，保证滑块位置与下方档位标志一致） */
    fun nearest(speed: Float): Float =
        TIERS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f

    /** 档位文字：1.0 → "1.0x"、1.25 → "1.25x"、1.75 → "1.75x" */
    fun label(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    /**
     * 档位在 Slider 行程上的位置比例：v2.0.9 起按档位序号**均匀**分布
     * （0.5→0%、0.75→1/6、1.0→2/6、1.25→3/6、1.5→4/6、1.75→5/6、2.0→100%），
     * 与 Slider 的索引语义（valueRange=0..size-1）一致，保证 thumb 与档位标签刻度对齐。
     * 非档位值回退线性比例（防御性）。
     */
    fun fraction(tier: Float): Float {
        var index = -1
        for (i in TIERS.indices) if (TIERS[i] == tier) { index = i; break }
        return if (index >= 0) index.toFloat() / (TIERS.size - 1).toFloat()
        else (tier - MIN) / (MAX - MIN)
    }
}
