package com.yagay.yauto

import android.content.Context
import android.os.Build
import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.importer.ImporterRegistry
import com.yagay.yauto.core.logging.*
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.runtime.AutomationRuntime
import com.yagay.yauto.feature.standard.StandardFeaturePacks
import com.yagay.yauto.importer.macrodroid.MacroDroidFeatureSuggestions
import com.yagay.yauto.importer.macrodroid.MacroDroidImporter
import com.yagay.yauto.importer.shortx.EnhancedShortXImporter
import com.yagay.yauto.importer.tasker.TaskerImporter
import com.yagay.yauto.platform.accessibility.AccessibilityBackend
import com.yagay.yauto.platform.accessibility.AccessibilityDiagnosticCollector
import com.yagay.yauto.platform.accessibility.AccessibilityFeaturePack
import com.yagay.yauto.platform.accessibility.AccessibilityKeyFeaturePack
import com.yagay.yauto.platform.android.*
import com.yagay.yauto.platform.root.*
import com.yagay.yauto.platform.xposed.LsposedLogCollector
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.XposedBackend

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val features = FeatureRegistry()
    val importers = ImporterRegistry()
    val diagnosticRegistry = DiagnosticRegistry()
    val traceStore = InMemoryExecutionTracer()
    private val persistentTracer = FileExecutionTracer(appContext)
    val tracer: ExecutionTracer = SequencedExecutionTracer(CompositeExecutionTracer(listOf(traceStore, persistentTracer)))
    val rootShell = RootShell()
    val shizuku = ShizukuBackend(appContext)
    val xposed = XposedBackend(appContext)
    val accessibility = AccessibilityBackend()
    val workspace = JsonWorkspaceRepository(appContext)
    val importReports = JsonImportReportStore(appContext)
    val quickSettingsTiles = QuickSettingsTileController(appContext)
    val overlaySurfaces = OverlaySurfaceController(appContext)

    val capabilities = CapabilityBroker(environmentProvider = {
        RuntimeEnvironment(sdkInt = Build.VERSION.SDK_INT, manufacturer = Build.MANUFACTURER, brand = Build.BRAND, model = Build.MODEL)
    })
    val diagnostics = DiagnosticCoordinator(diagnosticRegistry)
    val runtime = AutomationRuntime(workspace, features, capabilities, tracer)

    init {
        StandardFeaturePacks.all().forEach(features::install)
        features.install(AndroidFeaturePack(appContext))
        features.install(AndroidCommunicationFeaturePack(appContext))
        features.install(AndroidCommunicationEventFeaturePack(appContext))
        features.install(AndroidControlFeaturePack(appContext))
        features.install(AndroidPackageQueryFeaturePack(appContext))
        features.install(AndroidAppManagementFeaturePack(appContext))
        features.install(AndroidAdvancedSystemFeaturePack(appContext))
        features.install(AndroidSystemConvenienceFeaturePack(appContext))
        features.install(AndroidPrivilegedUtilityFeaturePack(appContext))
        features.install(AndroidPrivilegedStateFeaturePack())
        features.install(AndroidMediaDeviceFeaturePack(appContext))
        features.install(AndroidAudioFeaturePack(appContext))
        features.install(AndroidMediaTransportFeaturePack(appContext))
        features.install(AndroidSpeechFeaturePack(appContext))
        features.install(AndroidPlaybackFeaturePack(appContext))
        features.install(AndroidOrganizerFeaturePack(appContext))
        features.install(AndroidDeviceDataFeaturePack(appContext))
        features.install(AndroidDeviceUtilityFeaturePack(appContext))
        features.install(AndroidResourceStateFeaturePack(appContext))
        features.install(AndroidConnectivityFeaturePack(appContext))
        features.install(AndroidBluetoothDeviceFeaturePack(appContext))
        features.install(AndroidWifiDetailFeaturePack(appContext))
        features.install(AndroidNetworkUtilityFeaturePack())
        features.install(AndroidNetworkProfileEventFeaturePack())
        features.install(AndroidBluetoothAudioEventFeaturePack())
        features.install(AndroidLocationRadiusFeaturePack(appContext))
        features.install(AndroidLocationEventFeaturePack())
        features.install(AndroidHttpFeaturePack())
        features.install(AndroidFileFeaturePack())
        features.install(AndroidArchiveFeaturePack())
        features.install(AndroidContentUtilityFeaturePack(appContext))
        features.install(AndroidEventFeaturePack())
        features.install(AndroidStateFeaturePack(appContext))
        features.install(AndroidNotificationControlFeaturePack())
        features.install(AndroidSensorFeaturePack())
        features.install(AndroidShortcutFeaturePack(appContext))
        features.install(AndroidQuickSettingsFeaturePack(quickSettingsTiles))
        features.install(AndroidSurfaceFeaturePack(overlaySurfaces))
        features.install(AndroidExternalCommandFeaturePack(appContext))
        features.install(AccessibilityFeaturePack())
        features.install(AccessibilityKeyFeaturePack())

        capabilities.register(RootBackend(rootShell))
        capabilities.register(shizuku)
        capabilities.register(xposed)
        capabilities.register(accessibility)

        importers.register(MacroDroidImporter(mapper = MacroDroidFeatureSuggestions.mapper))
        importers.register(EnhancedShortXImporter())
        importers.register(TaskerImporter())

        diagnosticRegistry.register(ExecutionFileDiagnosticCollector(appContext))
        diagnosticRegistry.register(ImportReportDiagnosticCollector(appContext))
        diagnosticRegistry.register(NotificationAccessDiagnosticCollector(appContext))
        diagnosticRegistry.register(AccessibilityDiagnosticCollector())
        diagnosticRegistry.register(RootDiagnosticCollector(rootShell))
        diagnosticRegistry.register(LsposedLogCollector(rootShell))
        diagnosticRegistry.register(shizuku)
        diagnosticRegistry.register(xposed)
    }
}
