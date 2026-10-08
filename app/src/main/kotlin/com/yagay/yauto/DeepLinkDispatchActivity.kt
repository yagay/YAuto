package com.yagay.yauto

import android.app.Activity
import android.os.Bundle
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DeepLinkDispatchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            // External URLs are untrusted. Avoid allocating very large event payloads.
            if (uri.toString().length > 8192) {
                finish()
                return
            }
            AutomationRuntimeService.start(this)
            val graph = runCatching { (application as YAutoApplication).graph }
                .onFailure { StartupFailureRecorder.record(this, "deep-link:graph", it) }
                .getOrNull()
            if (graph != null) {
                val params = buildMap<String, ConfigValue> {
                    put("uri", ConfigValue.StringValue(uri.toString()))
                    put("scheme", ConfigValue.StringValue(uri.scheme.orEmpty()))
                    // A URI such as yauto:run is opaque. Path and query access on an
                    // opaque Android Uri can throw UnsupportedOperationException.
                    put("host", ConfigValue.StringValue(if (uri.isHierarchical) uri.host.orEmpty() else ""))
                    put("path", ConfigValue.StringValue(if (uri.isHierarchical) uri.path.orEmpty() else ""))
                    put("fragment", ConfigValue.StringValue(uri.fragment.orEmpty()))
                    if (uri.isHierarchical) {
                        runCatching {
                            uri.queryParameterNames.take(64).forEach { name ->
                                put("query." + name.take(128), ConfigValue.StringValue(
                                    uri.getQueryParameter(name).orEmpty().take(4096)
                                ))
                            }
                        }
                    }
                }
                RuntimeEventDispatcher(graph, scope).dispatch(
                    RuntimeEvent(
                        "android.event.deep_link",
                        params,
                        source = "android.deep_link",
                    )
                )
            }
        }
        finish()
    }
}
