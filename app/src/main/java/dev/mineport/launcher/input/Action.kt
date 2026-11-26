package dev.mineport.launcher.input

/**
 * 意图级动作（PRODUCT-CORE 二.4：不映射满屏按键，而是动作轮盘）。
 * 具体键位由输入桥在 JVM 侧翻译，模组从 10 个到 100 个，主屏动作数量不变。
 */
enum class GameAction(val label: String, val glyph: String) {
    FORWARD("前", "▲"),
    RIGHT("右", "▶"),
    BACK("后", "▼"),
    LEFT("左", "◀"),
    JUMP("跳", "⤒"),
    SNEAK("潜", "⤓"),
    ATTACK("攻", "✕"),
    USE("用", "●");

    companion object {
        val Defaults: List<GameAction> =
            listOf(FORWARD, RIGHT, BACK, LEFT, JUMP, SNEAK, ATTACK, USE)
    }
}
