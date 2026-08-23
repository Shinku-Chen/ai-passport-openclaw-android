package com.shinku.aipassport.openclaw.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** 一条对话消息。 */
    data class Message(
        val id: Long,
        val role: String,     // "user" / "agent"
        val text: String,
        val source: String,   // "text" / "voice"
        val ts: Long,
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

    /** 追加一条消息,返回其稳定 id(供后续 replaceById 定位)。 */
    fun add(role: String, text: String, source: String): Long {
        val id = synchronized(this) {
            if (nextId <= 0L) {
                // 首次:以当前时间为上界再 +1,避免与旧时间戳撞号
                nextId = System.currentTimeMillis()
            }
            nextId += 1
            nextId
        }
        update { it + Message(id, role, text, source, System.currentTimeMillis()) }
        return id
    }

    /** 按 id 替换消息内容(用于把"…"占位符换成真实回复)。找到才改,未命中不动。 */
    fun replaceById(id: Long, text: String) {
        update { list ->
            list.map { if (it.id == id) it.copy(text = text) else it }
        }
    }

    private inline fun update(transform: (List<Message>) -> List<Message>) {
        val next = transform(_messages.value)
        _messages.value = next
        persist(next)
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
