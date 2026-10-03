package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.Stability

enum class FeatureCatalogIssueSeverity { ERROR, WARNING }

data class FeatureCatalogIssue(
    val severity: FeatureCatalogIssueSeverity,
    val code: String,
    val featureId: String,
    val message: String,
)

/**
 * Validates the structural contract shared by every Feature pack.
 *
 * The validator intentionally lives in core:registry rather than in a UI or a specific pack so
 * every future Trigger/State/Condition/Action can be checked by the same rules in unit tests, CI,
 * diagnostics, or developer tooling.
 */
fun validateFeatureDescriptors(descriptors: Iterable<FeatureDescriptor>): List<FeatureCatalogIssue> {
    val list = descriptors.toList()
    val issues = mutableListOf<FeatureCatalogIssue>()

    list.groupBy { it.id.value }
        .filterValues { it.size > 1 }
        .forEach { (id, matches) ->
            issues += FeatureCatalogIssue(
                severity = FeatureCatalogIssueSeverity.ERROR,
                code = "duplicate_feature_id",
                featureId = id,
                message = "Feature ID is registered ${matches.size} times",
            )
        }

    list.forEach { descriptor ->
        val id = descriptor.id.value

        if (id.isBlank() || id != id.trim()) {
            issues += descriptor.error("invalid_feature_id", "Feature ID must be non-blank and trimmed")
        }
        if (descriptor.ownerPackId.isBlank()) {
            issues += descriptor.error("missing_owner_pack", "Feature ownerPackId must not be blank")
        }
        if (descriptor.schemaVersion <= 0) {
            issues += descriptor.error("invalid_schema_version", "schemaVersion must be greater than zero")
        }
        if (descriptor.minSdk <= 0) {
            issues += descriptor.error("invalid_min_sdk", "minSdk must be greater than zero")
        }

        val fieldKeys = descriptor.fields.map { it.key }.toSet()
        descriptor.fields
            .groupBy { it.key }
            .filterValues { it.size > 1 }
            .forEach { (key, matches) ->
                issues += descriptor.error(
                    "duplicate_field_key",
                    "Field key '$key' is declared ${matches.size} times",
                )
            }

        descriptor.fieldBehaviors.keys.filterNot(fieldKeys::contains).forEach { key ->
            issues += descriptor.error(
                "orphan_field_behavior",
                "Field behavior '$key' does not match a declared field",
            )
        }

        descriptor.fields.forEach { field ->
            if (field.key.isBlank() || field.key != field.key.trim()) {
                issues += descriptor.error(
                    "invalid_field_key",
                    "Field key '${field.key}' must be non-blank and trimmed",
                )
            }
            when (field) {
                is FieldSchema.Number -> {
                    val min = field.min
                    val max = field.max
                    if (min != null && max != null && min > max) {
                        issues += descriptor.error(
                            "invalid_number_range",
                            "Number field '${field.key}' has min $min greater than max $max",
                        )
                    }
                }
                is FieldSchema.Choice -> {
                    val duplicates = field.options.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                    if (duplicates.isNotEmpty()) {
                        issues += descriptor.error(
                            "duplicate_choice_option",
                            "Choice field '${field.key}' contains duplicate options: ${duplicates.joinToString()}",
                        )
                    }
                }
                else -> Unit
            }

            val behavior = descriptor.fieldBehaviors[field.key] ?: return@forEach
            validateDefault(descriptor, field, behavior.defaultValue)?.let(issues::add)
            listOfNotNull(behavior.visibleWhen, behavior.enabledWhen).forEach { rule ->
                if (rule.fieldKey !in fieldKeys) {
                    issues += descriptor.error(
                        "unknown_field_rule_dependency",
                        "Field '${field.key}' depends on unknown field '${rule.fieldKey}'",
                    )
                } else if (rule.fieldKey == field.key) {
                    issues += descriptor.error(
                        "self_field_rule_dependency",
                        "Field '${field.key}' cannot depend on itself",
                    )
                }
            }
        }
    }

    return issues.sortedWith(
        compareBy<FeatureCatalogIssue> { it.severity.ordinal }
            .thenBy { it.featureId }
            .thenBy { it.code }
    )
}

private fun validateDefault(
    descriptor: FeatureDescriptor,
    field: FieldSchema,
    value: ConfigValue?,
): FeatureCatalogIssue? {
    if (value == null) return null
    val valid = when (field) {
        is FieldSchema.Text, is FieldSchema.AppPicker, is FieldSchema.Variable -> value is ConfigValue.StringValue
        is FieldSchema.Number -> value is ConfigValue.NumberValue && value.value.isFinite() &&
            (field.min?.let { value.value >= it } ?: true) &&
            (field.max?.let { value.value <= it } ?: true)
        is FieldSchema.Duration -> value is ConfigValue.NumberValue && value.value.isFinite() && value.value >= 0
        is FieldSchema.Toggle -> value is ConfigValue.BooleanValue
        is FieldSchema.Choice -> value is ConfigValue.StringValue && value.value in field.options
    }
    return if (valid) null else descriptor.error(
        "invalid_field_default",
        "Default value for '${field.key}' does not match its field schema",
    )
}

/**
 * Adds implementation coverage checks on top of descriptor validation.
 *
 * Deprecated compatibility placeholders intentionally represent imported source items that YAuto
 * cannot execute directly, so they are warnings. Every normal catalog item without a runtime
 * implementation is an error because it would otherwise appear selectable while being unusable.
 */
fun FeatureRegistry.catalogIssues(): List<FeatureCatalogIssue> {
    val descriptors = allDescriptors()
    val issues = validateFeatureDescriptors(descriptors).toMutableList()

    descriptors.forEach { descriptor ->
        val hasImplementation = when (descriptor.kind) {
            FeatureKind.ACTION -> actionExecutor(descriptor.id.value) != null
            FeatureKind.CONDITION -> conditionEvaluator(descriptor.id.value) != null
            FeatureKind.EVENT -> eventMatcher(descriptor.id.value) != null
            FeatureKind.STATE -> stateEvaluator(descriptor.id.value) != null
        }
        if (!hasImplementation) {
            val compatibilityPlaceholder =
                descriptor.category == FeatureCategory.COMPATIBILITY || descriptor.stability == Stability.DEPRECATED
            issues += FeatureCatalogIssue(
                severity = if (compatibilityPlaceholder) {
                    FeatureCatalogIssueSeverity.WARNING
                } else {
                    FeatureCatalogIssueSeverity.ERROR
                },
                code = "missing_implementation",
                featureId = descriptor.id.value,
                message = "${descriptor.kind} has no registered runtime implementation",
            )
        }
    }

    return issues.sortedWith(
        compareBy<FeatureCatalogIssue> { it.severity.ordinal }
            .thenBy { it.featureId }
            .thenBy { it.code }
    )
}

/** Fails fast in tests/CI while keeping warnings available to diagnostics and developer tooling. */
fun FeatureRegistry.requireValidCatalog() {
    val errors = catalogIssues().filter { it.severity == FeatureCatalogIssueSeverity.ERROR }
    require(errors.isEmpty()) {
        errors.joinToString(prefix = "Invalid feature catalog:\n", separator = "\n") {
            "[${it.code}] ${it.featureId}: ${it.message}"
        }
    }
}

private fun FeatureDescriptor.error(code: String, message: String) = FeatureCatalogIssue(
    severity = FeatureCatalogIssueSeverity.ERROR,
    code = code,
    featureId = id.value,
    message = message,
)
