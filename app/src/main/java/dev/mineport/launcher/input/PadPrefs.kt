package dev.mineport.launcher.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 局内按键的持久化（UX-BACKLOG §21.5）。
 *
 * 两类数据分开存，各用各的强项：
 * - 开关 / 滑块这类小值放 SharedPreferences（读同步、写异步，进程内多页面共享）；
 * - 8 个槽位绑定放 `filesDir/action-pad.json`（结构化、可导出，将来主题包和云同步都按文件走）。
 *
 * 读一律**容错优先**：文件缺失、JSON 坏了、槽位数量对不上，统统退回默认绑定，
 * 而不是在开局第一帧抛异常——玩家丢掉的是自定义，不是整局游戏。
 */
class PadPrefs(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val bindingsFile = File(app.filesDir, BINDINGS_FILE)

    /** 摇杆整体不透明度。颜色本身已带 alpha，这里再乘一层，默认 100% 保持原样。 */
    var movePadAlpha: Float
        get() = prefs.getFloat(KEY_MOVE_ALPHA, 1f).coerceIn(MIN_ALPHA, 1f)
        set(value) = prefs.edit().putFloat(KEY_MOVE_ALPHA, value.coerceIn(MIN_ALPHA, 1f)).apply()

    /** 摇杆基座直径（dp）。 */
    var movePadSizeDp: Int
        get() = prefs.getInt(KEY_MOVE_SIZE, MovePad.DEFAULT_BASE_DP).coerceIn(MIN_PAD_DP, MAX_PAD_DP)
        set(value) = prefs.edit().putInt(KEY_MOVE_SIZE, value.coerceIn(MIN_PAD_DP, MAX_PAD_DP)).apply()

    /** 动作轮盘进游戏时是否直接展开；默认收起成小球（UX-21 §21.3）。 */
    var actionPadExpanded: Boolean
        get() = prefs.getBoolean(KEY_PAD_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAD_EXPANDED, value).apply()

    /** 轮盘整体不透明度。 */
    var actionPadAlpha: Float
        get() = prefs.getFloat(KEY_PAD_ALPHA, 1f).coerceIn(MIN_ALPHA, 1f)
        set(value) = prefs.edit().putFloat(KEY_PAD_ALPHA, value.coerceIn(MIN_ALPHA, 1f)).apply()

    /** 轮盘左上角在可用区域内的归一化横坐标（0=贴左，1=贴右）。 */
    var actionPadBallX: Float
        get() = prefs.getFloat(KEY_BALL_X, 0f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_BALL_X, value.coerceIn(0f, 1f)).apply()

    /** 轮盘左上角的归一化纵坐标（0=贴顶，1=贴底）。 */
    var actionPadBallY: Float
        get() = prefs.getFloat(KEY_BALL_Y, 1f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_BALL_Y, value.coerceIn(0f, 1f)).apply()

    /** 是否开启「4 秒无操作自动收起」。 */
    var actionPadAutoCollapse: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COLLAPSE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COLLAPSE, value).apply()

    /**
     * 读 8 个槽位绑定。文件不存在/损坏/数量不对都退回 [ActionPad.defaultSlots]。
     *
     * 注意：不缓存。一个进程里只读一次（GameActivity 启动时），缓存反而会让
     * 「编辑完立刻重进游戏」读到旧值。
     */
    fun loadSlots(): List<ActionSlot> {
        val fallback = ActionPad.defaultSlots()
        val parsed: List<ActionSlot>? = runCatching {
            if (!bindingsFile.isFile) return@runCatching null
            val arr = JSONArray(bindingsFile.readText())
            if (arr.length() != SLOT_COUNT) return@runCatching null
            val list = ArrayList<ActionSlot>(SLOT_COUNT)
            for (i in 0 until SLOT_COUNT) {
                val obj = arr.getJSONObject(i)
                val type = ActionType.valueOf(obj.getString("type"))
                list.add(
                    ActionSlot(
                        index = i,
                        type = type,
                        code = obj.optInt("code", fallback[i].code),
                        label = obj.optString("label", fallback[i].label),
                    )
                )
            }
            list
        }.getOrNull()
        return parsed?.takeIf { it.size == SLOT_COUNT } ?: fallback
    }

    /**
     * 写 8 个槽位绑定。先写临时文件再改名——中途被杀进程也不会留下半个 JSON。
     */
    fun saveSlots(slots: List<ActionSlot>) {
        runCatching {
            if (slots.size != SLOT_COUNT) return
            val arr = JSONArray()
            slots.forEach { slot ->
                arr.put(
                    JSONObject().apply {
                        put("type", slot.type.name)
                        put("code", slot.code)
                        put("label", slot.label)
                    }
                )
            }
            val tmp = File(bindingsFile.parentFile, BINDINGS_FILE + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(bindingsFile)) {
                // 有些文件系统不支持覆盖式 rename：退回直接写。
                bindingsFile.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    /** 恢复默认绑定（设置页「恢复默认」用）。 */
    fun resetSlots(): List<ActionSlot> {
        val defaults = ActionPad.defaultSlots()
        saveSlots(defaults)
        return defaults
    }

    companion object {
        private const val NAME = "mineport-input"
        private const val BINDINGS_FILE = "action-pad.json"

        /** 槽位数量必须和 [ActionPad] 的默认绑定一致，多了少了都视为坏数据。 */
        const val SLOT_COUNT = 8

        /** 不透明度下限：再淡就看不清了，等于逼玩家误触。 */
        const val MIN_ALPHA = 0.2f

        const val MIN_PAD_DP = 96
        const val MAX_PAD_DP = 220

        private const val KEY_MOVE_ALPHA = "movePadAlpha"
        private const val KEY_MOVE_SIZE = "movePadSize"
        private const val KEY_PAD_EXPANDED = "actionPadExpanded"
        private const val KEY_PAD_ALPHA = "actionPadAlpha"
        private const val KEY_BALL_X = "actionPadBallX"
        private const val KEY_BALL_Y = "actionPadBallY"
        private const val KEY_AUTO_COLLAPSE = "actionPadAutoCollapse"
    }
}
