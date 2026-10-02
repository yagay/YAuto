package com.yagay.yauto.platform.android

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

class AndroidNfcFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.nfc"
    private val adapter: NfcAdapter? = context.applicationContext.getSystemService(NfcManager::class.java)?.defaultAdapter

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.nfc.info"), FeatureKind.ACTION,
                "Get NFC information", "Store whether NFC hardware is available and currently enabled",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store NFC object in variable", true)),
                keywords = setOf("nfc", "tag", "enabled", "hardware"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "available" to ConfigValue.BooleanValue(adapter != null),
                    "enabled" to ConfigValue.BooleanValue(runCatching { adapter?.isEnabled == true }.getOrDefault(false)),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.nfc_tag"), FeatureKind.EVENT,
                "NFC tag discovered", "Run when Android routes an NFC tag to YAuto and optionally match UID, technology, text or URI records",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("kind", "Discovery type", options = listOf("any", "ndef", "tech", "tag")),
                    FieldSchema.Text("uidHex", "UID hex exact"),
                    FieldSchema.Text("uidPrefix", "UID hex prefix"),
                    FieldSchema.Text("techContains", "Technology name contains"),
                    FieldSchema.Text("textContains", "NDEF text contains"),
                    FieldSchema.Text("uriContains", "NDEF URI contains"),
                ),
                keywords = setOf("nfc", "tag", "ndef", "uid", "tech", "uri"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.nfc_tag") return@registerEvent false
            val payload = ctx.event.payload
            val expectedKind = feature.config.string("kind", "any")
            val actualUid = payload.string("uidHex")
            val uidExact = feature.config.string("uidHex").normalizeUid()
            val uidPrefix = feature.config.string("uidPrefix").normalizeUid()
            val techFilter = feature.config.string("techContains")
            val textFilter = feature.config.string("textContains")
            val uriFilter = feature.config.string("uriContains")
            val technologies = (payload["techList"] as? ConfigValue.ListValue)?.value.orEmpty().map { (it as? ConfigValue.StringValue)?.value.orEmpty() }
            val texts = (payload["texts"] as? ConfigValue.ListValue)?.value.orEmpty().map { (it as? ConfigValue.StringValue)?.value.orEmpty() }
            val uris = (payload["uris"] as? ConfigValue.ListValue)?.value.orEmpty().map { (it as? ConfigValue.StringValue)?.value.orEmpty() }
            (expectedKind == "any" || payload.string("kind") == expectedKind) &&
                (uidExact.isBlank() || actualUid.normalizeUid() == uidExact) &&
                (uidPrefix.isBlank() || actualUid.normalizeUid().startsWith(uidPrefix)) &&
                (techFilter.isBlank() || technologies.any { it.contains(techFilter, ignoreCase = true) }) &&
                (textFilter.isBlank() || texts.any { it.contains(textFilter, ignoreCase = true) }) &&
                (uriFilter.isBlank() || uris.any { it.contains(uriFilter, ignoreCase = true) })
        }
    }
}

internal fun String.normalizeUid(): String = filter { it.isLetterOrDigit() }.uppercase()
