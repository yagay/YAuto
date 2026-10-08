package com.yagay.yauto

import android.telecom.Call
import android.telecom.CallScreeningService
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class YAutoCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher by lazy { RuntimeEventDispatcher((application as YAutoApplication).graph, scope) }

    override fun onScreenCall(callDetails: Call.Details) {
        // YAuto observes by default; it does not block, silence or reject the call.
        respondToCall(callDetails, CallResponse.Builder().build())

        val direction = callDetails.callDirection
        val incoming = direction == Call.Details.DIRECTION_INCOMING
        dispatcher.dispatch(
            RuntimeEvent(
                typeId = "android.event.call_screened",
                payload = mapOf(
                    "incoming" to ConfigValue.BooleanValue(incoming),
                    "direction" to ConfigValue.StringValue(
                        when (direction) {
                            Call.Details.DIRECTION_INCOMING -> "incoming"
                            Call.Details.DIRECTION_OUTGOING -> "outgoing"
                            else -> "unknown"
                        }
                    ),
                    "number" to ConfigValue.StringValue(callDetails.handle?.schemeSpecificPart.orEmpty()),
                    "name" to ConfigValue.StringValue(callDetails.contactDisplayName?.toString().orEmpty()),
                    "properties" to ConfigValue.StringValue(callDetails.callProperties.toString()),
                    "capabilities" to ConfigValue.StringValue(callDetails.callCapabilities.toString()),
                    "propertiesCode" to ConfigValue.NumberValue(callDetails.callProperties.toDouble()),
                    "capabilitiesCode" to ConfigValue.NumberValue(callDetails.callCapabilities.toDouble()),
                    "accountPackage" to ConfigValue.StringValue(
                        callDetails.accountHandle?.componentName?.packageName.orEmpty()
                    ),
                ),
                source = "android.call_screening",
            )
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
