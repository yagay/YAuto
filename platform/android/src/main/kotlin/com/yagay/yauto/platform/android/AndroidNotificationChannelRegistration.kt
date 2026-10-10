package com.yagay.yauto.platform.android

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*


internal fun AndroidFinalParityFeaturePack.registerNotificationChannels(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channels.query"), FeatureKind.ACTION,
                "Query notification channels",
                "Return notification channels owned by YAuto, including importance, sound and group",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store channel list", true)),
                keywords = setOf("notification channel", "importance", "sound", "group", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                notifications.notificationChannels.map { channel ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "id" to ConfigValue.StringValue(channel.id),
                            "name" to ConfigValue.StringValue(channel.name?.toString().orEmpty()),
                            "description" to ConfigValue.StringValue(channel.description.orEmpty()),
                            "importance" to ConfigValue.NumberValue(channel.importance.toDouble()),
                            "group" to ConfigValue.StringValue(channel.group.orEmpty()),
                            "sound" to ConfigValue.StringValue(channel.sound?.toString().orEmpty()),
                            "vibration" to ConfigValue.BooleanValue(channel.shouldVibrate()),
                            "lights" to ConfigValue.BooleanValue(channel.shouldShowLights()),
                            "bypassDnd" to ConfigValue.BooleanValue(channel.canBypassDnd()),
                            "lockscreenVisibility" to ConfigValue.NumberValue(channel.lockscreenVisibility.toDouble()),
                        )
                    )
                }
            )
            store(feature, ctx, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel.create"), FeatureKind.ACTION,
                "Create or update notification channel",
                "Create a YAuto notification channel or update fields Android still allows the app to change",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("channelId", "Channel ID", true),
                    FieldSchema.Text("name", "Name", true),
                    FieldSchema.Text("description", "Description"),
                    FieldSchema.Choice("importance", "Importance", true, listOf("none", "min", "low", "default", "high", "max")),
                    FieldSchema.Text("groupId", "Channel group ID"),
                    FieldSchema.Toggle("vibration", "Enable vibration"),
                    FieldSchema.Toggle("lights", "Enable lights"),
                ),
                keywords = setOf("notification channel", "create channel", "importance", "vibration", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val channelId = feature.config.string("channelId").resolveVariables(ctx.variables).trim()
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (!safeId(channelId) || name.isBlank()) return@registerAction ActionExecutionResult(false)
            val importance = when (feature.config.string("importance", "default")) {
                "none" -> NotificationManager.IMPORTANCE_NONE
                "min" -> NotificationManager.IMPORTANCE_MIN
                "low" -> NotificationManager.IMPORTANCE_LOW
                "high" -> NotificationManager.IMPORTANCE_HIGH
                "max" -> NotificationManager.IMPORTANCE_MAX
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = feature.config.string("description").resolveVariables(ctx.variables).take(1000)
                group = feature.config.string("groupId").resolveVariables(ctx.variables).trim().takeIf(::safeId)
                enableVibration(feature.config.boolean("vibration", false))
                enableLights(feature.config.boolean("lights", false))
            }
            runCatching {
                notifications.createNotificationChannel(channel)
                ActionExecutionResult(true, ConfigValue.StringValue(channelId))
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel.delete"), FeatureKind.ACTION,
                "Delete notification channel",
                "Delete a notification channel owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("channelId", "Channel ID", true)),
                keywords = setOf("notification channel", "delete channel", "remove"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val channelId = feature.config.string("channelId").resolveVariables(ctx.variables).trim()
            if (!safeId(channelId)) return@registerAction ActionExecutionResult(false)
            runCatching { notifications.deleteNotificationChannel(channelId); ActionExecutionResult(true) }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_groups.query"), FeatureKind.ACTION,
                "Query notification channel groups",
                "Return notification channel groups owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store group list", true)),
                keywords = setOf("notification channel group", "channel group"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                notifications.notificationChannelGroups.map { group ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "id" to ConfigValue.StringValue(group.id),
                            "name" to ConfigValue.StringValue(group.name?.toString().orEmpty()),
                            "description" to ConfigValue.StringValue(group.description.orEmpty()),
                            "blocked" to ConfigValue.BooleanValue(group.isBlocked),
                            "channels" to ConfigValue.ListValue(group.channels.map { ConfigValue.StringValue(it.id) }),
                        )
                    )
                }
            )
            store(feature, ctx, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_group.create"), FeatureKind.ACTION,
                "Create notification channel group",
                "Create or update a notification channel group owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("groupId", "Group ID", true),
                    FieldSchema.Text("name", "Name", true),
                    FieldSchema.Text("description", "Description"),
                ),
                keywords = setOf("notification group", "channel group", "create"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val groupId = feature.config.string("groupId").resolveVariables(ctx.variables).trim()
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (!safeId(groupId) || name.isBlank()) return@registerAction ActionExecutionResult(false)
            runCatching {
                notifications.createNotificationChannelGroup(
                    NotificationChannelGroup(groupId, name).apply {
                        description = feature.config.string("description").resolveVariables(ctx.variables).take(1000)
                    }
                )
                ActionExecutionResult(true, ConfigValue.StringValue(groupId))
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_group.delete"), FeatureKind.ACTION,
                "Delete notification channel group",
                "Delete a notification channel group owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("groupId", "Group ID", true)),
                keywords = setOf("notification group", "channel group", "delete"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val groupId = feature.config.string("groupId").resolveVariables(ctx.variables).trim()
            if (!safeId(groupId)) return@registerAction ActionExecutionResult(false)
            runCatching { notifications.deleteNotificationChannelGroup(groupId); ActionExecutionResult(true) }.getOrElse { failure(it) }
        }
    }

