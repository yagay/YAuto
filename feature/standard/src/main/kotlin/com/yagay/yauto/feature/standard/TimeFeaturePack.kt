package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TimeFeaturePack(private val clock: Clock = Clock.systemDefaultZone()) : FeaturePack {
    override val id = "standard.time"

    override fun install(registry: FeatureRegistry) {
        registerStateAndCondition(
            registry,
            "time_window",
            "Time window",
            "Match the current local time, including windows that cross midnight",
            listOf(
                FieldSchema.Text("start", "Start (HH:mm)", true),
                FieldSchema.Text("end", "End (HH:mm)", true),
            ),
            setOf("time", "schedule", "clock", "时间", "时段"),
        ) { feature ->
            val start = parseTime(feature.config.string("start"))
            val end = parseTime(feature.config.string("end"))
            val now = ZonedDateTime.now(clock).toLocalTime().withSecond(0).withNano(0)
            if (start == end) true
            else if (start < end) !now.isBefore(start) && now.isBefore(end)
            else !now.isBefore(start) || now.isBefore(end)
        }

        registerStateAndCondition(
            registry,
            "weekday",
            "Day of week",
            "Match ISO weekdays: 1=Monday through 7=Sunday",
            listOf(FieldSchema.Text("days", "Days (comma separated, 1-7)", true)),
            setOf("weekday", "day", "week", "星期", "周"),
        ) { feature ->
            val days = feature.config.string("days").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "Days must contain ISO weekday numbers 1-7" }
            ZonedDateTime.now(clock).dayOfWeek.value in days
        }

        registerStateAndCondition(
            registry,
            "date_range",
            "Date range",
            "Match an inclusive local date range using yyyy-MM-dd",
            listOf(
                FieldSchema.Text("start", "Start date (yyyy-MM-dd)", true),
                FieldSchema.Text("end", "End date (yyyy-MM-dd)", true),
            ),
            setOf("date", "calendar", "range", "日期"),
        ) { feature ->
            val start = LocalDate.parse(feature.config.string("start"), DateTimeFormatter.ISO_LOCAL_DATE)
            val end = LocalDate.parse(feature.config.string("end"), DateTimeFormatter.ISO_LOCAL_DATE)
            require(!end.isBefore(start)) { "End date must not be before start date" }
            val today = LocalDate.now(clock)
            !today.isBefore(start) && !today.isAfter(end)
        }
    }

    private fun registerStateAndCondition(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        evaluate: (com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val stateId = "time.state.$key"
        val conditionId = "time.condition.$key"
        registry.registerState(
            FeatureDescriptor(
                FeatureId(stateId), FeatureKind.STATE, title, description, FeatureCategory.SYSTEM,
                fields = fields, keywords = keywords, ownerPackId = id,
            )
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
        registry.registerCondition(
            FeatureDescriptor(
                FeatureId(conditionId), FeatureKind.CONDITION, title, description, FeatureCategory.SYSTEM,
                fields = fields, keywords = keywords, ownerPackId = id,
            )
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
    }

    private fun parseTime(raw: String): LocalTime = LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm"))
}
