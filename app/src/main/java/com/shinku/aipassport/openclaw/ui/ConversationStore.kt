package com.shinku.aipassport.openclaw.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 共享、持久化的对话历史存储(进程内单例)。
 *
 * 让"对话框打字聊天"(ChatFragment)与"硬件语音对话"(VoicePipeline)共用同一条对话历史:
 *  - 双方都往里写,列表实时反映;既有打字记录也有语音记录,按 [Message.source] 区分。
 *  - 用 [MutableStateFlow] 暴露,任何协程线程写都原子(ChatFragment 在主线程 collect,
 *    VoicePipeline 的 gateway.chat 在 IO 线程写,互不阻塞)。
 *  - 持久化到 SharedPreferences(最近的 maxItems 条),App 重启、切换 Tab 后仍保留。
 *
 * 首次使用时需 [init],传入 applicationContext(kotlin/object 单例没有 Context)。
 */
object ConversationStore {

    private const val TAG = "ConversationStore"
    private const val PREFS = "conversation_store"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_ITEMS = 200

    const val SOURCE_TEXT = "text"      // 对话框输入
    const val SOURCE_VOICE = "voice"    // 硬件语音

    /** 一条对话消息。
     *
     * @param appliedSuffix 仅语音输入消息使用:实际附加到本条语音末尾、发给网关的提示词。
     *   非空时对话列表在用户气泡下方展示一行弱化小字「＋附加提示：<suffix>」,
     *   让用户看到「实际发给网关的完整文本」;文字输入与网关回复恒为 null。
     * @param label 非 null = **弱化小字**样式的 raw 回传条目标签(如 `步骤 · Exec Fetch …`);
     *   null = 正常气泡(body 正文)。
     * @param flag 条目上的标记(目前只有「[疑似状態话术]」);只加标记,不改 [text]。
     */
    data class Message(
        val id: Long,
        val role: String,     // "user" / "agent"
        val text: String,
        val source: String,   // "text" / "voice"
        val ts: Long,
        val appliedSuffix: String? = null,
        val label: String? = null,
        val flag: String? = null,
    )

    private val gson = Gson()
    private val listType = object : TypeToken<List<Message>>() {}.type

    private var prefs: SharedPreferences? = null
    private var nextId = 0L

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    /** 用 applicationContext 初始化(幂等;可从 Activity/Fragment/Service 任一处调用)。 */
    fun init(context: Context) {
        val app = context.applicationContext
        val p = prefs ?: run {
            val pp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = pp
            pp
        }
        if (_messages.value.isEmpty()) {
            load(p)
        }
    }

    /**
     * 追加一条消息,返回其稳定 id(供后续 replaceById 定位)。
     *
     * @param appliedSuffix 仅语音输入使用:附加到本条末尾、发给网关的提示词(非空则 UI 另起一行弱化展示)
     * @param label raw 回传条目标签(非 null = 弱化小字样式);body 正文为 null
     * @param flag 条目标记(「[疑似状態话术]」);不改动 [text]
     */
    fun add(
        role: String,
        text: String,
        source: String,
        appliedSuffix: String? = null,
        label: String? = null,
        flag: String? = null,
    ): Long {
        val id = synchronized(this) {
            if (nextId <= 0L) {
                // 首次:以当前时间为上界再 +1,避免与旧时间戳撞号
                nextId = System.currentTimeMillis()
            }
            nextId += 1
            nextId
        }
        update { it + Message(id, role, text, source, System.currentTimeMillis(), appliedSuffix, label, flag) }
        return id
    }

    /** 追加一条 App 展示项(网关回复的 body / raw 条目);role 固定为 "agent"。 */
    fun add(entry: ReplyDisplayEntry, source: String): Long =
        add(
            role = "agent",
            text = entry.text,
            source = source,
            label = entry.label,
            flag = entry.flag,
        )

    /** 按 id 替换消息内容(用于把"…"占位符换成真实回复)。找到才改,未命中不动。 */
    fun replaceById(id: Long, text: String, label: String? = null, flag: String? = null) {
        update { list ->
            list.map { if (it.id == id) it.copy(text = text, label = label, flag = flag) else it }
        }
    }

    /**
     * 把一条消息**降级**成弱化小字条目(只改 label / flag,保留 [Message.text])。
     *
     * 用途:同一轮只允许一个正常正文气泡([Message.label] == null)—— 历史补正就地替换第一条正文气泡后,
     * 本轮其余的正文气泡降级成 `正文 · 全文` 弱化小字(见 `ui/BodyBubbles.kt` 的 [writeBodyCorrection])。
     * 未命中的 id 不动。
     */
    fun demoteById(id: Long, label: String) {
        update { list ->
            list.map {
                if (it.id == id) it.copy(label = label, flag = statusTalkFlag(it.text)) else it
            }
        }
    }

    private inline fun update(transform: (List<Message>) -> List<Message>) {
        // 用 MutableStateFlow.update(CAS)原子更新,避免跨线程并发时
        // "读旧 value → 算新 → 写回" 的竞态覆盖(否则多 turn/语音+打字并发会乱序/丢消息)。
        _messages.update { transform(it) }
        persist(_messages.value)
    }

    private fun load(p: SharedPreferences) {
        val raw = p.getString(KEY_MESSAGES, null)
        if (raw.isNullOrEmpty()) return
        try {
            val loaded: List<Message> = gson.fromJson(raw, listType) ?: emptyList()
            if (loaded.isNotEmpty()) {
                _messages.value = loaded
                nextId = loaded.maxOf { it.id }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载对话历史失败,丢弃", e)
        }
    }

    private fun persist(list: List<Message>) {
        val p = prefs ?: return
        val trimmed = if (list.size > MAX_ITEMS) list.takeLast(MAX_ITEMS) else list
        val json = gson.toJson(trimmed, listType)
        p.edit().putString(KEY_MESSAGES, json).apply()
    }
}
