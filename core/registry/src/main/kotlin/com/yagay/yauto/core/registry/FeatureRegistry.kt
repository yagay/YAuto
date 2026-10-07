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

@JvmInline value class FeatureId(val value: String)

enum class FeatureKind { EVENT, STATE, CONDITION, ACTION }

enum class FeatureCategory {
    CORE, APP, DEVICE, NETWORK, DISPLAY, AUDIO, NOTIFICATION, FILE,
    VARIABLE, FLOW, UI_AUTOMATION, SYSTEM, SCRIPT, ADVANCED, COMPATIBILITY,
}

sealed interface FieldSchema {
    val key: String
    val label: String
    val required: Boolean
    data class Text(override val key: String, override val label: String, override val required: Boolean = false, val multiline: Boolean = false) : FieldSchema
    data class Number(override val key: String, override val label: String, override val required: Boolean = false, val min: Double? = null, val max: Double? = null) : FieldSchema
    data class Toggle(override val key: String, override val label: String, override val required: Boolean = false) : FieldSchema
    data class Duration(override val key: String, override val label: String, override val required: Boolean = false) : FieldSchema
    data class AppPicker(override val key: String, override val label: String, override val required: Boolean = false) : FieldSchema
    data class Variable(override val key: String, override val label: String, override val required: Boolean = false) : FieldSchema
    data class Choice(override val key: String, override val label: String, override val required: Boolean = false, val options: List<String>) : FieldSchema
}

/** Stable machine requirements. Human-readable labels live in Android resources. */
enum class AccessRequirement(val id: String) {
    ROOT("root"),
    SHIZUKU("shizuku"),
    LSPOSED("lsposed"),
    ZYGISK("zygisk"),
    ACCESSIBILITY("accessibility"),
    USAGE_STATS("usage_stats"),
    NOTIFICATION_LISTENER("notification_listener"),
    POST_NOTIFICATIONS("post_notifications"),
    OVERLAY("overlay"),
    WRITE_SETTINGS("write_settings"),
    CAMERA("camera"),
    LOCATION("location"),
    BLUETOOTH_CONNECT("bluetooth_connect"),
    DND_POLICY("dnd_policy"),
    DEVICE_ADMIN("device_admin"),
    CALENDAR("calendar"),
    CONTACTS("contacts"),
    CALL_LOG("call_log"),
    SMS("sms"),
    PHONE("phone"),
    RECORD_AUDIO("record_audio"),
    ACTIVITY_RECOGNITION("activity_recognition"),
}

/** Language-neutral implementation metadata. UI copy is resolved by backendId in the Android layer. */
data class FeatureImplementationOption(
    val backendId: String?,
    val requirements: Set<AccessRequirement> = emptySet(),
    val restartRequired: Boolean = false,
)

data class FeatureDescriptor(
    val id: FeatureId,
    val kind: FeatureKind,
    val title: String,
    val description: String,
    val category: FeatureCategory,
    val schemaVersion: Int = 1,
    val minSdk: Int = 31,
    val capabilities: Set<CapabilityId> = emptySet(),
    val fields: List<FieldSchema> = emptyList(),
    val stability: Stability = Stability.STABLE,
    val keywords: Set<String> = emptySet(),
    val ownerPackId: String = "core",
    val accessRequirements: Set<AccessRequirement> = emptySet(),
    val implementationOptions: List<FeatureImplementationOption> = emptyList(),
    /** Historical IDs accepted when restoring older workspaces. New code must use [id]. */
    val aliases: Set<String> = emptySet(),
    /** Presentation and default-value metadata keyed by [FieldSchema.key]. */
    val fieldBehaviors: Map<String, FieldBehavior> = emptyMap(),
    /** User-facing semantic domain; kept near the end to preserve positional constructor compatibility. */
    val domain: FeatureDomain = inferFeatureDomain(id.value, category),
    /**
     * Declarative config defaults applied only when a historical alias is restored.
     * Existing config wins over these values. This is appended to preserve all older positional
     * constructor call sites.
     */
    val aliasConfigDefaults: Map<String, ConfigMap> = emptyMap(),
    /**
     * Optional historical config-key migrations keyed by alias ID.
     * A direct canonical key already present in saved config wins over its renamed legacy key.
     */
    val aliasConfigKeyRenames: Map<String, Map<String, String>> = emptyMap(),
)

sealed interface FeatureResolution {
    val requestedId: String

    data class Available(
        override val requestedId: String,
        val descriptor: FeatureDescriptor,
    ) : FeatureResolution

    data class Aliased(
        override val requestedId: String,
        val canonicalId: String,
        val descriptor: FeatureDescriptor,
    ) : FeatureResolution

    data class Missing(
        override val requestedId: String,
    ) : FeatureResolution

    data class Incompatible(
        override val requestedId: String,
        val expectedKind: FeatureKind,
        val actualKind: FeatureKind,
        val descriptor: FeatureDescriptor,
    ) : FeatureResolution
}

interface VariableAccess {
    fun get(name: String): ConfigValue?
    fun set(name: String, value: ConfigValue)
    fun snapshot(): Map<String, ConfigValue>
}

data class FeatureExecutionContext(
    val executionId: ExecutionId,
    val nodeId: NodeId?,
    val variables: VariableAccess,
    val capabilities: CapabilityClient,
    val tracer: ExecutionTracer,
)

data class EventMatchContext(
    val executionId: ExecutionId,
    val event: RuntimeEvent,
    val variables: VariableAccess,
    val capabilities: CapabilityClient,
    val tracer: ExecutionTracer,
)

data class ActionExecutionResult(
    val success: Boolean,
    val value: ConfigValue = ConfigValue.NullValue,
    val message: String? = null,
)

fun interface ActionExecutor { suspend fun execute(feature: FeatureRef, context: FeatureExecutionContext): ActionExecutionResult }
fun interface ConditionEvaluator { suspend fun evaluate(feature: FeatureRef, context: FeatureExecutionContext): Boolean }
fun interface EventMatcher { suspend fun matches(feature: FeatureRef, context: EventMatchContext): Boolean }

interface FeaturePack {
    val id: String
    fun install(registry: FeatureRegistry)
}

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
    copy(capabilities = capabilities.preferBackend(feature.preferredBackendId()))

private fun EventMatchContext.withFeatureBackend(feature: FeatureRef): EventMatchContext =
    copy(capabilities = capabilities.preferBackend(feature.preferredBackendId()))

private fun containsCjk(value: String): Boolean = value.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
