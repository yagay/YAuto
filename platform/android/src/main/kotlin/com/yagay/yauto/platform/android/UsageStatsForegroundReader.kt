package com.yagay.yauto.platform.android

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class ForegroundAppSnapshot(
    val packageName: String,
    val className: String? = null,
    val timestampEpochMs: Long,
)

internal enum class UsageActivityTransition { RESUMED, PAUSED, STOPPED }

internal data class UsageActivityRecord(
    val packageName: String,
    val className: String?,
    val timestampEpochMs: Long,
    val transition: UsageActivityTransition,
)

interface ForegroundAppReader {
    fun hasAccess(): Boolean
    fun currentForegroundApp(nowEpochMs: Long = System.currentTimeMillis()): ForegroundAppSnapshot?
}

class SystemUsageStatsForegroundReader(context: Context) : ForegroundAppReader {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsageStatsManager::class.java)

    override fun hasAccess(): Boolean = isUsageStatsAccessGranted(context)

    override fun currentForegroundApp(nowEpochMs: Long): ForegroundAppSnapshot? {
        if (!hasAccess()) return null
        val begin = (nowEpochMs - LOOKBACK_MS).coerceAtLeast(0L)
        return runCatching {
            val events = manager.queryEvents(begin, nowEpochMs)
            val event = UsageEvents.Event()
            val records = buildList {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val transition = when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> UsageActivityTransition.RESUMED
                        UsageEvents.Event.ACTIVITY_PAUSED -> UsageActivityTransition.PAUSED
                        UsageEvents.Event.ACTIVITY_STOPPED -> UsageActivityTransition.STOPPED
                        else -> null
                    } ?: continue
                    val packageName = event.packageName?.trim().orEmpty()
                    if (packageName.isEmpty()) continue
                    add(
                        UsageActivityRecord(
                            packageName = packageName,
                            className = event.className?.takeIf { it.isNotBlank() },
                            timestampEpochMs = event.timeStamp,
                            transition = transition,
                        )
                    )
                }
            }
            resolveForegroundApp(records)
        }.getOrNull()
    }

    companion object {
        private const val LOOKBACK_MS = 60_000L
    }
}

fun isUsageStatsAccessGranted(context: Context): Boolean = runCatching {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

internal fun resolveForegroundApp(records: List<UsageActivityRecord>): ForegroundAppSnapshot? {
    data class ActivityKey(val packageName: String, val className: String?)

    val active = linkedMapOf<ActivityKey, ForegroundAppSnapshot>()
    records.sortedBy { it.timestampEpochMs }.forEach { record ->
        val key = ActivityKey(record.packageName, record.className)
        when (record.transition) {
            UsageActivityTransition.RESUMED -> {
                active[key] = ForegroundAppSnapshot(record.packageName, record.className, record.timestampEpochMs)
            }
            UsageActivityTransition.PAUSED,
            UsageActivityTransition.STOPPED -> {
                active.remove(key)
                if (record.className == null) {
                    active.keys.filter { it.packageName == record.packageName }.toList().forEach(active::remove)
                }
            }
        }
    }
    return active.values.maxByOrNull { it.timestampEpochMs }
}
