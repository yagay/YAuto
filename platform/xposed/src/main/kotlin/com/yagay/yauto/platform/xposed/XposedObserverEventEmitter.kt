package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent
import android.os.Binder

/**
 * Owns ShortX observer event payloads and identities. Hook installation stays in
 * the module; this component has no LSPosed API dependency.
 */
internal class XposedObserverEventEmitter(
    private val state: XposedInstallationState,
    private val publish: (Context, String, String, Map<String, Any?>, Long) -> Unit,
) {
    private fun emitSystemRuntimeEvent(
        context: Context,
        type: String,
        dedupKey: String,
        extras: Map<String, Any?>,
        dedupWindowMs: Long,
    ) = publish(context, type, dedupKey, extras, dedupWindowMs)

    fun emit(
        context: Context,
        spec: ShortXObserverHookSpec,
        className: String,
        methodName: String,
        thisObject: Any?,
        args: List<Any?>,
    ) {
        if (spec.eventType !in state.subscribedSystemEvents.get()) return
        val extras = linkedMapOf<String, Any?>(
            "hookId" to spec.id,
            "className" to className,
            "method" to methodName,
        )
        if (spec.id == "clipboard-read") {
            extras["callingUid"] = Binder.getCallingUid()
        }
        args.take(8).forEachIndexed { index, value ->
            when (value) {
                is String -> extras["arg$index"] = value.take(512)
                is Int -> extras["arg$index"] = value
                is Long -> extras["arg$index"] = value
                is Boolean -> extras["arg$index"] = value
                is Enum<*> -> extras["arg$index"] = value.name
            }
        }

        when (spec.payloadKind) {
            ShortXHookPayloadKind.PROCESS -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("ProcessRecord") == true } ?: thisObject
                val snapshot = processSnapshot(target)
                extras["package"] = snapshot.packageName
                extras["processName"] = snapshot.processName
                extras["uid"] = snapshot.uid
                extras["pid"] = snapshot.pid
            }
            ShortXHookPayloadKind.ACTIVITY -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("ActivityRecord") == true } ?: thisObject
                val snapshot = activitySnapshot(target)
                extras["package"] = snapshot.packageName
                extras["activity"] = snapshot.activityName
                extras["taskId"] = snapshot.taskId
            }
            ShortXHookPayloadKind.TASK -> {
                val target = args.firstOrNull { it?.javaClass?.name == "com.android.server.wm.Task" }
                    ?: args.firstOrNull { it?.javaClass?.name?.contains("Task") == true }
                val snapshot = taskSnapshot(target)
                extras["package"] = snapshot.packageName
                extras["activity"] = snapshot.activityName
                extras["taskId"] = snapshot.taskId
            }
            ShortXHookPayloadKind.NOTIFICATION -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("NotificationRecord") == true } ?: thisObject
                val snapshot = notificationSnapshot(target)
                extras["package"] = snapshot.packageName
                extras["notificationKey"] = snapshot.key
                extras["notificationId"] = snapshot.id
                extras["channel"] = snapshot.channelId
            }
            ShortXHookPayloadKind.VPN -> {
                extras["state"] = args.firstOrNull { it is Enum<*> || it is String }?.toString().orEmpty()
                extras["reason"] = args.drop(1).firstOrNull { it is String }?.toString().orEmpty()
            }
            ShortXHookPayloadKind.IME -> {
                val editor = args.firstOrNull { it?.javaClass?.name == "android.view.inputmethod.EditorInfo" }
                extras["package"] = reflectedString(editor, "packageName")
                extras["fieldId"] = reflectedInt(editor, "fieldId")
            }
            ShortXHookPayloadKind.WINDOW -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("WindowState") == true } ?: thisObject
                extras["package"] = reflectedString(target, "mOwningPackage", "owningPackage")
                val attrs = reflectedValue(target, "mAttrs", "attrs")
                extras["title"] = reflectedValue(attrs, "title")?.toString().orEmpty()
            }
            ShortXHookPayloadKind.ROTATION -> {
                extras["rotation"] = args.firstOrNull { it is Int } as? Int ?: -1
            }
            ShortXHookPayloadKind.WIDGET -> {
                extras["package"] = args.firstOrNull { it is String }?.toString().orEmpty()
                extras["hostId"] = args.firstOrNull { it is Int } as? Int ?: -1
            }
            ShortXHookPayloadKind.SHORTCUT -> {
                val strings = args.filterIsInstance<String>()
                extras["package"] = strings.firstOrNull().orEmpty()
                extras["shortcutId"] = strings.getOrNull(1).orEmpty()
            }
            ShortXHookPayloadKind.STATUS_BAR_ICON -> {
                extras["slot"] = args.firstOrNull { it is String }?.toString().orEmpty()
            }
            ShortXHookPayloadKind.INTENT_START -> {
                val intent = args.firstOrNull { it is Intent } as? Intent
                    ?: args.asSequence().mapNotNull { reflectedValue(it, "intent", "mIntent") as? Intent }.firstOrNull()
                extras["package"] = intent?.component?.packageName.orEmpty()
                extras["activity"] = intent?.component?.className.orEmpty()
                extras["action"] = intent?.action.orEmpty()
            }
            ShortXHookPayloadKind.SCREEN_STATE -> {
                extras["screenOn"] = args.firstOrNull { it is Boolean } as? Boolean ?: false
            }
            ShortXHookPayloadKind.APP_OPS -> {
                // AppOpsService variants can have IBinder/AttributionSource or
                // wrapper arguments. Expose only demonstrably typed primitives;
                // never invent an app package when the signature is unknown.
                extras["phase"] = if (methodName.startsWith("start")) "start" else "finish"
                extras["callingUid"] = Binder.getCallingUid()
                extras["opCode"] = args.filterIsInstance<Int>().firstOrNull() ?: -1
                extras["package"] = args.filterIsInstance<String>()
                    .firstOrNull { it.contains('.') && it.length <= 255 }.orEmpty()
            }
            ShortXHookPayloadKind.BACK_PRESS,
            ShortXHookPayloadKind.NONE -> Unit
        }

        val identity = XposedObserverEventIdentity.from(spec.id, extras)
        emitSystemRuntimeEvent(
            context = context,
            type = spec.eventType,
            dedupKey = identity,
            extras = extras,
            // AppOps wrapper/Impl callbacks may describe the same operation.
            // De-duplicate identical phase+UID+package+op within 75ms while
            // preserving start and finish as separate event identities.
            dedupWindowMs = if (spec.payloadKind == ShortXHookPayloadKind.APP_OPS) 75L else 150L,
        )
    }
}

/** Preserves the original observer de-duplication identity across hook families. */
internal object XposedObserverEventIdentity {
    fun from(specId: String, extras: Map<String, Any?>): String = buildList {
        add(specId)
        add(extras["package"]?.toString().orEmpty())
        add(extras["activity"]?.toString().orEmpty())
        add(extras["processName"]?.toString().orEmpty())
        add(extras["state"]?.toString().orEmpty())
        if (specId.startsWith("app-ops-")) {
            // Different ops/UIDs must never collapse into one event.
            add(extras["opCode"]?.toString().orEmpty())
            add(extras["callingUid"]?.toString().orEmpty())
        }
    }.joinToString(":")
}
