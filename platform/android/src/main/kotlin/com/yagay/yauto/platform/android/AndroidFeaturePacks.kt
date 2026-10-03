package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.registry.FeaturePack

/**
 * Authoritative catalog of YAuto-native Android FeaturePacks.
 *
 * AppGraph installs this catalog as a group and no longer needs to know every concrete Android
 * feature implementation. Adding/removing a native Android capability therefore happens here,
 * while engine, UI, storage and compatibility importers stay unchanged.
 */
object AndroidFeaturePacks {
    fun all(
        context: Context,
        quickSettingsTiles: QuickSettingsTileController,
        overlaySurfaces: OverlaySurfaceController,
    ): List<FeaturePack> = listOf(
        AndroidFeaturePack(context),
        AndroidCommunicationFeaturePack(context),
        AndroidCommunicationEventFeaturePack(context),
        AndroidControlFeaturePack(context),
        AndroidPackageQueryFeaturePack(context),
        AndroidAppManagementFeaturePack(context),
        AndroidAdvancedSystemFeaturePack(context),
        AndroidSystemConvenienceFeaturePack(context),
        AndroidSystemPowerCoverageFeaturePack(context),
        AndroidInteractionCoverageFeaturePack(context),
        AndroidPrivilegedUtilityFeaturePack(context),
        AndroidPrivilegedStateFeaturePack(),
        AndroidMediaDeviceFeaturePack(context),
        AndroidAudioFeaturePack(context),
        AndroidMediaTransportFeaturePack(context),
        AndroidSpeechFeaturePack(context),
        AndroidPlaybackFeaturePack(context),
        AndroidOrganizerFeaturePack(context),
        AndroidDeviceDataFeaturePack(context),
        AndroidDeviceUtilityFeaturePack(context),
        AndroidResourceStateFeaturePack(context),
        AndroidConnectivityFeaturePack(context),
        AndroidBluetoothDeviceFeaturePack(context),
        AndroidWifiDetailFeaturePack(context),
        AndroidNetworkUtilityFeaturePack(),
        AndroidNetworkProfileEventFeaturePack(),
        AndroidBluetoothAudioEventFeaturePack(),
        AndroidLocationRadiusFeaturePack(context),
        AndroidLocationEventFeaturePack(),
        AndroidNfcFeaturePack(context),
        AndroidMidiFeaturePack(context),
        AndroidHttpFeaturePack(),
        AndroidFileFeaturePack(),
        AndroidFileEventFeaturePack(),
        AndroidArchiveFeaturePack(),
        AndroidContentUtilityFeaturePack(context),
        AndroidEventFeaturePack(),
        AndroidIntervalFeaturePack(),
        AndroidStateFeaturePack(context),
        AndroidNotificationControlFeaturePack(),
        AndroidSensorFeaturePack(),
        AndroidShortcutFeaturePack(context),
        AndroidQuickSettingsFeaturePack(quickSettingsTiles),
        AndroidSurfaceFeaturePack(overlaySurfaces),
        AndroidExternalCommandFeaturePack(context),
    )
}
