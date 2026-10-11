package com.yagay.yauto.core.runtime

/**
 * Counts distinct POWER key releases within a sliding 10-second window.
 * Android's POWER KeyCode is 26. Some OEMs do not forward it to hooks;
 * callers must not present this as a guaranteed hardware kill switch.
 */
class EmergencyPowerKeyDetector(
    private val threshold: Int = 12,
    private val windowMs: Long = 10_000L,
) {
    private val releases = ArrayDeque<Long>()
    @Synchronized fun accept(keyCode: Int, action: String, repeatCount: Int, nowMs: Long): Boolean {
        if (keyCode != 26 || action != "up" || repeatCount > 0) return false
        while (releases.isNotEmpty() && nowMs - releases.first() > windowMs) {
            releases.removeFirst()
        }
        releases.addLast(nowMs)
        if (releases.size < threshold) return false
        releases.clear()
        return true
    }
}
