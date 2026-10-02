package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.importer.CompatFeatureIds
import com.yagay.yauto.core.model.Stability
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class CompatibilityFeaturePack : FeaturePack {
    override val id: String = "standard.compatibility"

    override fun install(registry: FeatureRegistry) {
        listOf(
            CompatFeatureIds.SOURCE_EVENT to FeatureKind.EVENT,
            CompatFeatureIds.SOURCE_STATE to FeatureKind.STATE,
            CompatFeatureIds.SOURCE_CONDITION to FeatureKind.CONDITION,
        ).forEach { (typeId, kind) ->
            registry.registerDescriptor(
                FeatureDescriptor(
                    FeatureId(typeId), kind, userText("compat.imported_item"),
                    userText("compat.imported_item_description"),
                    FeatureCategory.COMPATIBILITY,
                    stability = Stability.DEPRECATED,
                    ownerPackId = id,
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId(CompatFeatureIds.SOURCE_ACTION), FeatureKind.ACTION, userText("compat.imported_action"),
                userText("compat.imported_action_description"),
                FeatureCategory.COMPATIBILITY,
                stability = Stability.DEPRECATED,
                ownerPackId = id,
            )
        ) { feature, _ ->
            val source = feature.config.string("source.type", "unknown")
            ActionExecutionResult(false, message = userText("compat.action_not_mapped", source))
        }
    }
}
