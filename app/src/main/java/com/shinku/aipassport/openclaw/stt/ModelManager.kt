package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Vosk 模型下载/部署/发现。
 *
 * Vosk 模型是 zip(内含 vosk-model-xxx/ 目录结构),解压到 App 内部存储 filesDir。
 * 模型目录名含 "vosk-model" 即被 SttFactory 发现。下载/解压跑在 IO 线程,回调主线程。
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** 下载源(已验证可达)。 */
    const val SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    const val LARGE_URL = "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"

    private val client = OkHttpClient.Builder().build()

    /** filesDir 下所有 vosk-model* 目录。 */
    fun installedModels(context: Context): List<File> {
        return context.filesDir
            ?.listFiles { f -> f.isDirectory && f.name.contains("vosk-model") }
            ?.toList() ?: emptyList()
    }

    /** 当前用于识别的模型目录(SttFactory 逻辑,优先大模型);无则 null。 */
    fun currentModelDir(context: Context): File? {
        val models = installedModels(context)
        if (models.isEmpty()) return null
        // 优先名字含 large 或 cn-0.22(非 small):大模型识别率更高。
        val large = models.filter {
            it.name.contains("large") || (it.name.contains("cn-0.22") && !it.name.contains("small"))
        }
        val pool = if (large.isNotEmpty()) large else models
        // 再取目录体积最大的(≈模型最大,识别率最高)。
        return pool.maxByOrNull { dirSize(it) } ?: pool.first()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 下载 zip 到 filesDir 临时文件,解压到 filesDir,删临时 zip。
     * onProgress(percent) / onDone(modelDir) / onError(msg) 都在主线程回调。
     */
    suspend fun downloadModel(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val dir = context.filesDir
        if (dir == null) { onError("filesDir 不可用"); return }
        // 下载到临时文件(避免与已安装模型目录冲突)
        val tmpZip = File(dir, "vosk_download_tmp.zip")
        var ok = false
        try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw RuntimeException("HTTP ${resp.code}")
                    }
                    val total = resp.body?.contentLength() ?: -1L
                    val input = resp.body?.byteStream()
                        ?: throw RuntimeException("无响应体")
                    FileOutputStream(tmpZip).use { fos ->
                        val bos = BufferedOutputStream(fos, 64 * 1024)
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            bos.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) onProgressSafe(onProgress, downloaded.toFloat() / total)
                        }
                    }
                }
            }
            // 解压(IO 线程),保留 zip 内顶层目录名
            val outDir = withContext(Dispatchers.IO) {
                unzip(tmpZip, dir)
            }
            // 删除临时 zip
            tmpZip.delete()
            ok = true
            onDone(outDir)
        } catch (e: Exception) {
            Log.e(TAG, "下载/解压失败", e)
            tmpZip.delete()
            onError(e.message ?: "下载失败")
        }
    }

    /** 解压 zip 到 destDir,返回解压出的模型目录。 */
    private fun unzip(zip: File, destDir: File): File {
        var topDir: File? = null
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                // 去掉 zip 内可能的绝对/上级路径,只保留目录名
                val clean = entryName.trimStart('/')
                if (clean.isEmpty()) { entry = zis.nextEntry; continue }
                val target = File(destDir, clean)
                if (entry.isDirectory) {
                    target.mkdirs()
                    if (topDir == null) topDir = target
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (topDir == null) {
            topDir = destDir.listFiles { f -> f.isDirectory && f.name.contains("vosk-model") }
                ?.firstOrNull()
        }
        return topDir ?: destDir
    }

    private suspend fun onProgressSafe(cb: (Float) -> Unit, v: Float) {
        // 从 IO 线程切主线程回调
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { cb(v) }
    }
}
