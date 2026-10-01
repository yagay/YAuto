package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.EmptySourceFeatureMapper

/**
 * Tasker numeric action/context mappings live here, separate from XML parsing.
 * Start conservative: unknown codes are preserved until verified against exports.
 */
object TaskerMappings {
    val mapper = EmptySourceFeatureMapper
}
