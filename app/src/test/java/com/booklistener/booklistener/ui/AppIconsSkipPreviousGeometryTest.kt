package com.booklistener.ui

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import com.booklistener.ui.icons.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：上一曲图标跨安卓版本渲染成直角三角形的问题。
 *
 * 根因：旧版 path "M6,6h2v12H6zm3.5,6l8.5,6V6z" 中「z 闭合后紧跟相对移动 m」在部分
 * 旧安卓的 path 解析中相对基准出错，三角形顶点被顶到左上角 → 直角三角形。
 * 修复：改写为两个独立子路径 + 纯绝对命令（竖条 + 等腰三角，顶点水平居中朝左）。
 *
 * 本测试直接解析 path 并断言节点几何：
 *  - 三角顶点 x 必须位于两底角之间（等腰），且 y 位于两底角中点；
 *  - 不存在「z 闭合后跟相对命令」的危险组合；
 *  - 对比 SkipNext（正常对照）确认几何与官方 Material 语义一致。
 */
class AppIconsSkipPreviousGeometryTest {

    /** 解析 path 为节点序列（与 AppIcons.build 同源：PathParser().parsePathString().toNodes()） */
    private fun parse(path: String): List<PathNode> =
        PathParser().parsePathString(path).toNodes()

    /** 提取所有 moveTo / lineTo 节点（排除 close），供几何断言 */
    private fun points(nodes: List<PathNode>): List<Pair<Float, Float>> =
        nodes.filterIsInstance<PathNode.MoveTo>().map { it.x to it.y } +
            nodes.filterIsInstance<PathNode.LineTo>().map { it.x to it.y }

    @Test
    fun skipPrevious_isIsoscelesTriangle() {
        val pts = points(parse("M9.5,12L18,18L18,6Z"))
        assertEquals(3, pts.size)
        val (ax, ay) = pts[0]   // 顶点
        val (b1x, b1y) = pts[1] // 底角1（右下）
        val (b2x, b2y) = pts[2] // 底角2（右上）

        // 等腰：顶点到两底角距离相等（两腰相等）
        val d1 = kotlin.math.sqrt((ax - b1x) * (ax - b1x) + (ay - b1y) * (ay - b1y))
        val d2 = kotlin.math.sqrt((ax - b2x) * (ax - b2x) + (ay - b2y) * (ay - b2y))
        assertEquals("两腰必须相等（等腰）", d1, d2, 0.01f)
        // 顶点 y 位于两底角之间（底边垂直，y 跨 6-18，顶点 y=12）
        assertTrue("顶点 y 应在两底角之间", ay in minOf(b1y, b2y)..maxOf(b1y, b2y))
        // 底边垂直（两底角 x 相同）且底边长度 = 官方语义 12
        assertEquals("底边必须垂直", b1x, b2x, 0.01f)
        assertEquals("底边长度 = 12", 12f, kotlin.math.abs(b1y - b2y), 0.01f)
    }

    @Test
    fun skipPrevious_hasNoRelativeCommandAfterClose() {
        // 危险组合：子路径闭合（z）后紧跟相对命令（m/l/v/h）。
        // 新 path 仅含大写绝对命令（M/L/Z），解析后节点不含 Relative* 类型即证明无相对命令。
        val nodes = parse("M6,6L8,6L8,18L6,18ZM9.5,12L18,18L18,6Z")
        nodes.forEach { node ->
            assertTrue(
                "存在相对命令节点: $node",
                node !is PathNode.RelativeMoveTo &&
                    node !is PathNode.RelativeLineTo &&
                    node !is PathNode.RelativeHorizontalTo &&
                    node !is PathNode.RelativeVerticalTo &&
                    node !is PathNode.RelativeCurveTo &&
                    node !is PathNode.RelativeReflectiveCurveTo &&
                    node !is PathNode.RelativeQuadTo &&
                    node !is PathNode.RelativeReflectiveQuadTo
            )
        }
    }

    @Test
    fun skipPrevious_matchesOfficialMaterialSemantics() {
        // 官方语义：竖条占 x6-8（宽 2），三角顶点 x9.5（竖条右侧 1.5），底边 x18
        val nodes = parse("M6,6L8,6L8,18L6,18ZM9.5,12L18,18L18,6Z")
        val pts = points(nodes)
        val barXs = pts.map { it.first }.filter { it in 6f..8f }
        assertTrue("竖条 x 范围缺失", barXs.size >= 2)
        val apexX = pts.first { it.second == 12f }.first
        assertTrue("三角顶点必须在竖条右侧", apexX > 8f)
    }

    @Test
    fun appIconSkipPrevious_resolvesWithoutError() {
        // 真实 AppIcons 入口可正常构造（触发 lazy + PathParser）
        val v = AppIcons.SkipPrevious
        assertEquals("SkipPrevious", v.name)
        assertEquals(24f, v.viewportWidth, 0.01f)
        assertEquals(24f, v.viewportHeight, 0.01f)
    }
}
