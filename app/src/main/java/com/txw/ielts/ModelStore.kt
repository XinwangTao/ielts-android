package com.txw.ielts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模型管理：外部目录只作为投放入口（adb push），真正加载的副本必须在应用内部存储，
 * 否则 onnxruntime 在 /sdcard（FUSE）上 mmap 会出问题。
 */
object ModelStore {

    fun externalDir(context: Context, prefix: String): File =
        File(context.getExternalFilesDir(null), "models/$prefix")

    fun internalDir(context: Context, prefix: String): File =
        File(context.filesDir, "models/$prefix")

    fun isReady(context: Context, prefix: String): Boolean =
        File(internalDir(context, prefix), "$prefix-encoder.int8.onnx").isFile

    /** 内部没有就从外部拷一份；返回可加载的目录。 */
    suspend fun ensure(context: Context, prefix: String): File = withContext(Dispatchers.IO) {
        val target = internalDir(context, prefix)
        if (isReady(context, prefix)) return@withContext target
        val src = externalDir(context, prefix)
        require(src.isDirectory && File(src, "$prefix-encoder.int8.onnx").isFile) {
            "设备上没有 $prefix 模型。把模型文件放到 ${src.absolutePath}/ 后重试"
        }
        target.mkdirs()
        src.listFiles()?.forEach { f -> f.copyTo(File(target, f.name), overwrite = true) }
        target
    }

    fun missingHint(context: Context, prefix: String): String =
        "缺少模型 $prefix，请执行：\n" +
            "adb push <模型目录>/. ${externalDir(context, prefix).absolutePath}/"
}
