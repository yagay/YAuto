package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.io.File

/** Configurable file-system trigger inspired by mature automation tools, using YAuto-native events. */
class AndroidFileEventFeaturePack : FeaturePack {
    override val id: String = "android.file.events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.file_changed"), FeatureKind.EVENT,
                "File changed", "Run when an accessible file or directory reports a selected filesystem change",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File or directory path", true),
                    FieldSchema.Choice("event", "Change type", true, listOf("any", "create", "delete", "modify", "move", "close_write")),
                    FieldSchema.Toggle("recursive", "Watch subdirectories"),
                    FieldSchema.Text("nameContains", "Name contains"),
                ),
                keywords = setOf("file", "folder", "watch", "changed", "created", "deleted", "modified"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.file_changed" &&
                ctx.event.payload.string("subscription") == fileWatchSubscriptionKey(feature)
        }
    }
}

internal fun fileWatchSubscriptionKey(feature: FeatureRef): String {
    val path = feature.config.string("path").trim().let { if (it.isBlank()) it else File(it).absolutePath }
    val event = feature.config.string("event", "any")
    val recursive = (feature.config["recursive"] as? com.yagay.yauto.core.model.ConfigValue.BooleanValue)?.value == true
    val nameContains = feature.config.string("nameContains").trim().lowercase()
    return listOf(path, event, recursive.toString(), nameContains).joinToString("\u001f")
}
