package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidStopwatchFeaturePack : FeaturePack {
    override val id: String = "android.stopwatch"

    override fun install(registry: FeatureRegistry) {
        registerAction(registry, "android.stopwatch.start", "Start stopwatch") { name ->
            StopwatchRuntime.start(name); true
        }
        registerAction(registry, "android.stopwatch.pause", "Pause stopwatch") { name ->
            StopwatchRuntime.pause(name)
        }
        registerAction(registry, "android.stopwatch.resume", "Resume stopwatch") { name ->
            StopwatchRuntime.resume(name)
        }
        registerAction(registry, "android.stopwatch.reset", "Reset stopwatch") { name ->
            StopwatchRuntime.reset(name); true
        }
        registerAction(registry, "android.stopwatch.stop", "Stop stopwatch") { name ->
            StopwatchRuntime.stop(name)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.stopwatch.get"), FeatureKind.ACTION,
                "Get stopwatch", "Return elapsed time and running state for a named stopwatch",
                FeatureCategory.TIME,
                fields = listOf(
                    FieldSchema.Text("name", "Stopwatch name", true),
                    FieldSchema.Variable("resultVariable", "Store stopwatch object", true),
                ),
                keywords = setOf("stopwatch", "elapsed", "timer", "duration"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val snapshot = StopwatchRuntime.snapshot(name)
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "name" to ConfigValue.StringValue(name),
                    "exists" to ConfigValue.BooleanValue(snapshot != null),
                    "running" to ConfigValue.BooleanValue(snapshot?.running == true),
                    "elapsedMs" to ConfigValue.NumberValue((snapshot?.elapsedMs ?: 0L).toDouble()),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }

        val fields = listOf(
            FieldSchema.Text("name", "Stopwatch name", true),
            FieldSchema.Number("minElapsedMs", "Minimum elapsed ms", min = 0.0),
            FieldSchema.Number("maxElapsedMs", "Maximum elapsed ms", min = 0.0),
            FieldSchema.Toggle("running", "Running"),
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val snapshot = StopwatchRuntime.snapshot(name) ?: return@ConditionEvaluator false
            val min = feature.config["minElapsedMs"].numberOrNull() ?: 0.0
            val max = feature.config["maxElapsedMs"].numberOrNull() ?: Double.MAX_VALUE
            min <= max && snapshot.elapsedMs.toDouble() in min..max &&
                snapshot.running == feature.config.boolean("running", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.stopwatch"), FeatureKind.STATE,
            "Stopwatch state", "Check elapsed time and running state of a named stopwatch",
            FeatureCategory.TIME,
            fields = fields,
            keywords = setOf("stopwatch", "elapsed", "running"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.stopwatch"), kind = FeatureKind.CONDITION), evaluator)

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.stopwatch_reached"), FeatureKind.EVENT,
                "Stopwatch reached", "Run when a named stopwatch reaches a configured elapsed duration",
                FeatureCategory.TIME,
                fields = listOf(
                    FieldSchema.Text("name", "Stopwatch name", true),
                    FieldSchema.Duration("elapsedMs", "Elapsed duration", true),
                ),
                keywords = setOf("stopwatch", "trigger", "elapsed", "duration"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.stopwatch_reached") return@registerEvent false
            val wanted = feature.config.string("name")
            if (wanted.isNotBlank() && ctx.event.payload.string("name") != wanted) return@registerEvent false
            val threshold = feature.config["elapsedMs"].numberOrNull() ?: 0.0
            val actual = (ctx.event.payload["elapsedMs"] as? ConfigValue.NumberValue)?.value ?: return@registerEvent false
            actual >= threshold && actual < threshold + 1_000.0
        }
    }

    private fun registerAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        block: (String) -> Boolean,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION,
                title, "$title for a named YAuto stopwatch",
                FeatureCategory.TIME,
                fields = listOf(FieldSchema.Text("name", "Stopwatch name", true)),
                keywords = setOf("stopwatch", "timer", title.lowercase()),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val ok = name.isNotBlank() && block(name)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok))
        }
    }
}

private data class StopwatchEntry(
    var accumulatedMs: Long,
    var startedAtElapsedMs: Long?,
)

internal data class StopwatchSnapshot(val elapsedMs: Long, val running: Boolean)

internal object StopwatchRuntime {
    private val values = ConcurrentHashMap<String, StopwatchEntry>()

    @Synchronized fun start(name: String) {
        values[name] = StopwatchEntry(0L, android.os.SystemClock.elapsedRealtime())
    }

    @Synchronized fun pause(name: String): Boolean {
        val entry = values[name] ?: return false
        val started = entry.startedAtElapsedMs ?: return true
        entry.accumulatedMs += android.os.SystemClock.elapsedRealtime() - started
        entry.startedAtElapsedMs = null
        return true
    }

    @Synchronized fun resume(name: String): Boolean {
        val entry = values[name] ?: return false
        if (entry.startedAtElapsedMs == null) entry.startedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        return true
    }

    @Synchronized fun reset(name: String) {
        values[name] = StopwatchEntry(0L, android.os.SystemClock.elapsedRealtime())
    }

    @Synchronized fun stop(name: String): Boolean {
        val exists = pause(name)
        if (exists) values.remove(name)
        return exists
    }

    @Synchronized fun snapshot(name: String): StopwatchSnapshot? {
        val entry = values[name] ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = entry.accumulatedMs + (entry.startedAtElapsedMs?.let { now - it } ?: 0L)
        return StopwatchSnapshot(elapsed, entry.startedAtElapsedMs != null)
    }

    fun names(): Set<String> = values.keys
}

class StopwatchEventSource : AndroidEventSource {
    override val id: String = "android.stopwatch"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null
    private val emittedSeconds = ConcurrentHashMap<String, Long>()

    override fun start(emitter: RuntimeEventEmitter) {
        job = scope.launch {
            while (isActive) {
                StopwatchRuntime.names().forEach { name ->
                    val snapshot = StopwatchRuntime.snapshot(name) ?: return@forEach
                    if (!snapshot.running) return@forEach
                    val second = snapshot.elapsedMs / 1000L
                    if (second > 0 && emittedSeconds.put(name, second) != second) {
                        emitter.emit(
                            com.yagay.yauto.core.model.RuntimeEvent(
                                typeId = "android.event.stopwatch_reached",
                                payload = mapOf(
                                    "name" to ConfigValue.StringValue(name),
                                    "elapsedMs" to ConfigValue.NumberValue(snapshot.elapsedMs.toDouble()),
                                ),
                                source = id,
                            )
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        emittedSeconds.clear()
    }
}
