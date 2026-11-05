package com.txw.ielts.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 当前是否有可用网络。异常时按「有网络」处理，避免误判把用户锁在本地模型。 */
object NetworkStatus {
    fun hasNetwork(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
