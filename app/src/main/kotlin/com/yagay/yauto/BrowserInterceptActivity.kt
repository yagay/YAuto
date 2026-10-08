package com.yagay.yauto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrowserInterceptActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        AutomationRuntimeService.start(this)
        val graph = runCatching { (application as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(this, "browser-intercept:graph", it) }
            .getOrNull()
        if (graph == null) {
            finish()
            return
        }

        scope.launch {
            RuntimeEventDispatcher(graph, this).dispatch(
                RuntimeEvent(
                    "android.event.browser_intercept",
                    mapOf(
                        "url" to ConfigValue.StringValue(uri.toString()),
                        "scheme" to ConfigValue.StringValue(uri.scheme.orEmpty()),
                        "host" to ConfigValue.StringValue(uri.host.orEmpty()),
                    ),
                    source = "android.browser_intercept",
                )
            )

            val rules = runCatching {
                graph.workspace.load().automations.asSequence()
                    .filter { it.enabled }
                    .flatMap { it.activation.events.asSequence() }
                    .filter { it.typeId == "android.event.browser_intercept" }
                    .toList()
            }.getOrDefault(emptyList())

            val matching = rules.filter { feature ->
                val expected = feature.config.string("urlContains").trim()
                if (expected.isBlank()) true
                else if (feature.config.boolean("regex", false)) {
                    runCatching { Regex(expected).containsMatchIn(uri.toString()) }.getOrDefault(false)
                } else {
                    uri.toString().contains(expected, ignoreCase = true)
                }
            }

            val forwardRule = matching.firstOrNull { it.config.boolean("openUrlAfterTrigger", false) }
            if (forwardRule != null) {
                openInAnotherBrowser(uri, forwardRule.config.string("browserPackage").trim())
            }
            runOnUiThread { finish() }
        }
    }

    private fun openInAnotherBrowser(uri: Uri, preferredPackage: String) {
        val base = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val targetPackage = preferredPackage.takeIf { it.isNotBlank() && it != packageName }
            ?: packageManager.queryIntentActivities(base, 0)
                .asSequence()
                .map { it.activityInfo.packageName }
                .firstOrNull { it != packageName }
        if (targetPackage != null) {
            runCatching { startActivity(Intent(base).setPackage(targetPackage)) }
        }
    }
}
