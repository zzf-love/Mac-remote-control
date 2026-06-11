package com.macremote.rfb

/**
 * X11 keysym 映射（RFB KeyEvent 使用 keysym 编码按键）。
 *
 * 规则：
 * - ASCII 可打印字符（0x20..0x7E）即其本身；
 * - Latin-1 补充区（0xA0..0xFF）即其本身；
 * - 其余 Unicode 字符使用 0x01000000 + 码点（中文等由服务器端输入法接收）；
 * - 功能键 / 修饰键使用 0xFFxx 专用值。
 *
 * macOS 屏幕共享对修饰键的对应关系：
 *   Control -> ⌃    Alt/Option -> ⌥    Meta/Super -> ⌘（Command）
 */
object Keysym {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val F1 = 0xFFBE // F2..F12 依次 +1

    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val META_L = 0xFFE7  // macOS: Command ⌘
    const val ALT_L = 0xFFE9   // macOS: Option ⌥
    const val SUPER_L = 0xFFEB // 备选 Command 映射（个别服务器版本）

    fun fromChar(c: Char): Int {
        val cp = c.code
        return when {
            c == '\n' || c == '\r' -> RETURN
            c == '\t' -> TAB
            cp in 0x20..0x7E -> cp
            cp in 0xA0..0xFF -> cp
            else -> 0x01000000 + cp
        }
    }
}
