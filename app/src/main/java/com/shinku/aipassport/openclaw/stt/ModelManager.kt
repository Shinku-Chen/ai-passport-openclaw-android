package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Vosk 模型下载/部署/发现。
 *
 * 模型目录名含 "vosk-model" 即被 SttFactory 发现。下载/解压跑在 IO 线程,回调主线程。
 * 内置 small 模型打包进 assets,首装自动复制到 filesDir(ensureBundled)。
 * large 模型由用户在设置页从 host 列表手动下载。
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** 打包进 assets 的 small 模型目录名(assets 根下)。 */
    private const val BUNDLED_MODEL = "vosk-model-small-cn-0.22"

    /**
     * large 模型多个下载 host(label → url)。以用户实际网络可访问为准;
     * 官方 alphacephei 稳定可达(200)。下载失败可换 host 重试。
     */
    val LARGE_HOSTS: List<Pair<String, String>> = listOf(
        "官方 (alphacephei.com)" to "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
        // 备选:镜像站(若墙外/网速问题可再补充;这里保留官方/huggingface 直连路径)
        "HuggingFace 直连" to "https://huggingface.co/alphacephei/vosk-model-cn-0.22/resolve/main/vosk-model-cn-0.22.zip",
    )

    private val client = OkHttpClient.Builder().build()

    /** 判断 large 模型是否已安装(filesDir 下名字含 cn-0.22 且非 small)。 */
    fun isLargeInstalled(context: Context): Boolean {
        for (f in installedModels(context)) {
            if (f.name.contains("cn-0.22") && !f.name.contains("small")) return true
        }
        return false
    }

    /**
     * 首装确保打包的 small 模型在 filesDir(否则从 assets 递归复制)。
     * 幂等:若已存在任何 vosk-model* 或有 small 目录,跳过。无模型时才复制。
     */
    fun ensureBundled(context: Context) {
        val dir = context.filesDir ?: return
        if (installedModels(context).isNotEmpty()) {
            Log.i(TAG, "已存在模型,跳过 assets 复制")
            return
        }
        val target = File(dir, BUNDLED_MODEL)
        if (target.exists()) { Log.i(TAG, "bundled 模型已在 filesDir"); return }
        try {
            val am = context.assets
            var count = 0
            copyAssetDir(am, BUNDLED_MODEL, target) { count++ }
            Log.i(TAG, "已从 assets 复制 small 模型到 filesDir, $count 个文件")
        } catch (e: Exception) {
            Log.e(TAG, "assets 复制 small 模型失败", e)
        }
    }

    // 递归复制 assets 目录到目标目录(逐文件读入 -> 写盘)。
    private fun copyAssetDir(am: android.content.res.AssetManager, assetPath: String, destDir: File, onFile: () -> Unit) {
        val children = try { am.list(assetPath) ?: emptyArray() } catch (e: Exception) { emptyArray() }
        if (children.isEmpty()) {
            // 是文件
            destDir.parentFile?.mkdirs()
            am.open(assetPath).use { input: InputStream ->
                FileOutputStream(destDir).use { out ->
                    input.copyTo(out)
                }
            }
            onFile()
        } else {
            destDir.mkdirs()
            for (c in children) {
                copyAssetDir(am, "$assetPath/$c", File(destDir, c), onFile)
            }
        }
    }

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
        var pool = models
        val large = mutableListOf<File>()
        for (f in models) {
            if (f.name.contains("large") || (f.name.contains("cn-0.22") && !f.name.contains("small"))) {
                large.add(f)
            }
        }
        if (large.isNotEmpty()) pool = large
        // 再取目录体积最大的(≈模型最大,识别率最高)。
        var best: File? = null
        var bestSize = -1L
        for (f in pool) {
            val s = dirSize(f)
            if (s > bestSize) { bestSize = s; best = f }
        }
        return best ?: pool.first()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return dir.length()
        var total = 0L
        for (f in dir.walkTopDown()) {
            if (f.isFile) total += f.length()
        }
        return total
    }

    /**
     * 分块并行下载 zip 到 filesDir 临时文件,合并后解压,删临时 zip。
     * alphacephei 单连接限速低(实测 ~26KB/s),用 Range 分块并行下载提速(多连接并发)。
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
        val tmpZip = File(dir, "vosk_download_tmp.zip")
        val CHUNKS = 8
        try {
            // 先 HEAD 拿总大小
            val total = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).head().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    resp.body?.contentLength() ?: -1L
                }
            }
            if (total <= 0) { onError("无法获取文件大小"); return }
            val chunkSize = total / CHUNKS

            // 并行下载各块(写 vosk_dl_$i.tmp);用 AtomicLong 累计已下载字节,供进度
            val downloaded = java.util.concurrent.atomic.AtomicLong(0L)
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val deferred = (0 until CHUNKS).map { i ->
                        async(Dispatchers.IO) {
                            val start = i * chunkSize
                            val end = if (i == CHUNKS - 1) total - 1 else (i + 1) * chunkSize - 1
                            if (start > end) return@async  // 空块
                            val req = Request.Builder().url(url)
                                .header("Range", "bytes=$start-$end")
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.code != 206 && resp.code != 200) {
                                    throw RuntimeException("HTTP ${resp.code}")
                                }
                                val input = resp.body?.byteStream() ?: throw RuntimeException("无响应体")
                                val chunkFile = File(dir, "vosk_dl_$i.tmp")
                                FileOutputStream(chunkFile).use { fos ->
                                    val buf = ByteArray(64 * 1024)
                                    var read: Int
                                    while (input.read(buf).also { read = it } != -1) {
                                        fos.write(buf, 0, read)
                                        downloaded.addAndGet(read.toLong())
                                    }
                                }
                            }
                            onProgressSafe(onProgress, downloaded.get().toFloat() / total)
                        }
                    }
                    deferred.awaitAll()
                }
                // 合并块
                FileOutputStream(tmpZip).use { out ->
                    val bos = BufferedOutputStream(out, 256 * 1024)
                    for (i in 0 until CHUNKS) {
                        val cf = File(dir, "vosk_dl_$i.tmp")
                        if (cf.exists()) {
                            cf.inputStream().use { it.copyTo(bos) }
                        }
                    }
                    bos.flush()
                }
                onProgressSafe(onProgress, 1f)
            }

            // 清理块文件
            for (i in 0 until CHUNKS) { File(dir, "vosk_dl_$i.tmp").delete() }

            // 解压,保留 zip 内顶层目录名
            val outDir = withContext(Dispatchers.IO) { unzip(tmpZip, dir) }
            tmpZip.delete()
            onDone(outDir)
        } catch (e: Exception) {
            Log.e(TAG, "下载/解压失败", e)
            tmpZip.delete()
            for (i in 0 until CHUNKS) { File(dir, "vosk_dl_$i.tmp").delete() }
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
