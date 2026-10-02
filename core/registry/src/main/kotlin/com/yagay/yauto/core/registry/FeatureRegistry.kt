package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityId
import com.yagay.yauto.core.logging.ExecutionTracer
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

enum class AccessRequirement(val id: String, val label: String) {
    ROOT("root", "Root"),
    SHIZUKU("shizuku", "Shizuku"),
    LSPOSED("lsposed", "LSPosed"),
    SHAMIKO("shamiko", "Shamiko"),
    ZYGISK("zygisk", "Zygisk"),
    ACCESSIBILITY("accessibility", "Accessibility"),
    NOTIFICATION_LISTENER("notification_listener", "通知监听"),
    OVERLAY("overlay", "悬浮窗"),
    WRITE_SETTINGS("write_settings", "修改系统设置"),
    DEVICE_ADMIN("device_admin", "设备管理器"),
}

data class FeatureImplementationOption(
    /** CapabilityBackend.id. Use null only for a non-Broker implementation described by the feature itself. */
    val backendId: String?,
    val title: String,
    val summary: String,
    val requirements: Set<AccessRequirement> = emptySet(),
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
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
    /** Extra environment requirements that cannot be inferred from CapabilityId, e.g. Shamiko/Zygisk. */
    val accessRequirements: Set<AccessRequirement> = emptySet(),
    /** Explicit alternatives override the built-in capability-derived implementation catalog. */
    val implementationOptions: List<FeatureImplementationOption> = emptyList(),
)

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
    private val actions = ConcurrentHashMap<String, ActionExecutor>()
    private val conditions = ConcurrentHashMap<String, ConditionEvaluator>()
    private val events = ConcurrentHashMap<String, EventMatcher>()
    private val states = ConcurrentHashMap<String, ConditionEvaluator>()

    fun registerAction(descriptor: FeatureDescriptor, executor: ActionExecutor) {
        require(descriptor.kind == FeatureKind.ACTION)
        registerDescriptor(descriptor)
        actions[descriptor.id.value] = executor
    }

    fun registerCondition(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        require(descriptor.kind == FeatureKind.CONDITION)
        registerDescriptor(descriptor)
        conditions[descriptor.id.value] = evaluator
    }

    fun registerEvent(descriptor: FeatureDescriptor, matcher: EventMatcher) {
        require(descriptor.kind == FeatureKind.EVENT)
        registerDescriptor(descriptor)
        events[descriptor.id.value] = matcher
    }

    fun registerState(descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        require(descriptor.kind == FeatureKind.STATE)
        registerDescriptor(descriptor)
        states[descriptor.id.value] = evaluator
    }

    fun registerDescriptor(descriptor: FeatureDescriptor) {
        val decorated = descriptor.withAccessEditorMetadata()
        val existing = descriptors.putIfAbsent(decorated.id.value, decorated)
        require(existing == null || existing == decorated) { "Feature ID collision: ${decorated.id.value}" }
    }

    fun install(pack: FeaturePack) = pack.install(this)

    fun uninstallPack(packId: String) {
        val ids = descriptors.values.filter { it.ownerPackId == packId }.map { it.id.value }.toSet()
        ids.forEach {
            descriptors.remove(it)
            actions.remove(it)
            conditions.remove(it)
            events.remove(it)
            states.remove(it)
        }
    }

    fun descriptor(id: String): FeatureDescriptor? = descriptors[id]
    fun actionExecutor(id: String): ActionExecutor? = actions[id]
    fun conditionEvaluator(id: String): ConditionEvaluator? = conditions[id]
    fun eventMatcher(id: String): EventMatcher? = events[id]
    fun stateEvaluator(id: String): ConditionEvaluator? = states[id]
    fun allDescriptors(): List<FeatureDescriptor> = descriptors.values.sortedWith(compareBy<FeatureDescriptor> { it.category.name }.thenBy { it.title })
}
