package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.InMemoryExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomationEngineTest {
    @Test fun structuredIfExecutesExpectedBranch() = runBlocking {
        val registry = FeatureRegistry()
        registry.registerAction(FeatureDescriptor(FeatureId("test.set"), FeatureKind.ACTION, "set", "", FeatureCategory.CORE)) { _, ctx ->
            ctx.variables.set("result", ConfigValue.StringValue("yes")); ActionExecutionResult(true)
        }
        val engine = AutomationEngine(registry, CapabilityClient { CapabilityResult(false) }, InMemoryExecutionTracer())
        val automation = Automation(
            AutomationId("a"), "test",
            onEvent = listOf(ActionNode.If(NodeId("if"), PredicateNode.Literal(true), listOf(ActionNode.Action(NodeId("x"), FeatureRef("test.set")))))
        )
        val result = engine.execute(automation, AutomationPhase.EVENT)
        assertTrue(result.success)
        assertEquals("yes", (result.variables["result"] as ConfigValue.StringValue).value)
    }

    @Test fun waitUntilReusesNormalConditionEvaluatorUntilItBecomesTrue() = runBlocking {
        val registry = FeatureRegistry()
        val evaluations = AtomicInteger(0)
        registry.registerCondition(
            FeatureDescriptor(FeatureId("test.eventually"), FeatureKind.CONDITION, "eventually", "", FeatureCategory.CORE)
        ) { _, _ -> evaluations.incrementAndGet() >= 3 }
        val engine = AutomationEngine(registry, CapabilityClient { CapabilityResult(false) }, InMemoryExecutionTracer())
        val automation = Automation(
            AutomationId("wait"), "wait",
            onEvent = listOf(
                ActionNode.WaitUntil(
                    NodeId("wait-node"),
                    PredicateNode.Condition(FeatureRef("test.eventually")),
                    timeoutMs = 1_000L,
                    pollIntervalMs = 10L,
                )
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertTrue(result.success)
        assertTrue(evaluations.get() >= 3)
    }

    @Test fun waitUntilFailsCleanlyWhenPredicateNeverMatches() = runBlocking {
        val registry = FeatureRegistry()
        val engine = AutomationEngine(registry, CapabilityClient { CapabilityResult(false) }, InMemoryExecutionTracer())
        val automation = Automation(
            AutomationId("timeout"), "timeout",
            onEvent = listOf(
                ActionNode.WaitUntil(
                    NodeId("wait-node"),
                    PredicateNode.Literal(false),
                    timeoutMs = 150L,
                    pollIntervalMs = 100L,
                )
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun defaultActionFailureStillStopsFollowingActions() = runBlocking {
        val registry = FeatureRegistry()
        registry.registerAction(actionDescriptor("test.fail")) { _, _ ->
            ActionExecutionResult(false, message = "expected failure")
        }
        registry.registerAction(actionDescriptor("test.after")) { _, ctx ->
            ctx.variables.set("after", ConfigValue.BooleanValue(true))
            ActionExecutionResult(true)
        }
        val engine = testEngine(registry)
        val automation = Automation(
            AutomationId("default-stop"),
            "default stop",
            onEvent = listOf(
                ActionNode.Action(NodeId("fail"), FeatureRef("test.fail")),
                ActionNode.Action(NodeId("after"), FeatureRef("test.after")),
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertFalse(result.success)
        assertNull(result.variables["after"])
    }

    @Test fun continuePolicyRunsFollowingActionAfterFailure() = runBlocking {
        val registry = FeatureRegistry()
        registry.registerAction(actionDescriptor("test.fail")) { _, _ ->
            ActionExecutionResult(false, message = "expected failure")
        }
        registry.registerAction(actionDescriptor("test.after")) { _, ctx ->
            ctx.variables.set("after", ConfigValue.BooleanValue(true))
            ActionExecutionResult(true)
        }
        val engine = testEngine(registry)
        val automation = Automation(
            AutomationId("continue"),
            "continue",
            onEvent = listOf(
                ActionNode.Action(
                    NodeId("fail"),
                    FeatureRef("test.fail"),
                    failurePolicy = ActionFailurePolicy.CONTINUE,
                ),
                ActionNode.Action(NodeId("after"), FeatureRef("test.after")),
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertTrue(result.success)
        assertEquals(ConfigValue.BooleanValue(true), result.variables["after"])
    }

    @Test fun retryPolicySucceedsWithinConfiguredAttemptLimit() = runBlocking {
        val attempts = AtomicInteger(0)
        val registry = FeatureRegistry()
        registry.registerAction(actionDescriptor("test.flaky")) { _, _ ->
            val current = attempts.incrementAndGet()
            ActionExecutionResult(current >= 3, message = "attempt $current")
        }
        val engine = testEngine(registry)
        val automation = Automation(
            AutomationId("retry-success"),
            "retry success",
            onEvent = listOf(
                ActionNode.Action(
                    NodeId("flaky"),
                    FeatureRef("test.flaky"),
                    failurePolicy = ActionFailurePolicy.RETRY,
                    retryPolicy = ActionRetryPolicy(maxAttempts = 3, delayMs = 0L),
                )
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertTrue(result.success)
        assertEquals(3, attempts.get())
    }

    @Test fun retryPolicyStopsAfterConfiguredAttemptsAreExhausted() = runBlocking {
        val attempts = AtomicInteger(0)
        val registry = FeatureRegistry()
        registry.registerAction(actionDescriptor("test.always.fail")) { _, _ ->
            attempts.incrementAndGet()
            ActionExecutionResult(false, message = "still failing")
        }
        val engine = testEngine(registry)
        val automation = Automation(
            AutomationId("retry-failure"),
            "retry failure",
            onEvent = listOf(
                ActionNode.Action(
                    NodeId("always-fail"),
                    FeatureRef("test.always.fail"),
                    failurePolicy = ActionFailurePolicy.RETRY,
                    retryPolicy = ActionRetryPolicy(maxAttempts = 3, delayMs = 0L),
                )
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertFalse(result.success)
        assertEquals(3, attempts.get())
        assertNotNull(result.error)
    }

    private fun actionDescriptor(id: String) = FeatureDescriptor(
        FeatureId(id),
        FeatureKind.ACTION,
        id,
        "test action",
        FeatureCategory.CORE,
    )

    private fun testEngine(registry: FeatureRegistry) = AutomationEngine(
        registry,
        CapabilityClient { CapabilityResult(false) },
        InMemoryExecutionTracer(),
    )
}
