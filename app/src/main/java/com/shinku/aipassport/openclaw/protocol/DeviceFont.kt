package com.shinku.aipassport.openclaw.protocol

import java.nio.charset.Charset

/**
 * 设备屏字库的覆盖范围（纯逻辑，便于单测）。
 *
 * 固件把整份中文界面字库编进镜像：`assets/fonts/intercom_cjk_16.c`
 * （`lv_font_intercom_cjk_16`，由固件仓库 `tools/intercom_font.py` 生成），
 * 字符集 = **GB2312 全量（6763 汉字 + 682 符号）+ ASCII**，共 **7540 个码位**。
 *
 * ⚠ 设备端**没有字体回退**：字库里没有的字符在屏幕上就是一个方块（LVGL 的
 * missing-glyph 占位框）。所以凡是会出现在设备屏上的 App 文案（TEXT 气泡、
 * 底部提示行 `gateway.detail`）都必须过一遍 [canRender]。
 *
 * 历史坑（真机反馈「版本号之间是方块乱码」）：版本不匹配提示写的是
 * `固件 1.12 与 App 1.11 大版本不一致（1.12 ↔ 1.11）`，而 `↔`(U+2194) 不在 GB2312 里
 * （GB2312 只有 `← ↑ → ↓`），设备屏上就成了 `1.12 ▯ 1.11`。
 *
 * 同类字符（都很常见、但设备上是方块，写设备文案时要避开）：
 * | 想写 | 码位 | GB2312 | 设备可用的替代 |
 * | --- | --- | --- | --- |
 * | `↔` | U+2194 | ✗ | `≠`(U+2260)、`vs` |
 * | `—` 长破折号 | U+2014 | ✗ | `｜`(U+FF5C)、`-` |
 * | `·` 间隔点 | U+00B7 | ✗ | `・`(U+30FB)、`,` |
 * | `✓ ✔` | U+2713/2714 | ✗ | `OK`、`√`(U+221A) |
 * | emoji（`👍 ⭐`…） | 1F300+ | ✗ | 文字；设备端暂无 emoji 字体 |
 * | `“ ” ‘ ’` | U+201C… | ✓ | 可用 |
 * | `…` | U+2026 | ✓ | 可用 |
 * | 全角标点 `（），：` | U+FF01–FF5E | ✓ | 可用 |
 *
 * 判定用的是 JDK/Android 的 `GB2312` 字符集：实测它可编码的码位（7541 个，含 DEL）
 * 与固件字库的码位清单 `intercom_cjk_symbols.txt`（7540 个）**逐位一致** ——
 * 所以 [canRender] 就是设备屏显示能力的精确代理。
 */
object DeviceFont {

    /** 判定用字符集；与设备字库字符集逐位一致（见类注释）。 */
    private val GB2312: Charset = Charset.forName("GB2312")

    /** 整段文字都能在设备屏上原样显示吗？ */
    fun canRender(text: String): Boolean = text.all { canRender(it) }

    /**
     * 单个字符能在设备屏上显示吗？
     *
     * ASCII 一定可以；其余要求能按 GB2312 编码**且往返一致** —— 只看「能编码」不够：
     * 个别字符会被映射成另一个字形（例：`·` 若按 GB2312 的 `・` 编码，设备上画出来的
     * 是 `・` 而不是 `·`），往返不一致就按「显示不了」处理。
     */
    fun canRender(ch: Char): Boolean = ch.code < 0x80 || roundTrips(ch)

    /** 第一个显示不了的字符（测试报错时用来指出到底是哪个字）；全都能显示则为 null。 */
    fun firstUnrenderable(text: String): Char? = text.firstOrNull { !canRender(it) }

    private fun roundTrips(ch: Char): Boolean = runCatching {
        String(ch.toString().toByteArray(GB2312), GB2312) == ch.toString()
    }.getOrDefault(false)
}
