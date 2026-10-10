package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceData

/** Stable trigger identity shared by dispatch and trigger controls. */
internal object RuntimeTriggerPolicy {
    fun key(automation: Automation, feature: FeatureRef): String {
        val sourceType = (feature.config["source.type"] as? ConfigValue.StringValue)?.value.orEmpty()
        val tag = feature.config.string("tag").trim()
        return automation.id.value + "|" +
            (if (sourceType.isNotBlank()) sourceType else feature.typeId) + "|" + tag
    }
    fun enabled(workspace: WorkspaceData, automation: Automation, feature: FeatureRef): Boolean =
        key(automation, feature) !in workspace.disabledTriggerKeys
}
