package com.dongholab.pagetuner.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val TAG = "PageTurnerDiagnostic"
    private const val MaxLogs = 200

    private val _logsState = MutableStateFlow<List<String>>(emptyList())
    val logsState: StateFlow<List<String>> = _logsState.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(stepTag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $stepTag $message"

        runCatching {
            Log.i(TAG, "$stepTag $message")
        }.onFailure {
            println("[$TAG] $logLine")
        }

        _logsState.update { current ->
            (current + logLine).takeLast(MaxLogs)
        }
    }

    fun clear() {
        _logsState.value = emptyList()
    }
}
