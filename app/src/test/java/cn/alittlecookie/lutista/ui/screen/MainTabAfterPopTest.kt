package cn.alittlecookie.lutista.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「出栈后落到哪个 Tab」的契约测试。
 *
 * 为什么需要它：三条返回路径（页面内返回箭头 / 系统返回键 / 侧滑 dismiss）最终都汇到
 * `popRoute()` → [mainTabAfterPop]。这段映射写在 Composable 内部时无法被 JVM 单测覆盖，
 * 一旦改错（例如漏掉水印编辑器、或把主题设置映射成配方室），只有真机点一遍才能发现。
 * 抽出纯函数后就用这组用例把它钉死。
 */
class MainTabAfterPopTest {

    @Test
    fun `滤镜编辑器出栈后落到配方室 Tab`() {
        assertEquals(TAB_RECIPE, mainTabAfterPop(Screen.FilterEditor(1L)))
    }

    @Test
    fun `水印编辑器出栈后落到配方室 Tab`() {
        assertEquals(TAB_RECIPE, mainTabAfterPop(Screen.WatermarkEditor(1L)))
    }

    @Test
    fun `主题设置出栈后落到设置 Tab`() {
        assertEquals(TAB_SETTINGS, mainTabAfterPop(Screen.ThemeSettings))
    }

    @Test
    fun `主页出栈不需要纠正 Tab`() {
        assertNull(mainTabAfterPop(Screen.Main))
    }

    @Test
    fun `图片处理页出栈不需要纠正 Tab`() {
        assertNull(mainTabAfterPop(Screen.PhotoProcess(listOf("content://media/1"))))
    }

    @Test
    fun `空栈不需要纠正 Tab`() {
        assertNull(mainTabAfterPop(null))
    }

    @Test
    fun `三个 Tab 常量与 MainTabScreen 的页序一致`() {
        // MainTabScreen 的页面顺序来自 MainPage.entries（HOME / RECIPE / SETTINGS）。
        // 这里刻意不引用 MainPage 本身：该枚举带 Compose ImageVector 字段，在纯 JVM 单测里
        // 加载它会引入不必要的 Android / Compose 运行时依赖。
        assertEquals(0, TAB_HOME)
        assertEquals(1, TAB_RECIPE)
        assertEquals(2, TAB_SETTINGS)
    }
}
