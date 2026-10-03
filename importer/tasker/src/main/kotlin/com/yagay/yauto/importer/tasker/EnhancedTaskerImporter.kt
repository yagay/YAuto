package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.AutomationImporter
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.importer.ImportResult

/** Keeps Tasker XML lossless while attaching native YAuto upgrade targets to preserved nodes. */
class EnhancedTaskerImporter(
    private val delegate: TaskerImporter = TaskerImporter(),
) : AutomationImporter {
    override val id: String get() = delegate.id
    override val displayName: String get() = delegate.displayName

    override fun confidence(input: ImportInput): Int = delegate.confidence(input)

    override fun import(input: ImportInput): ImportResult {
        val result = delegate.import(input)
        if (result.issues.isEmpty()) return result
        return result.copy(
            issues = result.issues.map { issue ->
                if (issue.suggestedFeatureId != null) issue
                else issue.copy(suggestedFeatureId = suggested(issue.sourceType))
            }
        )
    }

    private fun suggested(sourceType: String?): String? {
        val raw = sourceType.orEmpty()
        return when {
            raw.startsWith("code:") -> TaskerFeatureSuggestions.action(raw.substringAfter(':'))
            raw.startsWith("TaskerAction:") -> TaskerFeatureSuggestions.action(raw.substringAfter(':'))
            raw.startsWith("Event:") -> TaskerFeatureSuggestions.event(raw.substringAfter(':'))
            raw.startsWith("State:") -> TaskerFeatureSuggestions.state(raw.substringAfter(':'))
            else -> null
        }
    }
}
