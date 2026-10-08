package com.yagay.yauto.platform.android

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class BiometricPromptActivity : Activity() {
    private var cancellation: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "YAuto authentication" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val allowCredential = intent.getBooleanExtra(EXTRA_ALLOW_CREDENTIAL, true)
        val builder = BiometricPrompt.Builder(this)
            .setTitle(title)
        if (subtitle.isNotBlank()) builder.setSubtitle(subtitle)
        if (allowCredential) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            builder.setNegativeButton("Cancel", mainExecutor) { _, _ ->
                BiometricRuntime.complete(token, false, "cancelled")
                finish()
            }
        }
        val prompt = builder.build()
        cancellation = CancellationSignal()
        prompt.authenticate(
            cancellation!!,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    BiometricRuntime.complete(token, true, "success")
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    BiometricRuntime.complete(token, false, "error:$errorCode:${errString?.toString().orEmpty()}")
                    finish()
                }

                override fun onAuthenticationFailed() {
                    BiometricRuntime.noteFailure(token)
                }
            }
        )
    }

    override fun onDestroy() {
        cancellation?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_ALLOW_CREDENTIAL = "allowCredential"
    }
}

private object BiometricRuntime {
    data class Result(val success: Boolean, val reason: String)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Result>>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    fun create(): Pair<String, CompletableDeferred<Result>> {
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result>()
        requests[token] = deferred
        failureCounts[token] = 0
        return token to deferred
    }

    fun noteFailure(token: String) {
        failureCounts.compute(token) { _, current -> (current ?: 0) + 1 }
    }

    fun complete(token: String, success: Boolean, reason: String) {
        val failures = failureCounts.remove(token) ?: 0
        requests.remove(token)?.complete(Result(success, if (failures > 0) "$reason;failedAttempts=$failures" else reason))
    }

    fun remove(token: String) {
        requests.remove(token)
        failureCounts.remove(token)
    }
}

class AndroidBiometricFeaturePack(private val context: android.content.Context) : FeaturePack {
    override val id: String = "android.biometric"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.biometric.authenticate"), FeatureKind.ACTION,
                "Authenticate user", "Show Android biometric/device-credential authentication and wait for its result",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("title", "Prompt title"),
                    FieldSchema.Text("subtitle", "Prompt subtitle"),
                    FieldSchema.Toggle("allowDeviceCredential", "Allow PIN/pattern/password"),
                    FieldSchema.Duration("timeoutMs", "Authentication timeout"),
                    FieldSchema.Variable("resultVariable", "Store authentication object"),
                ),
                keywords = setOf("biometric", "fingerprint", "face", "authenticate", "credential", "unlock"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val (token, deferred) = BiometricRuntime.create()
            val title = feature.config.string("title").resolveVariables(ctx.variables).ifBlank { "YAuto authentication" }
            val subtitle = feature.config.string("subtitle").resolveVariables(ctx.variables)
            val allowCredential = feature.config.boolean("allowDeviceCredential", true)
            val timeout = ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 60_000.0).toLong().coerceIn(5_000L, 300_000L)
            val launched = runCatching {
                context.startActivity(
                    android.content.Intent(context, BiometricPromptActivity::class.java)
                        .putExtra(BiometricPromptActivity.EXTRA_TOKEN, token)
                        .putExtra(BiometricPromptActivity.EXTRA_TITLE, title)
                        .putExtra(BiometricPromptActivity.EXTRA_SUBTITLE, subtitle)
                        .putExtra(BiometricPromptActivity.EXTRA_ALLOW_CREDENTIAL, allowCredential)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (!launched) {
                BiometricRuntime.remove(token)
                return@registerAction ActionExecutionResult(false, message = userText("feature.biometric_launch_failed"))
            }
            val result = withTimeoutOrNull(timeout) { deferred.await() }
            BiometricRuntime.remove(token)
            if (result == null) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.biometric_timeout"))
            }
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "success" to ConfigValue.BooleanValue(result.success),
                    "reason" to ConfigValue.StringValue(result.reason),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(result.success, output, if (result.success) null else userText("feature.operation_failed", result.reason))
        }
    }
}
