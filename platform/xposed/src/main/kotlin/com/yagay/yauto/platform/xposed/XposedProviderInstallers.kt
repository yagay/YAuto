package com.yagay.yauto.platform.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ComponentName
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import com.yagay.yauto.core.capability.SystemOperations
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Installs NFC, media, SMS and application input provider hooks. */
abstract class XposedProviderInstallers : XposedModule() {
    internal val sharedState = XposedInstallationState()
    protected val installedHooks get() = sharedState.installedHooks
    protected fun installShortXNfcHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.nfc.NfcService\$NfcServiceHandler")
        }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name == "dispatchTagEndpoint" }.forEach { method ->
            val key = "shortx-nfc|" + method.toGenericString()
            val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                method.isAccessible = true
            hook(method).intercept { chain ->
                val endpoint = chain.args.firstOrNull {
                    it?.javaClass?.name?.contains("TagEndpoint") == true
                }
                val uid = reflectedValue(endpoint, "uid", "mUid") as? ByteArray
                    ?: runCatching {
                        endpoint?.javaClass?.methods?.firstOrNull {
                            it.name == "getUid" && it.parameterCount == 0
                        }?.invoke(endpoint) as? ByteArray
                    }.getOrNull()
                val payload = mapOf(
                    "kind" to "tag",
                    "uidHex" to (uid?.joinToString("") { byte -> "%02X".format(byte) } ?: ""),
                    "method" to method.name,
                )
                emitPackageRuntimeEvent(context, "android.event.nfc_tag_system", payload)
                emitPackageRuntimeEvent(context, "android.event.nfc_tag", payload)
                chain.proceed()
            }
            }
            installation.exceptionOrNull()?.let { error ->
                log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
            }
        }
    }

    protected fun installShortXMediaProviderHooks(context: Context, classLoader: ClassLoader) {
        runCatching { classLoader.loadClass("com.android.providers.media.MediaProvider") }
            .getOrNull()
            ?.declaredMethods
            ?.filter { it.name == "onCreate" }
            ?.forEach { method ->
                val key = "shortx-media-provider-ready|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.media_provider_ready",
                        mapOf("method" to method.name),
                    )
                    result
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
                }
            }

        val classNames = listOf(
            "com.android.providers.media.MediaProvider",
            "com.android.providers.media.MediaDocumentsProvider",
        )
        classNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { it.name in setOf("insert", "delete", "update") }
                .forEach { method ->
                    val eventType = when (method.name) {
                        "insert" -> "android.event.media_store_inserted"
                        "delete" -> "android.event.media_store_deleted"
                        "update" -> "android.event.media_store_updated"
                        else -> "android.event.media_store_changed"
                    }
                    val key = "shortx-media-provider|" + method.toGenericString()
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                        method.isAccessible = true
                    hook(method).intercept { chain ->
                        val uri = chain.args.firstOrNull { it is android.net.Uri } as? android.net.Uri
                        val result = chain.proceed()
                        emitPackageRuntimeEvent(
                            context,
                            eventType,
                            mapOf(
                                "uri" to uri?.toString().orEmpty(),
                                "collection" to mediaCollection(uri),
                                "method" to method.name,
                            ),
                        )
                        val genericPayload = mapOf(
                            "uri" to uri?.toString().orEmpty(),
                            "collection" to mediaCollection(uri),
                            "operation" to method.name,
                            "method" to method.name,
                        )
                        emitPackageRuntimeEvent(context, "android.event.media_store_changed", genericPayload)
                        emitPackageRuntimeEvent(context, "android.event.media_provider_changed", genericPayload)
                        result
                    }
                    }
                    installation.exceptionOrNull()?.let { error ->
                        log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
                    }
                }
        }
    }

    protected fun installShortXTelephonyProviderHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("com.android.providers.telephony.SmsProvider") }.getOrNull()
            ?: return
        clazz.declaredMethods.filter { it.name == "onCreate" }.forEach { method ->
            val key = "shortx-sms-provider-ready|" + method.toGenericString()
            val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                method.isAccessible = true
            hook(method).intercept { chain ->
                val result = chain.proceed()
                emitPackageRuntimeEvent(
                    context,
                    "android.event.sms_provider_ready",
                    mapOf("method" to method.name),
                )
                result
            }
            }
            installation.exceptionOrNull()?.let { error ->
                log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
            }
        }
        clazz.declaredMethods
            .filter { it.name in setOf("insert", "delete", "update") }
            .forEach { method ->
                val key = "shortx-sms-provider|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                hook(method).intercept { chain ->
                    val uri = chain.args.firstOrNull { it is android.net.Uri } as? android.net.Uri
                    val result = chain.proceed()
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.sms_provider_changed",
                        mapOf(
                            "operation" to method.name,
                            "uri" to uri?.toString().orEmpty(),
                            "method" to method.name,
                        ),
                    )
                    result
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
                }
            }
    }

    protected fun installShortXInputConnectionHook(context: Context, packageName: String, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("android.view.inputmethod.RemoteInputConnectionImpl") }.getOrNull()
            ?: return
        clazz.declaredMethods.filter { it.name == "commitText" }.forEach { method ->
            val key = "shortx-input-connection|" + packageName + "|" + method.toGenericString()
            val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                method.isAccessible = true
            hook(method).intercept { chain ->
                val text = chain.args.firstOrNull { it is CharSequence }?.toString().orEmpty()
                emitPackageRuntimeEvent(
                    context,
                    "android.event.input_text_committed",
                    mapOf(
                        "package" to packageName,
                        "text" to text.take(2048),
                        "method" to method.name,
                    ),
                )
                chain.proceed()
            }
            }
            installation.exceptionOrNull()?.let { error ->
                log(android.util.Log.WARN, "YAuto", "Provider hook unavailable: " + key, error)
            }
        }
    }

}
