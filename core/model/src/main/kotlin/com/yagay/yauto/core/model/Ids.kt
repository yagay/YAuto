package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class AutomationId(val value: String)

@Serializable
@JvmInline
value class FlowId(val value: String)

@Serializable
@JvmInline
value class NodeId(val value: String)

@Serializable
@JvmInline
value class ExecutionId(val value: String)

@Serializable
@JvmInline
value class WorkspaceId(val value: String)
