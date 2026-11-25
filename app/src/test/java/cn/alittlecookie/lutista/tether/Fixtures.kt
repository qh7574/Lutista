package cn.alittlecookie.lutista.tether

/**
 * 测试用 PTP 回包 fixture 加载器。
 *
 * 仓库里已有真机 dump，位于 `app/src/test/resources/fixtures/`：
 * 每份 `xxx.hex`（纯十六进制文本）配套 `xxx.meta.json`，记录 opcode / 参数 / 响应码 /
 * 字节数 / 是否合成。本加载器只读 hex 部分。
 *
 * ## 给后来者（T03/T07 会照抄这里）的两条硬规则
 * 1. **用 `ClassLoader.getResourceAsStream` 时路径不能带前导 `/`** —— 它的路径是「相对 classpath 根」
 *    的裸路径（`fixtures/xxx.hex`）。带 `/` 只有 `Class.getResourceAsStream` 才认，
 *    混用会静默返回 null，然后报一个和真原因无关的 NPE。
 * 2. main 源码里**不要**读 fixture：这是 `src/test/resources` 的东西，进不了 APK。
 */
internal object Fixtures {

    /** 读一份 hex fixture（自动忽略空白与换行）。 */
    fun hex(name: String): ByteArray {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name.hex")
            ?: error("fixture 不存在：fixtures/$name.hex（应位于 app/src/test/resources/fixtures/）")
        val text = stream.bufferedReader().use { it.readText() }
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "fixture $name 的十六进制长度为奇数：${clean.length}" }
        return ByteArray(clean.length / 2) { index ->
            ((digit(clean[index * 2]) shl 4) or digit(clean[index * 2 + 1])).toByte()
        }
    }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> error("非法十六进制字符：'$char'")
    }
}
