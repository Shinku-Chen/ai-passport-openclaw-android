package com.shinku.aipassport.openclaw.tts

/**
 * 下行音频**写模式**的契约(纯逻辑,JVM 单测钉住):哪条链路用哪种 GATT 写。
 *
 * 为什么要单独拿出来钉:写模式不是实现细节,它决定音频能不能**到得了**设备 ——
 * 2026-10 真机 A/B(同一台手机 + 同一固件)结论:
 *  - **无响应写**(`WRITE_NO_RESPONSE` / Write Command)在小智直通上会**静默丢帧**:
 *    App 侧报「写入成功 N 帧」,设备侧 `TTS` 计数恒为 0,而设备的 `RX 缓冲满` 计数也为 0
 *    —— 不是设备 ring 溢出,是这些写根本没到设备(无应答,丢了也不报错);
 *  - **带响应写**(`WRITE_TYPE_DEFAULT`)实测 207 帧 / 11.2s ≈ **18.5 帧/秒**,高于 16.7 帧/秒
 *    的实时需求(旧注释里「带响应写只有 11 帧/秒、会饿着设备」的假设是错的);
 *    而且只有它有 GATT 写回调,`BleCentral.deliveredFrameCount()` 才是真实在途量。
 *
 * @param bulkWrite 传给 `BleCentral.setBulkWrite`:true = 无响应写,false = 带响应写
 */
enum class TtsWriteMode(val bulkWrite: Boolean) {

    /** 带响应写(有 ATT 应答;丢帧会报错,并能拿到 GATT 写回调做真实在途流控)。 */
    WITH_RESPONSE(false),

    /** 无响应写(Write Command:快,但无应答 —— 本机/本固件组合上实测会静默丢帧)。 */
    NO_RESPONSE(true);

    companion object {

        /**
         * **小智直通**(音频由小智云端下发、本端原样转发):**固定带响应写**。
         *
         * 这条契约由 [com.shinku.aipassport.openclaw.service.VoiceBridgeService] 的
         * `drainXiaozhiTts` 使用 —— 不要再改回无响应写(真机上表现为「设备侧 TTS 计数恒为 0」)。
         */
        val XIAOZHI_DIRECT: TtsWriteMode = WITH_RESPONSE

        /**
         * **本机合成**下行(文本 → 本机合成 PCM → 编 Opus):仍是历史行为(**无响应写**)。
         *
         * **待真机复核**:同一手机/固件组合上,这条路很可能与小智直通一样被静默丢帧,
         * 但本次不动机器合成的写模式(它有自己的合成/编码节奏与验收),先按现状保留并标记。
         */
        val LOCAL_SYNTHESIS: TtsWriteMode = NO_RESPONSE
    }
}
