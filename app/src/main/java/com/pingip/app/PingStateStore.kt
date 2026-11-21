package com.pingip.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PingStats(
    val startTimeMs: Long = 0L,
    val toggleCount: Int = 0,
    val lastToggleTime: String = "-"
)

object PingStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _statusText = MutableStateFlow("Berhenti")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _stats = MutableStateFlow(PingStats())
    val stats: StateFlow<PingStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _isPaused.value = false
        }
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun setStatus(status: String) {
        _statusText.value = status
    }

    fun resetStats(startTimeMs: Long) {
        _stats.value = PingStats(startTimeMs = startTimeMs, toggleCount = 0, lastToggleTime = "-")
    }

    fun recordToggle(timeStr: String): Int {
        val cur = _stats.value
        val nextCount = cur.toggleCount + 1
        _stats.value = cur.copy(toggleCount = nextCount, lastToggleTime = timeStr)
        return nextCount
    }

    @Synchronized
    fun appendLog(line: String, maxLines: Int = 100) {
        val current = _logs.value
        val limit = maxLines.coerceAtLeast(20)
        val next = if (current.size >= limit) {
            current.drop(current.size - limit + 1) + line
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
