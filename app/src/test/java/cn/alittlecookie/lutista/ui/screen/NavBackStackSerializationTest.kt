package cn.alittlecookie.lutista.ui.screen

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 导航栈的「保存 / 恢复」契约测试。
 *
 * 为什么需要它：迁移到 miuix-nav 后，整条 back stack 由 `rememberNavBackStack<T>` 通过
 * kotlinx.serialization 存进 rememberSaveable（miuix-nav 内 NavBackStackKt 的 Saver 就是
 * `Json.encodeToString(List<T>)` ⇄ `Json.decodeFromString`）。只要路由树上有一个子类型漏标
 * @Serializable、或 reified 的 T 写错（例如写成 `Screen.Main`），编译期一切正常，
 * 故障要等到转屏 / 后台被回收时才炸出来。这里把「栈里多种路由混排」的往返过程固化下来。
 *
 * 说明：库里的 `navBackStackSaver` 与 `navBackStackJson` 都是 internal，测试无法直接复用，
 * 因此这里复刻它完全相同的 JSON 配置（`Json { ignoreUnknownKeys = true }`）。
 */
class NavBackStackSerializationTest {

    /** 与 miuix-nav NavBackStackKt 里的 NavBackStackJson 配置保持一致。 */
    private val navJson = Json { ignoreUnknownKeys = true }

    private val screenListSerializer = ListSerializer(Screen.serializer())

    private fun encode(stack: List<Screen>): String =
        navJson.encodeToString(screenListSerializer, stack)

    private fun decode(encoded: String): List<Screen> =
        navJson.decodeFromString(screenListSerializer, encoded)

    @Test
    fun `能往返包含全部五种路由的混合栈`() {
        val original = listOf<Screen>(
            Screen.Main,
            Screen.PhotoProcess(listOf("content://media/1", "content://media/2")),
            Screen.FilterEditor(42L),
            Screen.WatermarkEditor(-1L),
            Screen.ThemeSettings
        )

        val restored = decode(encode(original))

        assertEquals("恢复后的栈长度必须一致", original.size, restored.size)
        assertEquals("恢复后的栈内容必须逐项相等", original, restored)
        assertEquals(
            "恢复后的每一项类型必须不变",
            original.map { it::class },
            restored.map { it::class }
        )
    }

    @Test
    fun `每条路由单独往返后具体类型不变`() {
        val cases: List<Screen> = listOf(
            Screen.Main,
            Screen.PhotoProcess(emptyList()),
            Screen.PhotoProcess(listOf("a")),
            Screen.FilterEditor(0L),
            Screen.FilterEditor(Long.MAX_VALUE),
            Screen.WatermarkEditor(7L),
            Screen.ThemeSettings
        )

        for (screen in cases) {
            val restored = decode(encode(listOf(screen))).single()
            assertEquals("路由 $screen 往返失败", screen, restored)
            assertEquals("路由 $screen 恢复后类型变了", screen::class, restored::class)
        }
    }

    @Test
    fun `序列化结果里保留子类型判别信息`() {
        val encoded = encode(listOf(Screen.Main, Screen.FilterEditor(1L), Screen.ThemeSettings))

        // 没有判别信息（类名/序号）时 sealed interface 反序列化会直接抛 SerializationException。
        val decoded = decode(encoded)
        assertEquals(listOf<Screen>(Screen.Main, Screen.FilterEditor(1L), Screen.ThemeSettings), decoded)
    }

    /**
     * 反证 MainScreen.kt 里「必须显式写 <Screen>」那条注释：
     * 若 reified 参数写成 `<Screen.Main>`，序列化器就只认 Screen.Main，
     * 一旦栈里 push 进子类型，保存/恢复就会失败。这里把「错误写法」的失败面钉死。
     */
    @Test
    fun `用 Screen-Main 的序列化器无法无损保存子类型`() {
        val wrongSerializer = ListSerializer(Screen.Main.serializer())
        val stack: List<Screen> = listOf(Screen.Main, Screen.FilterEditor(5L))

        // 库里 navBackStackSaver 拿到的是 SnapshotStateList<NavKey>，只在保存时强转成 List<T>，
        // 所以这种「元素类型写错」在编译期是看不出来的，这里显式复刻同样的强转。
        @Suppress("UNCHECKED_CAST")
        val asMainOnly = stack as List<Screen.Main>

        val roundTripped: List<Screen>? = runCatching {
            navJson.encodeToString(wrongSerializer, asMainOnly)
        }.getOrNull()?.let { encoded ->
            runCatching { decode(encoded) }.getOrNull()
        }

        assertNotEquals(
            "List<Screen.Main> 序列化器必须无法无损往返含子类型的栈（要么抛异常，要么丢内容）",
            stack,
            roundTripped
        )
    }

    /**
     * pushRoute() 的去重条件是 `backStack.lastOrNull() == screen`，即 data class 参数相等。
     * 这里固定住「同名路由在什么条件下会被判成重复」——miuix-nav 在 contentKey 重复时
     * 会抛 IllegalArgumentException（NavDisplay 内部用 HashSet 校验 contentKey），
     * 所以去重是必要的。
     *
     * 注意 PhotoProcess 的重复判定自 P2 起不再是「只看图片」：它多了一个每次入栈都不同的
     * requestId（见下一个用例），否则「继续处理 → 再选同一批图」会被静默丢弃。
     */
    @Test
    fun `同名路由的重复判定只看参数`() {
        assertEquals(
            "同一批同序图片 + 同一 requestId = 同一条路由（pushRoute 会丢弃）",
            Screen.PhotoProcess(listOf("a", "b")) as Any,
            Screen.PhotoProcess(listOf("a", "b")) as Any
        )
        assertNotEquals(
            "同一批图片但顺序不同 = 不同路由（pushRoute 会入栈）",
            Screen.PhotoProcess(listOf("a", "b")) as Any,
            Screen.PhotoProcess(listOf("b", "a")) as Any
        )
        assertEquals(
            "相同 filterId 必须是同一条路由",
            Screen.FilterEditor(1L) as Any,
            Screen.FilterEditor(1L) as Any
        )
        assertNotEquals(
            "不同 filterId 必须是不同路由",
            Screen.FilterEditor(1L) as Any,
            Screen.FilterEditor(2L) as Any
        )
        assertNotEquals(
            "FilterEditor 与 WatermarkEditor 不能相等",
            Screen.FilterEditor(1L) as Any,
            Screen.WatermarkEditor(1L) as Any
        )
        assertNotEquals(
            "Main 与 ThemeSettings 不能相等",
            Screen.Main as Any,
            Screen.ThemeSettings as Any
        )
    }

    /** contentKey 的默认实现是 key.toString()，重复即崩溃，所以把 toString 的互异性钉住。 */
    @Test
    fun `contentKey 的 toString 对不同路由保持互不相同`() {
        val keys = listOf<NavKey>(
            Screen.Main,
            Screen.PhotoProcess(listOf("a")),
            Screen.FilterEditor(1L),
            Screen.WatermarkEditor(1L),
            Screen.ThemeSettings
        ).map { it.toString() }

        assertEquals("五种路由的 contentKey 必须互不相同，实际为 $keys", keys.size, keys.toSet().size)
        assertEquals(
            "contentKey 必须可重复计算（同值同串）",
            Screen.Main.toString(),
            Screen.Main.toString()
        )
    }

    /**
     * P2 回归：`Screen.PhotoProcess` 带 requestId 之后，「同一批图片再次入栈」不再是同一条路由。
     *
     * 为什么这条重要：pushRoute 用结构相等去重，miuix-nav 用 contentKey（= toString()）判重，
     * 两者都以结构相等为输入。没有 requestId 时「处理页 → 继续处理 → 再选同一批图」构造出的
     * 路由与栈顶完全相等，会被静默丢弃（用户点了没反应）。这里把「两次入栈互不相等且
     * contentKey 不同」钉死。
     */
    @Test
    fun `同批图片用不同 requestId 入栈后既不相等 contentKey 也不同`() {
        val first = Screen.PhotoProcess(listOf("a", "b"), requestId = 1L)
        val second = Screen.PhotoProcess(listOf("a", "b"), requestId = 2L)

        assertNotEquals("不同 requestId 必须是不同的路由", first as Any, second as Any)
        assertNotEquals("不同 requestId 的 contentKey 必须不同", first.toString(), second.toString())
        assertEquals(
            "同批图片 + 同 requestId 才应判成同一条路由",
            first as Any,
            Screen.PhotoProcess(listOf("a", "b"), requestId = 1L) as Any
        )

        // 两条同批图片的路由必须能同时存在于一条栈里（miuix-nav 要求 contentKey 唯一），
        // 且经过 rememberSaveable 的 JSON 往返后仍互不相同 —— 否则进程恢复时两项会撞车。
        val restored = decode(encode(listOf<Screen>(Screen.Main, first, second)))
        assertEquals("两页同批图片必须都能存活", 3, restored.size)
        assertNotEquals(
            "恢复后 contentKey 仍必须不同",
            restored[1].toString(),
            restored[2].toString()
        )
    }

    /** requestId 必须能跟着栈一起做 JSON 往返，否则进程恢复后 contentKey 会退化并与其他项撞车。 */
    @Test
    fun `带 requestId 的处理页能往返且 requestId 不丢`() {
        val requestId = 1_987_654_321_000L
        val original = listOf<Screen>(
            Screen.Main,
            Screen.PhotoProcess(listOf("content://media/9"), requestId = requestId)
        )

        val restored = decode(encode(original))

        assertEquals("整条栈必须无损往返", original, restored)
        assertEquals(
            "requestId 必须在往返后保留",
            requestId,
            (restored[1] as Screen.PhotoProcess).requestId
        )
    }
}
