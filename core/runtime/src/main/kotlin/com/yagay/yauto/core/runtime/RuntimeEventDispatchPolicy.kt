package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.engine.AutomationPhase
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.storage.WorkspaceData

/** Shared pure policy for dispatch eligibility and state-machine phase transitions. */
internal object RuntimeEventDispatchPolicy {
    fun eligible(workspace: WorkspaceData, callStack: List<String>, statesOnly: Boolean): List<Automation> =
        workspace.automations.filter { automation ->
            automation.enabled &&
                automation.id.value !in callStack &&
                RuntimeSelectionPolicy.categoryEnabled(automation.category, workspace.disabledCategories) &&
                (!statesOnly || automation.activation.states.isNotEmpty())
        }

    fun phases(
        stateful: Boolean,
        gateOpen: Boolean,
        wasActive: Boolean,
        eventMatches: Boolean,
        conditionMatches: Boolean,
    ): List<AutomationPhase> = buildList {
        if (stateful) {
            if (gateOpen && !wasActive) add(AutomationPhase.ENTER)
            if (gateOpen && eventMatches) add(AutomationPhase.EVENT)
            if (!gateOpen && wasActive) add(AutomationPhase.EXIT)
        } else if (eventMatches && conditionMatches) {
            add(AutomationPhase.EVENT)
        }
    }
}
