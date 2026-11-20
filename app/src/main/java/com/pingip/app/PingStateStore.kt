package com.pingip.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PingStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow("Berhenti")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setStatus(status: String) {
        _statusText.value = status
    }

    @Synchronized
    fun appendLog(line: String, maxLines: Int = 100) {
        val current = _logs.value
        val next = if (current.size >= maxLines) {
            listOf(line)
        } else {
            current + line
        }
        _logs.value = next
    }

    @Synchronized
    fun clearLogs() {
        _logs.value = emptyList()
    }
}
