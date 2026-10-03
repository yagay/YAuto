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
    private var cachedForeground: ForegroundAppSnapshot? = null
    private var lastSuccessfulQueryEndEpochMs: Long? = null

    override fun hasAccess(): Boolean = isUsageStatsAccessGranted(context)

    @Synchronized
    override fun currentForegroundApp(nowEpochMs: Long): ForegroundAppSnapshot? {
        if (!hasAccess()) {
            cachedForeground = null
            lastSuccessfulQueryEndEpochMs = null
            return null
        }

        val begin = (
            lastSuccessfulQueryEndEpochMs
                ?.minus(QUERY_OVERLAP_MS)
                ?: nowEpochMs.minus(INITIAL_LOOKBACK_MS)
            ).coerceAtLeast(0L)
            .coerceAtMost(nowEpochMs)

        val records = runCatching {
            val events = manager.queryEvents(begin, nowEpochMs)
            val event = UsageEvents.Event()
            buildList {
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
        }.getOrNull() ?: return cachedForeground

        cachedForeground = resolveForegroundApp(records, cachedForeground)
        lastSuccessfulQueryEndEpochMs = nowEpochMs
        return cachedForeground
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1_000L
        private const val QUERY_OVERLAP_MS = 2_000L
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

internal fun resolveForegroundApp(
    records: List<UsageActivityRecord>,
    seed: ForegroundAppSnapshot? = null,
): ForegroundAppSnapshot? {
    data class ActivityKey(val packageName: String, val className: String?)

    val active = linkedMapOf<ActivityKey, ForegroundAppSnapshot>()
    seed?.let { active[ActivityKey(it.packageName, it.className)] = it }

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
