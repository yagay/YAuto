package com.yagay.yauto.core.registry

/**
 * One self-contained installable YAuto feature.
 *
 * A feature owns its descriptor and runtime implementation. Feature packs only group definitions by
 * domain. This keeps add/remove operations local and prevents the registry, editor and runtime from
 * accumulating feature-specific when blocks.
 */
sealed interface FeatureDefinition {
    val descriptor: FeatureDescriptor
    fun install(registry: FeatureRegistry)
}

data class ActionFeatureDefinition(
    override val descriptor: FeatureDescriptor,
    val executor: ActionExecutor,
) : FeatureDefinition {
    init { require(descriptor.kind == FeatureKind.ACTION) }
    override fun install(registry: FeatureRegistry) = registry.registerAction(descriptor, executor)
}

data class ConditionFeatureDefinition(
    override val descriptor: FeatureDescriptor,
    val evaluator: ConditionEvaluator,
) : FeatureDefinition {
    init { require(descriptor.kind == FeatureKind.CONDITION) }
    override fun install(registry: FeatureRegistry) = registry.registerCondition(descriptor, evaluator)
}

data class EventFeatureDefinition(
    override val descriptor: FeatureDescriptor,
    val matcher: EventMatcher,
) : FeatureDefinition {
    init { require(descriptor.kind == FeatureKind.EVENT) }
    override fun install(registry: FeatureRegistry) = registry.registerEvent(descriptor, matcher)
}

data class StateFeatureDefinition(
    override val descriptor: FeatureDescriptor,
    val evaluator: ConditionEvaluator,
) : FeatureDefinition {
    init { require(descriptor.kind == FeatureKind.STATE) }
    override fun install(registry: FeatureRegistry) = registry.registerState(descriptor, evaluator)
}

fun actionFeature(descriptor: FeatureDescriptor, executor: ActionExecutor): FeatureDefinition =
    ActionFeatureDefinition(descriptor, executor)

fun conditionFeature(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator): FeatureDefinition =
    ConditionFeatureDefinition(descriptor, evaluator)

fun eventFeature(descriptor: FeatureDescriptor, matcher: EventMatcher): FeatureDefinition =
    EventFeatureDefinition(descriptor, matcher)

fun stateFeature(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator): FeatureDefinition =
    StateFeatureDefinition(descriptor, evaluator)

class DefinitionFeaturePack(
    override val id: String,
    definitions: Iterable<FeatureDefinition>,
) : FeaturePack {
    private val definitions = definitions.map { definition ->
        val descriptor = definition.descriptor
        val owned = if (descriptor.ownerPackId == id) descriptor else descriptor.copy(ownerPackId = id)
        when (definition) {
            is ActionFeatureDefinition -> definition.copy(descriptor = owned)
            is ConditionFeatureDefinition -> definition.copy(descriptor = owned)
            is EventFeatureDefinition -> definition.copy(descriptor = owned)
            is StateFeatureDefinition -> definition.copy(descriptor = owned)
        }
    }

    override fun install(registry: FeatureRegistry) {
        definitions.forEach { it.install(registry) }
    }
}

fun featurePack(id: String, vararg definitions: FeatureDefinition): FeaturePack =
    DefinitionFeaturePack(id, definitions.asList())
