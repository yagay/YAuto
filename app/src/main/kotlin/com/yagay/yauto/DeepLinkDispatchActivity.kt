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
            AutomationRuntimeService.start(this)
            val graph = runCatching { (application as YAutoApplication).graph }
                .onFailure { StartupFailureRecorder.record(this, "deep-link:graph", it) }
                .getOrNull()
            if (graph != null) {
                val params = buildMap<String, ConfigValue> {
                    put("uri", ConfigValue.StringValue(uri.toString()))
                    put("scheme", ConfigValue.StringValue(uri.scheme.orEmpty()))
                    put("host", ConfigValue.StringValue(uri.host.orEmpty()))
                    put("path", ConfigValue.StringValue(uri.path.orEmpty()))
                    put("fragment", ConfigValue.StringValue(uri.fragment.orEmpty()))
                    uri.queryParameterNames.forEach { name ->
                        put("query." + name, ConfigValue.StringValue(uri.getQueryParameter(name).orEmpty()))
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
