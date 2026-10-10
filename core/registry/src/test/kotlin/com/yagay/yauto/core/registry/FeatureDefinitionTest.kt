package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureDefinitionTest {
    @Test
    fun `definition pack owns installed descriptors`() {
        val registry = FeatureRegistry()
        val definition = actionFeature(
            descriptor("test.action")
        ) { _, _ -> ActionExecutionResult(true) }

        registry.install(featurePack("test.pack", definition))

        assertEquals("test.pack", registry.descriptor("test.action")?.ownerPackId)
        assertTrue(registry.catalogIssues().none { it.severity == FeatureCatalogIssueSeverity.ERROR })
    }

    @Test
    fun `schema defaults fill only missing values`() {
        val descriptor = descriptor("test.defaults").copy(
            fields = listOf(
                FieldSchema.Toggle("enabled", "Enabled"),
                FieldSchema.Number("count", "Count"),
            ),
            fieldBehaviors = mapOf(
                "enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true)),
                "count" to FieldBehavior(defaultValue = ConfigValue.NumberValue(3.0)),
            ),
        )
        val feature = FeatureRef(
            typeId = "test.defaults",
            config = mapOf("count" to ConfigValue.NumberValue(9.0)),
        )

        val prepared = descriptor.applyDefaults(feature)

        assertEquals(ConfigValue.BooleanValue(true), prepared.config["enabled"])
        assertEquals(ConfigValue.NumberValue(9.0), prepared.config["count"])
    }

    @Test
    fun `catalog validation rejects orphan behavior and bad default`() {
        val descriptor = descriptor("test.invalid").copy(
            fields = listOf(FieldSchema.Choice("mode", "Mode", options = listOf("a", "b"))),
            fieldBehaviors = mapOf(
                "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("missing")),
                "ghost" to FieldBehavior(defaultValue = ConfigValue.StringValue("x")),
            ),
        )

        val issues = validateFeatureDescriptors(listOf(descriptor))

        assertTrue(issues.any { it.code == "invalid_field_default" })
        assertTrue(issues.any { it.code == "orphan_field_behavior" })
    }

    @Test
    fun `semantic domain is inferred independently from legacy category`() {
        assertEquals(
            FeatureDomain.LOCATION,
            FeatureDescriptor(
                id = FeatureId("android.event.geofence_transition"),
                kind = FeatureKind.EVENT,
                title = "Geofence",
                description = "Geofence",
                category = FeatureCategory.DEVICE,
            ).domain,
        )
        assertEquals(
            FeatureDomain.WEB_NETWORK,
            FeatureDescriptor(
                id = FeatureId("android.http.request"),
                kind = FeatureKind.ACTION,
                title = "HTTP request",
                description = "HTTP request",
                category = FeatureCategory.SCRIPT,
            ).domain,
        )
        assertEquals(
            FeatureDomain.AI,
            FeatureDescriptor(
                id = FeatureId("ai.llm.query"),
                kind = FeatureKind.ACTION,
                title = "LLM query",
                description = "LLM query",
                category = FeatureCategory.ADVANCED,
            ).domain,
        )
        assertEquals(
            FeatureDomain.USER_INPUT,
            FeatureDescriptor(
                id = FeatureId("android.qs_tile.click"),
                kind = FeatureKind.ACTION,
                title = "Quick Settings tile",
                description = "Quick Settings tile",
                category = FeatureCategory.ADVANCED,
            ).domain,
        )
        assertEquals(
            FeatureDomain.COMMUNICATION,
            FeatureDescriptor(
                id = FeatureId("android.sms.compose"),
                kind = FeatureKind.ACTION,
                title = "Compose SMS",
                description = "Compose SMS",
                category = FeatureCategory.APP,
            ).domain,
        )
    }

    @Test
    fun `catalog validation rejects unknown conditional dependency`() {
        val descriptor = descriptor("test.rule").copy(
            fields = listOf(FieldSchema.Text("value", "Value")),
            fieldBehaviors = mapOf(
                "value" to FieldBehavior(visibleWhen = FieldRule.Truthy("missing")),
            ),
        )

        val issues = validateFeatureDescriptors(listOf(descriptor))

        assertTrue(issues.any { it.code == "unknown_field_rule_dependency" })
    }

    private fun descriptor(id: String) = FeatureDescriptor(
        id = FeatureId(id),
        kind = FeatureKind.ACTION,
        title = id,
        description = id,
        category = FeatureCategory.CORE,
    )
    @Test
    fun `legacy source aliases must resolve to exactly one feature`() {
        val left = descriptor("test.left").copy(aliases = setOf("legacy.operation"))
        val right = descriptor("test.right").copy(aliases = setOf("legacy.operation"))
        val issues = validateFeatureDescriptors(listOf(left, right))
        assertTrue(issues.any { it.code == "duplicate_feature_alias" })
    }

    @Test
    fun `aliases must not shadow canonical feature ids`() {
        val canonical = descriptor("test.canonical")
        val alias = descriptor("test.alias").copy(aliases = setOf("test.canonical"))
        val issues = validateFeatureDescriptors(listOf(canonical, alias))
        assertTrue(issues.any { it.code == "alias_matches_feature_id" })
    }

    @Test
    fun `generic editors share descriptor field visibility`() {
        val definition = descriptor("test.visibility").copy(
            fields = listOf(
                FieldSchema.Toggle("enabled", "Enabled"),
                FieldSchema.Text("detail", "Detail"),
                FieldSchema.Text("advanced", "Advanced"),
            ),
            fieldBehaviors = mapOf(
                "detail" to FieldBehavior(visibleWhen = FieldRule.Truthy("enabled")),
                "advanced" to FieldBehavior(advanced = true),
            ),
        )
        val hidden = definition.visibleEditorFields(mapOf("enabled" to ConfigValue.BooleanValue(false)), false)
        assertEquals(listOf("enabled"), hidden.map { it.key })
        val shown = definition.visibleEditorFields(mapOf("enabled" to ConfigValue.BooleanValue(true)), true)
        assertEquals(listOf("enabled", "detail", "advanced"), shown.map { it.key })
    }

    @Test
    fun `schema active state is shared by editor rendering validation and saving`() {
        val descriptor = descriptor("test.active").copy(
            fields = listOf(
                FieldSchema.Toggle("on", "Enabled"),
                FieldSchema.Text("dependent", "Dependent"),
            ),
            fieldBehaviors = mapOf(
                "dependent" to FieldBehavior(
                    visibleWhen = FieldRule.Truthy("on"),
                    enabledWhen = FieldRule.Truthy("on"),
                ),
            ),
        )
        val off = mapOf("on" to ConfigValue.BooleanValue(false))
        assertEquals(listOf("on"), descriptor.editableEditorFields(off).map { it.key })
        val on = mapOf("on" to ConfigValue.BooleanValue(true))
        assertEquals(listOf("on", "dependent"), descriptor.editableEditorFields(on).map { it.key })
    }

}
