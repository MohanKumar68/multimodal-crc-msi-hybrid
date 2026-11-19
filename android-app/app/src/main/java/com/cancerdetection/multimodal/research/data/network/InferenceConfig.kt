package com.cancerdetection.multimodal.research.data.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InferenceConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("inference_config", Context.MODE_PRIVATE)

    private val _serverUrlFlow = MutableStateFlow(getServerBaseUrl())
    val serverUrlFlow: StateFlow<String> = _serverUrlFlow.asStateFlow()

    fun getServerBaseUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerBaseUrl(value: String) {
        val sanitized = value.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/health").removeSuffix("/")
        prefs.edit().putString(KEY_SERVER_URL, sanitized).apply()
        _serverUrlFlow.value = sanitized
    }

    var serverUrl: String
        get() = getServerBaseUrl()
        set(value) = setServerBaseUrl(value)

    fun getSummaryTimeoutMs(): Int {
        return prefs.getInt(KEY_SUMMARY_TIMEOUT_MS, DEFAULT_SUMMARY_TIMEOUT_MS)
    }

    fun setSummaryTimeoutMs(value: Int) {
        val clamped = value.coerceIn(15_000, 300_000)
        prefs.edit().putInt(KEY_SUMMARY_TIMEOUT_MS, clamped).apply()
    }

    companion object {
        // Default backend endpoint: Android emulator loopback (10.0.2.2 maps to host workstation localhost)
        // Physical devices must configure their workstation's local Wi-Fi IPv4 address (e.g. http://192.168.X.X:5000)
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:5000"
        private const val KEY_SERVER_URL = "server_url"

        // Default timeout for local LLM (Ollama) summary generation: 120 seconds
        const val DEFAULT_SUMMARY_TIMEOUT_MS = 120_000
        private const val KEY_SUMMARY_TIMEOUT_MS = "summary_timeout_ms"
    }
}
