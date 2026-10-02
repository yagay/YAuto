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
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.LsposedLogCollector
import com.yagay.yauto.platform.xposed.XposedBackend

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val features = FeatureRegistry()
    val importers = ImporterRegistry()
    val diagnosticRegistry = DiagnosticRegistry()
    val traceStore = InMemoryExecutionTracer()
    private val persistentTracer = FileExecutionTracer(appContext)
    val tracer: ExecutionTracer = SequencedExecutionTracer(CompositeExecutionTracer(listOf(traceStore, persistentTracer)))

    val rootShell by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RootShell() }
    val shizuku by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ShizukuBackend(appContext) }
    val xposed by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { XposedBackend(appContext) }
    val accessibility by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AccessibilityBackend() }
    val workspace = JsonWorkspaceRepository(appContext)
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

    init {
        safely("features.standard.catalog") { StandardFeaturePacks.all() }
            .orEmpty()
            .forEach { pack -> installPack("feature:${pack.id}") { pack } }

        installPack("feature:android.base") { AndroidFeaturePack(appContext) }
        installPack("feature:android.communication") { AndroidCommunicationFeaturePack(appContext) }
        installPack("feature:android.communication.events") { AndroidCommunicationEventFeaturePack(appContext) }
        installPack("feature:android.control") { AndroidControlFeaturePack(appContext) }
        installPack("feature:android.package.query") { AndroidPackageQueryFeaturePack(appContext) }
        installPack("feature:android.app.management") { AndroidAppManagementFeaturePack(appContext) }
        installPack("feature:android.advanced.system") { AndroidAdvancedSystemFeaturePack(appContext) }
        installPack("feature:android.system.convenience") { AndroidSystemConvenienceFeaturePack(appContext) }
        installPack("feature:android.privileged.utility") { AndroidPrivilegedUtilityFeaturePack(appContext) }
        installPack("feature:android.privileged.state") { AndroidPrivilegedStateFeaturePack() }
        installPack("feature:android.media.device") { AndroidMediaDeviceFeaturePack(appContext) }
        installPack("feature:android.audio") { AndroidAudioFeaturePack(appContext) }
        installPack("feature:android.media.transport") { AndroidMediaTransportFeaturePack(appContext) }
        installPack("feature:android.speech") { AndroidSpeechFeaturePack(appContext) }
        installPack("feature:android.playback") { AndroidPlaybackFeaturePack(appContext) }
        installPack("feature:android.organizer") { AndroidOrganizerFeaturePack(appContext) }
        installPack("feature:android.device.data") { AndroidDeviceDataFeaturePack(appContext) }
        installPack("feature:android.device.utility") { AndroidDeviceUtilityFeaturePack(appContext) }
        installPack("feature:android.resource.state") { AndroidResourceStateFeaturePack(appContext) }
        installPack("feature:android.connectivity") { AndroidConnectivityFeaturePack(appContext) }
        installPack("feature:android.bluetooth.device") { AndroidBluetoothDeviceFeaturePack(appContext) }
        installPack("feature:android.wifi.detail") { AndroidWifiDetailFeaturePack(appContext) }
        installPack("feature:android.network.utility") { AndroidNetworkUtilityFeaturePack() }
        installPack("feature:android.network.profile.events") { AndroidNetworkProfileEventFeaturePack() }
        installPack("feature:android.bluetooth.audio.events") { AndroidBluetoothAudioEventFeaturePack() }
        installPack("feature:android.location.radius") { AndroidLocationRadiusFeaturePack(appContext) }
        installPack("feature:android.location.events") { AndroidLocationEventFeaturePack() }
        installPack("feature:android.nfc") { AndroidNfcFeaturePack(appContext) }
        installPack("feature:android.midi") { AndroidMidiFeaturePack(appContext) }
        installPack("feature:android.http") { AndroidHttpFeaturePack() }
        installPack("feature:android.file") { AndroidFileFeaturePack() }
        installPack("feature:android.archive") { AndroidArchiveFeaturePack() }
        installPack("feature:android.content.utility") { AndroidContentUtilityFeaturePack(appContext) }
        installPack("feature:android.event") { AndroidEventFeaturePack() }
        installPack("feature:android.state") { AndroidStateFeaturePack(appContext) }
        installPack("feature:android.notification.control") { AndroidNotificationControlFeaturePack() }
        installPack("feature:android.sensor") { AndroidSensorFeaturePack() }
        installPack("feature:android.shortcut") { AndroidShortcutFeaturePack(appContext) }
        installPack("feature:android.quick.settings") { AndroidQuickSettingsFeaturePack(quickSettingsTiles) }
        installPack("feature:android.surface") { AndroidSurfaceFeaturePack(overlaySurfaces) }
        installPack("feature:android.external.command") { AndroidExternalCommandFeaturePack(appContext) }
        installPack("feature:accessibility") { AccessibilityFeaturePack() }
        installPack("feature:accessibility.key") { AccessibilityKeyFeaturePack() }

        safelyUnit("backend:root") { capabilities.register(RootBackend(rootShell)) }
        safelyUnit("backend:shizuku") { capabilities.register(shizuku) }
        safelyUnit("backend:lsposed") { capabilities.register(xposed) }
        safelyUnit("backend:accessibility") { capabilities.register(accessibility) }

        safelyUnit("importer:macrodroid") {
            importers.register(MacroDroidImporter(mapper = MacroDroidFeatureSuggestions.mapper))
        }
        safelyUnit("importer:shortx") { importers.register(EnhancedShortXImporter()) }
        safelyUnit("importer:tasker") { importers.register(TaskerImporter()) }

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
