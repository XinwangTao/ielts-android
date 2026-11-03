package com.alibaba.mnnllm.android.llm

/**
 * libmnnllmapp.so 的回调接口。
 * 包名 / 接口名 / 方法名不能改：so 内部按这个全限定名反射查找。
 */
interface GenerateProgressListener {
    /** 流式回调一个片段；返回 true 表示请求中断生成。生成结束时会回调 null。 */
    fun onProgress(progress: String?): Boolean
}

/**
 * MNN LLM 的最小 JNI 封装（从 alibaba/MNN 的 MnnLlmChat 裁剪，Apache-2.0）。
 *
 * 包名、类名、方法名必须与 so 内静态注册符号完全一致：
 * `Java_com_alibaba_mnnllm_android_llm_LlmSession_{initNative,submitNative,releaseNative}`。
 * 改任何一项都会在 System.loadLibrary 后调用时崩溃。
 */
class LlmSession {

    private var ptr: Long = 0L

    /**
     * 加载模型。
     * @param configPath 模型目录下 config.json 的绝对路径（native 端据此定位同目录权重）
     * @param mergedConfigJson llm_config.json 内容（可覆盖 max_new_tokens / backend 等）
     * @param extraConfigJson 运行参数：{"is_r1":false,"mmap_dir":"","keep_history":false}
     */
    fun load(
        configPath: String,
        mergedConfigJson: String,
        extraConfigJson: String = """{"is_r1":false,"mmap_dir":"","keep_history":false}""",
    ): Boolean {
        ptr = initNative(configPath, null, mergedConfigJson, extraConfigJson)
        return ptr != 0L
    }

    /** 阻塞式生成：native 线程通过 listener 流式回调，全部结束后返回统计信息。 */
    fun submit(input: String, listener: GenerateProgressListener): HashMap<String, Any> {
        check(ptr != 0L) { "本地模型未加载" }
        return submitNative(ptr, input, false, listener)
    }

    fun release() {
        if (ptr != 0L) {
            releaseNative(ptr)
            ptr = 0L
        }
    }

    val isLoaded: Boolean get() = ptr != 0L

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?,
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun releaseNative(instanceId: Long)

    companion object {
        init {
            System.loadLibrary("mnnllmapp")
        }
    }
}
