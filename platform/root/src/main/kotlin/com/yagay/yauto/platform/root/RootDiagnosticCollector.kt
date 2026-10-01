package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.*

class RootDiagnosticCollector(
    private val shell: RootShell,
) : DiagnosticCollector {
    override val id = "root.environment"

    override suspend fun status(): CollectorStatus {
        val available = shell.isAvailable()
        return CollectorStatus(id, available, if (available) "Root shell available" else "Root shell unavailable")
    }

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> {
        if (!shell.isAvailable()) return emptyList()
        val now = System.currentTimeMillis()
        val env = shell.run("id; echo ---; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.manufacturer; getprop ro.product.model; echo ---ROOT---; ls -ld /data/adb/ksu /data/adb/magisk /data/adb/ap 2>/dev/null")
        val logcat = shell.run("logcat -d -v threadtime -t 2500 2>/dev/null | grep -E -i 'YAuto|LSPosed|zygote|SystemUI|system_server|FATAL EXCEPTION|AndroidRuntime|KernelSU|Magisk|APatch|Zygisk' | tail -n 1200", 15_000)
        val kernel = shell.run("dmesg 2>/dev/null | grep -E -i 'KernelSU|ksu|Magisk|APatch|Zygisk|LSPosed|zygote' | tail -n 600", 10_000)
        return listOf(
            DiagnosticRecord(DiagnosticSource.ROOT, now, title = "Root environment", message = (env.stdout + "\n" + env.stderr).trim().take(80_000), context = context),
            DiagnosticRecord(DiagnosticSource.ANDROID, now, title = "Relevant root logcat", message = (logcat.stdout + "\n" + logcat.stderr).trim().take(250_000), context = context),
            DiagnosticRecord(DiagnosticSource.ROOT, now, title = "Root/kernel messages", message = (kernel.stdout + "\n" + kernel.stderr).trim().take(160_000), context = context),
        )
    }
}
