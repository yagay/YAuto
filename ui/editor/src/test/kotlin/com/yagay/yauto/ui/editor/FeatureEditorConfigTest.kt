package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldRule
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.applyDefaults
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeatureEditorConfigTest {
    private val descriptor = FeatureDescriptor(
        FeatureId("test.mode"), FeatureKind.ACTION, "Mode", "Set mode", FeatureCategory.CORE,
        fields = listOf(
            FieldSchema.Choice("mode", "Mode", true, listOf("basic", "advanced")),
            FieldSchema.Text("secret", "Advanced secret"),
            FieldSchema.Text("disabledValue", "Disabled"),
        ),
        fieldBehaviors = mapOf(
            "secret" to FieldBehavior(
                visibleWhen = FieldRule.Equals("mode", ConfigValue.StringValue("advanced")),
            ),
            "disabledValue" to FieldBehavior(
                enabledWhen = FieldRule.Equals("mode", ConfigValue.StringValue("advanced")),
            ),
        ),
    )

    @Test
    fun `switching to basic removes hidden and disabled old config while retaining unknown data`() {
        val initial = FeatureRef("test.mode", config = mapOf(
            "mode" to ConfigValue.StringValue("advanced"),
            "secret" to ConfigValue.StringValue("no-longer-active"),
            "disabledValue" to ConfigValue.StringValue("old"),
            "futureField" to ConfigValue.BooleanValue(true),
        ))
        val saved = buildEditedFeatureConfig(
            descriptor,
            initial,
            descriptor.applyDefaults(initial),
            mapOf("mode" to "basic", "secret" to "no-longer-active", "disabledValue" to "old"),
            Locale.UK,
        )
        assertEquals(ConfigValue.StringValue("basic"), saved["mode"])
        assertFalse(saved.containsKey("secret"))
        assertFalse(saved.containsKey("disabledValue"))
        assertEquals(ConfigValue.BooleanValue(true), saved["futureField"])
    }

    @Test
    fun `resaving a legacy fixed backend switches to automatic routing`() {
        val old = FeatureRef("test.mode", config = mapOf(
            "mode" to ConfigValue.StringValue("basic"),
            "__method" to ConfigValue.StringValue("root_required"),
            "__backend" to ConfigValue.StringValue("root"),
        ))
        val result = buildEditedFeatureConfig(
            descriptor, old, descriptor.applyDefaults(old),
            mapOf("mode" to "basic"), Locale.UK,
        )
        assertEquals(ConfigValue.StringValue("basic"), result["mode"])
        assertFalse(result.containsKey("__method"))
        assertFalse(result.containsKey("__backend"))
    }

    @Test
    fun `unchanged advanced values preserve original typed values and hidden hardware identity`() {
        val initial = FeatureRef("test.mode", config = mapOf(
            "mode" to ConfigValue.StringValue("advanced"),
            "secret" to ConfigValue.StringValue("present"),
            "hardwareIdentity" to ConfigValue.ObjectValue(mapOf("deviceId" to ConfigValue.NumberValue(4.0))),
        ))
        val saved = buildEditedFeatureConfig(
            descriptor,
            initial,
            descriptor.applyDefaults(initial),
            mapOf("mode" to "advanced", "secret" to "present"),
            Locale.UK,
            initial.config.filterKeys { it.startsWith("hardwareIdentity") },
        )
        assertEquals(initial.config["secret"], saved["secret"])
        assertEquals(initial.config["hardwareIdentity"], saved["hardwareIdentity"])
    }
}
