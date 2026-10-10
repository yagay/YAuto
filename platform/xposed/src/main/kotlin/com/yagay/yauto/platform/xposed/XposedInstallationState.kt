package com.yagay.yauto.platform.xposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Shared process-local state for all Hook installer families. */
internal class XposedInstallationState {
    val systemRegistered = AtomicBoolean(false)
    val appReceivers = ConcurrentHashMap.newKeySet<String>()
    val systemEventDedup = ConcurrentHashMap<String, Long>()
    val hardwareKeyEventDedup = ConcurrentHashMap<String, Long>()
    val hardwareKeyCaptureUntilElapsed = AtomicLong(0L)
    val subscribedSystemEvents = AtomicReference<Set<String>>(emptySet())
    val enabledShortXBehaviors = AtomicReference<Set<String>>(emptySet())
    val enabledPackageBehaviors = AtomicReference<Set<String>>(emptySet())
    val methodSessions = MethodHookSessionRegistry()
    val crashGuards = ConcurrentHashMap<String, HookCrashGuard>()
    val yAutoUid = AtomicLong(-1L)
}
