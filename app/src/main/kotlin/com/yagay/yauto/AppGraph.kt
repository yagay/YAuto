package com.yagay.yauto

import android.content.Context
import android.os.Build
import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.importer.ImporterRegistry
import com.yagay.yauto.core.logging.*
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.runtime.AutomationRuntime
import com.yagay.yauto.core.runtime.ReconcilingWorkspaceRepository
import com.yagay.yauto.feature.standard.StandardFeaturePacks
import com.yagay.yauto.platform.accessibility.AccessibilityBackend
import com.yagay.yauto.platform.accessibility.AccessibilityDiagnosticCollector
import com.yagay.yauto.platform.accessibility.AccessibilityFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityKeyFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityInspectorFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityContinuousPointerFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityBeanShellFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityMvelFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityWindowSnapshot
import com.yagay.yauto.platform.android.*
import com.yagay.yauto.platform.root.*
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.LsposedLogCollector
import com.yagay.yauto.platform.xposed.XposedBackend

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val features = FeatureRegistry()
    /**
     * Compatibility importers are described in a separate lazy catalog so AppGraph remains
     * independent from third-party parser implementations and cold-start construction stays off.
     */
    val importers = ImporterRegistry()
    val diagnosticRegistry = DiagnosticRegistry()
    val traceStore = InMemoryExecutionTracer()
    private val persistentTracer = FileExecutionTracer(
        appContext,
        maxBytesProvider = { RuntimeSettingsPreferences.logSizeMb(appContext).toLong() * 1024L * 1024L },
    )
    private val sequencedTracer: ExecutionTracer =
        SequencedExecutionTracer(CompositeExecutionTracer(listOf(traceStore, persistentTracer)))
    /** New severity preference takes effect on the next recorded event; errors are never lost. */
    val tracer: ExecutionTracer = object : ExecutionTracer {
        override suspend fun record(event: TraceEvent) {
            if (event.level >= RuntimeSettingsPreferences.traceLevel(appContext)) {
                sequencedTracer.record(event)
            }
        }
    }

    suspend fun clearExecutionLogs() {
        persistentTracer.clear()
        traceStore.clear()
    }

    val rootShell by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RootShell() }
    val hardwareKeys by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DeviceHardwareKeyCatalog(rootShell) }
    val shizuku by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ShizukuBackend(appContext) }
    val xposed by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { XposedBackend(appContext) }
    val lsposedScopes = LsposedScopeManager()
    val accessibility by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AccessibilityBackend() }
    private val usageForeground by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SystemUsageStatsForegroundReader(appContext) }
    private val workspaceStorage = JsonWorkspaceRepository(appContext)
    val workspace = ReconcilingWorkspaceRepository(workspaceStorage, features)
    val importReports = JsonImportReportStore(appContext)
    val quickSettingsTiles by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { QuickSettingsTileController(appContext) }
    val overlaySurfaces by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OverlaySurfaceController(appContext) }

    val capabilities = CapabilityBroker(environmentProvider = {
        RuntimeEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
        )
    })
    val diagnostics = DiagnosticCoordinator(diagnosticRegistry)
    val runtime = AutomationRuntime(workspace, features, capabilities, tracer)
    val featureHealth by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FeatureHealthScanner(
            context = appContext,
            descriptors = features::allDescriptors,
            rootShell = rootShell,
            shizuku = shizuku,
            xposed = xposed,
            accessibility = accessibility,
            implementationAvailable = { descriptor ->
                when (descriptor.kind) {
                    com.yagay.yauto.core.registry.FeatureKind.ACTION ->
                        features.actionExecutor(descriptor.id.value) != null
                    com.yagay.yauto.core.registry.FeatureKind.CONDITION ->
                        features.conditionEvaluator(descriptor.id.value) != null
                    com.yagay.yauto.core.registry.FeatureKind.EVENT ->
                        features.eventMatcher(descriptor.id.value) != null
                    com.yagay.yauto.core.registry.FeatureKind.STATE ->
                        features.stateEvaluator(descriptor.id.value) != null
                }
            },
        )
    }

    init {
        safelyUnit("importers:compatibility") {
            CompatibilityImporterCatalog.registerInto(importers)
        }

        installCatalog("features.standard.catalog") { StandardFeaturePacks.all(runtime, runtime) }
        installCatalog("features.android.catalog") {
            AndroidFeaturePacks.all(appContext, quickSettingsTiles, overlaySurfaces, workspace)
        }
        installPack("feature:accessibility") {
            AccessibilityFeaturePack {
                usageForeground.currentForegroundApp()?.let { current ->
                    AccessibilityWindowSnapshot(
                        packageName = current.packageName,
                        className = current.className,
                        timestampEpochMs = current.timestampEpochMs,
                    )
                }
            }
        }
        installPack("feature:accessibility.key") { AccessibilityKeyFeaturePack() }
        installPack("feature:accessibility.inspectors") { AccessibilityInspectorFeaturePack(appContext) }
        installPack("feature:accessibility.pointer") { AccessibilityContinuousPointerFeaturePack(appContext) }
        installPack("feature:accessibility.beanshell") { AccessibilityBeanShellFeaturePack(appContext) }
        installPack("feature:accessibility.mvel") { AccessibilityMvelFeaturePack(appContext) }

        safelyUnit("backend:root") { capabilities.register(RootBackend(rootShell)) }
        safelyUnit("backend:shizuku") { capabilities.register(shizuku) }
        safelyUnit("backend:lsposed") { capabilities.register(xposed) }
        safelyUnit("backend:accessibility") { capabilities.register(accessibility) }

        safelyUnit("diagnostic:execution-files") {
            diagnosticRegistry.register(ExecutionFileDiagnosticCollector(appContext))
        }
        safelyUnit("diagnostic:import-reports") {
            diagnosticRegistry.register(ImportReportDiagnosticCollector(appContext))
        }
        safelyUnit("diagnostic:notification-access") {
            diagnosticRegistry.register(NotificationAccessDiagnosticCollector(appContext))
        }
        safelyUnit("diagnostic:accessibility") {
            diagnosticRegistry.register(AccessibilityDiagnosticCollector())
        }
        safelyUnit("diagnostic:root") { diagnosticRegistry.register(RootDiagnosticCollector(rootShell)) }
        safelyUnit("diagnostic:lsposed-log") { diagnosticRegistry.register(LsposedLogCollector(rootShell)) }
        safelyUnit("diagnostic:shizuku") { diagnosticRegistry.register(shizuku) }
        safelyUnit("diagnostic:xposed") { diagnosticRegistry.register(xposed) }
    }

    private fun installCatalog(component: String, factory: () -> List<FeaturePack>) {
        safely(component, factory).orEmpty().forEach { pack ->
            installPack("feature:${pack.id}") { pack }
        }
    }

    private fun installPack(component: String, factory: () -> FeaturePack) {
        var pack: FeaturePack? = null
        try {
            pack = factory()
            features.install(pack)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            pack?.let { runCatching { features.uninstallPack(it.id) } }
            StartupFailureRecorder.record(appContext, component, error)
        }
    }

    private fun safelyUnit(component: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            StartupFailureRecorder.record(appContext, component, error)
        }
    }

    private fun <T> safely(component: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        StartupFailureRecorder.record(appContext, component, error)
        null
    }
}
