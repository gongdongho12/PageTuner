package com.dongholab.pagetuner.server.account

import org.springframework.stereotype.Component

/** Bounded, process-local abuse control. Reverse-proxy headers never choose the rate-limit key. */
@Component
class AccountAttempts {
    private data class Window(val until: Long, var count: Int)
    private val registrations = mutableMapOf<String, Window>()
    private val failures = mutableMapOf<String, Window>()
    @Synchronized fun register(address: String): Boolean = increment(registrations, address, 10, 30 * 60_000L)
    @Synchronized fun canAuthenticate(address: String): Boolean {
        purge(failures)
        return (failures[address]?.count ?: 0) < 20
    }
    @Synchronized fun failed(address: String) { increment(failures, address, 20, 15 * 60_000L) }
    private fun increment(windows: MutableMap<String, Window>, key: String, max: Int, duration: Long): Boolean {
        purge(windows)
        if (key !in windows && windows.size >= 2000) return false
        val window = windows.getOrPut(key) { Window(System.currentTimeMillis() + duration, 0) }
        if (window.count >= max) return false
        window.count++
        return true
    }
    private fun purge(windows: MutableMap<String, Window>) { windows.entries.removeIf { it.value.until <= System.currentTimeMillis() } }
}
