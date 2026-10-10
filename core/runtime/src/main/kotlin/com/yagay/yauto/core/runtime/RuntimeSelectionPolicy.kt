package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.storage.WorkspaceData

/** Canonical runtime identity and category selection used by manual and event runs. */
internal object RuntimeSelectionPolicy {
    fun categoryEnabled(category: String?, disabledCategories: Set<String>): Boolean {
        val name = category?.trim().orEmpty()
        return name.isBlank() || name !in disabledCategories
    }

    fun resolveAutomation(workspace: WorkspaceData, target: String): Automation? {
        workspace.automations.firstOrNull { it.id.value == target }?.let { return it }
        val matches = workspace.automations.filter { it.name.equals(target, ignoreCase = true) }
        return matches.singleOrNull()
    }
}
