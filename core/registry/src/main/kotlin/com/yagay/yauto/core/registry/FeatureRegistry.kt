package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityId
import com.yagay.yauto.core.capability.preferBackend
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.Stability
import java.util.concurrent.ConcurrentHashMap

class FeatureRegistry {
    private val descriptors = ConcurrentHashMap<String, FeatureDescriptor>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val actions = ConcurrentHashMap<String, ActionExecutor>()
    private val conditions = ConcurrentHashMap<String, ConditionEvaluator>()
    private val events = ConcurrentHashMap<String, EventMatcher>()
    private val states = ConcurrentHashMap<String, ConditionEvaluator>()
    @Volatile private var descriptorSnapshot: List<FeatureDescriptor>? = null

    fun registerAction(descriptor: FeatureDescriptor, executor: ActionExecutor) {
        require(descriptor.kind == FeatureKind.ACTION)
        registerDescriptor(descriptor)
        actions[descriptor.id.value] = ActionExecutor { feature, context ->
            val prepared = prepareFeature(feature, FeatureKind.ACTION)
            executor.execute(prepared, context.withFeatureBackend(prepared))
        }
    }

    fun registerCondition(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        require(descriptor.kind == FeatureKind.CONDITION)
        registerDescriptor(descriptor)
        conditions[descriptor.id.value] = ConditionEvaluator { feature, context ->
            val prepared = prepareFeature(feature, FeatureKind.CONDITION)
            evaluator.evaluate(prepared, context.withFeatureBackend(prepared))
        }
    }

    fun registerEvent(descriptor: FeatureDescriptor, matcher: EventMatcher) {
        require(descriptor.kind == FeatureKind.EVENT)
        registerDescriptor(descriptor)
        events[descriptor.id.value] = EventMatcher { feature, context ->
            val prepared = prepareFeature(feature, FeatureKind.EVENT)
            matcher.matches(prepared, context.withFeatureBackend(prepared))
        }
    }

    fun registerState(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        require(descriptor.kind == FeatureKind.STATE)
        registerDescriptor(descriptor)
        states[descriptor.id.value] = ConditionEvaluator { feature, context ->
            val prepared = prepareFeature(feature, FeatureKind.STATE)
            evaluator.evaluate(prepared, context.withFeatureBackend(prepared))
        }
    }

    @Synchronized
    fun registerDescriptor(descriptor: FeatureDescriptor) {
        val decorated = descriptor.withAccessEditorMetadata().copy(
            keywords = descriptor.keywords.filterNot(::containsCjk).toSet(),
            aliases = descriptor.aliases.filter { it.isNotBlank() && it != descriptor.id.value }.toSet(),
        )
        val id = decorated.id.value
        val existingAliasOwner = aliases[id]
        require(existingAliasOwner == null || existingAliasOwner == id) {
            "Feature ID collides with alias: $id -> $existingAliasOwner"
        }

        val existingDescriptor = descriptors[id]
        require(existingDescriptor == null || existingDescriptor == decorated) {
            "Feature ID collision: $id"
        }

        require(decorated.aliasConfigDefaults.keys.all { it in decorated.aliases }) {
            "Alias config defaults must target declared aliases for $id"
        }
        require(decorated.aliasConfigKeyRenames.keys.all { it in decorated.aliases }) {
            "Alias config key renames must target declared aliases for $id"
        }
        decorated.aliases.forEach { alias ->
            require(descriptors[alias] == null) {
                "Feature alias collides with canonical ID: $alias"
            }
            val existingTarget = aliases[alias]
            require(existingTarget == null || existingTarget == id) {
                "Feature alias collision: $alias -> $existingTarget / $id"
            }
        }

        // All validation is complete. Commit descriptor + aliases together while holding the same
        // registry monitor so a rejected descriptor cannot leave partial canonical/alias state.
        if (existingDescriptor == null) {
            descriptors[id] = decorated
            descriptorSnapshot = null
        }
        decorated.aliases.forEach { alias -> aliases[alias] = id }
    }

    fun install(pack: FeaturePack) = pack.install(this)

    @Synchronized
    fun uninstallPack(packId: String) {
        val ids = descriptors.values.filter { it.ownerPackId == packId }.map { it.id.value }.toSet()
        aliases.entries.removeIf { it.value in ids }
        ids.forEach {
            descriptors.remove(it)
            actions.remove(it)
            conditions.remove(it)
            events.remove(it)
            states.remove(it)
        }
        if (ids.isNotEmpty()) descriptorSnapshot = null
    }

    fun resolve(id: String, expectedKind: FeatureKind? = null): FeatureResolution {
        val canonicalId = if (descriptors.containsKey(id)) id else aliases[id]
            ?: return FeatureResolution.Missing(id)
        val descriptor = descriptors[canonicalId] ?: return FeatureResolution.Missing(id)
        if (expectedKind != null && descriptor.kind != expectedKind) {
            return FeatureResolution.Incompatible(id, expectedKind, descriptor.kind, descriptor)
        }
        return if (canonicalId == id) {
            FeatureResolution.Available(id, descriptor)
        } else {
            FeatureResolution.Aliased(id, canonicalId, descriptor)
        }
    }

    fun canonicalId(id: String, expectedKind: FeatureKind? = null): String? = when (
        val resolution = resolve(id, expectedKind)
    ) {
        is FeatureResolution.Available -> resolution.descriptor.id.value
        is FeatureResolution.Aliased -> resolution.canonicalId
        is FeatureResolution.Missing,
        is FeatureResolution.Incompatible -> null
    }

    fun canonicalRef(feature: FeatureRef, expectedKind: FeatureKind? = null): FeatureRef {
        return when (val resolution = resolve(feature.typeId, expectedKind)) {
            is FeatureResolution.Available -> feature
            is FeatureResolution.Aliased -> {
                val defaults = resolution.descriptor.aliasConfigDefaults[feature.typeId].orEmpty()
                val renames = resolution.descriptor.aliasConfigKeyRenames[feature.typeId].orEmpty()
                val renamed = buildMap {
                    feature.config.forEach { (key, value) ->
                        renames[key]?.let { target -> put(target, value) }
                    }
                }
                val direct = feature.config.filterKeys { it !in renames.keys }
                feature.copy(
                    typeId = resolution.canonicalId,
                    config = defaults + renamed + direct,
                )
            }
            is FeatureResolution.Missing,
            is FeatureResolution.Incompatible -> feature
        }
    }

    fun descriptor(id: String): FeatureDescriptor? = when (val resolution = resolve(id)) {
        is FeatureResolution.Available -> resolution.descriptor
        is FeatureResolution.Aliased -> resolution.descriptor
        is FeatureResolution.Missing,
        is FeatureResolution.Incompatible -> null
    }

    fun actionExecutor(id: String): ActionExecutor? = canonicalId(id, FeatureKind.ACTION)?.let(actions::get)
    fun conditionEvaluator(id: String): ConditionEvaluator? = canonicalId(id, FeatureKind.CONDITION)?.let(conditions::get)
    fun eventMatcher(id: String): EventMatcher? = canonicalId(id, FeatureKind.EVENT)?.let(events::get)
    fun stateEvaluator(id: String): ConditionEvaluator? = canonicalId(id, FeatureKind.STATE)?.let(states::get)
    fun allDescriptors(): List<FeatureDescriptor> {
        descriptorSnapshot?.let { return it }
        return synchronized(this) {
            descriptorSnapshot ?: descriptors.values.sortedWith(
                compareBy<FeatureDescriptor> { it.category.name }.thenBy { it.title }
            ).also { descriptorSnapshot = it }
        }
    }

    private fun prepareFeature(feature: FeatureRef, kind: FeatureKind): FeatureRef {
        val canonical = canonicalRef(feature, kind)
        return descriptor(canonical.typeId)?.applyDefaults(canonical) ?: canonical
    }
}

private fun FeatureExecutionContext.withFeatureBackend(feature: FeatureRef): FeatureExecutionContext =
    copy(capabilities = capabilities.preferBackend(feature.effectiveMethodBackendId()))

private fun EventMatchContext.withFeatureBackend(feature: FeatureRef): EventMatchContext =
    copy(capabilities = capabilities.preferBackend(feature.effectiveMethodBackendId()))

private fun containsCjk(value: String): Boolean = value.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
