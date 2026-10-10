package com.yagay.yauto.platform.xposed

import android.content.Intent

/** Stateless snapshots shared by system-server hook observers. */
internal fun processSnapshot(target: Any?): ProcessSnapshot {
        if (target == null) return ProcessSnapshot()
        val info = reflectedValue(target, "info", "mInfo")
        val packageName = reflectedString(info, "packageName")
            .ifBlank { reflectedString(target, "packageName", "mPackageName") }
        return ProcessSnapshot(
            packageName = packageName,
            processName = reflectedString(target, "processName", "mProcessName"),
            uid = reflectedInt(target, "uid", "mUid"),
            pid = reflectedInt(target, "pid", "mPid"),
        )
    }

internal fun taskSnapshot(target: Any?): TaskSnapshot {
        if (target == null) return TaskSnapshot()
        val taskId = reflectedInt(target, "mTaskId", "taskId")
        val component = reflectedValue(target, "realActivity", "origActivity", "mRealActivity") as? android.content.ComponentName
        val intent = reflectedValue(target, "intent", "mIntent") as? Intent
        val resolved = component ?: intent?.component
        return TaskSnapshot(
            taskId = taskId,
            packageName = resolved?.packageName.orEmpty(),
            activityName = resolved?.className.orEmpty(),
        )
    }

internal fun activitySnapshot(target: Any?): ActivitySnapshot {
        if (target == null) return ActivitySnapshot()
        val component = reflectedValue(target, "mActivityComponent", "realActivity") as? android.content.ComponentName
            ?: (reflectedValue(target, "intent", "mIntent") as? Intent)?.component
        val info = reflectedValue(target, "info", "mActivityInfo")
        return ActivitySnapshot(
            packageName = component?.packageName
                ?: reflectedString(target, "packageName", "mPackageName")
                    .ifBlank { reflectedString(info, "packageName") },
            activityName = component?.className
                ?: reflectedString(info, "name"),
            taskId = reflectedInt(reflectedValue(target, "task", "mTask"), "mTaskId", "taskId"),
        )
    }

internal fun notificationSnapshot(target: Any?): NotificationSnapshot {
        if (target == null) return NotificationSnapshot()
        val sbn = reflectedValue(target, "sbn", "mSbn") ?: runCatching {
            target.javaClass.methods.firstOrNull { it.name == "getSbn" && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull()
        val notification = reflectedValue(sbn, "notification", "mNotification")
        return NotificationSnapshot(
            packageName = reflectedString(sbn, "pkg", "packageName", "mPackageName"),
            key = reflectedString(sbn, "key", "mKey"),
            id = reflectedInt(sbn, "id", "mId"),
            channelId = reflectedString(notification, "mChannelId", "channelId"),
        )
    }

internal data class ActivitySnapshot(
        val packageName: String = "",
        val activityName: String = "",
        val taskId: Int = -1,
    )

internal data class NotificationSnapshot(
        val packageName: String = "",
        val key: String = "",
        val id: Int = -1,
        val channelId: String = "",
    )

internal data class ProcessSnapshot(
        val packageName: String = "",
        val processName: String = "",
        val uid: Int = -1,
        val pid: Int = -1,
    )

internal data class TaskSnapshot(
        val taskId: Int = -1,
        val packageName: String = "",
        val activityName: String = "",
    )

