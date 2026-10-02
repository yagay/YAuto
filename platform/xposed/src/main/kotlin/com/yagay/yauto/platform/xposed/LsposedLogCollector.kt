package com.yagay.yauto.platform.xposed

import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.diagnostics.*

class LsposedLogCollector(
    private val runner: DiagnosticCommandRunner,
) : DiagnosticCollector {
    override val id = "lsposed.logs"

    override suspend fun status(): CollectorStatus {
        val out = runner.run("test -d /data/adb/lspd && echo yes || true", 4_000)
        val available = "yes" in out.stdout
        return CollectorStatus(id, available, if (available) userText("diagnostics.lsposed.readable") else userText("diagnostics.lsposed.unreadable"))
    }

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> {
        val command = """
            if [ -d /data/adb/lspd/log ]; then
              for f in /data/adb/lspd/log/*.log /data/adb/lspd/log/*/*.log; do
                [ -f "${'$'}f" ] || continue
                echo "===== ${'$'}f ====="
                tail -n 1000 "${'$'}f"
              done
            fi
        """.trimIndent()
        val out = runner.run(command, 20_000)
        if (out.stdout.isBlank() && out.stderr.isBlank()) return emptyList()
        return listOf(
            DiagnosticRecord(
                source = DiagnosticSource.LSPOSED,
                timestampEpochMs = System.currentTimeMillis(),
                severity = if (out.exitCode == 0) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                title = userText("diagnostics.lsposed.logs"),
                message = (out.stdout + "\n" + out.stderr).trim().take(500_000),
                context = context,
            )
        )
    }
}
