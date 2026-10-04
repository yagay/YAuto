package com.yagay.yauto.feature.standard.time

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.conditionFeature
import com.yagay.yauto.core.registry.stateFeature
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun timePredicateFeatures(clock: Clock): List<FeatureDefinition> = buildList {
    addStateAndCondition(
        key = "time_window",
        title = "Time window",
        description = "Match the current local time, including windows that cross midnight",
        fields = listOf(
            FieldSchema.Text("start", "Start (HH:mm)", true),
            FieldSchema.Text("end", "End (HH:mm)", true),
        ),
        keywords = setOf("time", "schedule", "clock"),
    ) { feature ->
        val start = parseClockTime(feature.config.string("start"))
        val end = parseClockTime(feature.config.string("end"))
        val now = ZonedDateTime.now(clock).toLocalTime().withSecond(0).withNano(0)
        if (start == end) true
        else if (start < end) !now.isBefore(start) && now.isBefore(end)
        else !now.isBefore(start) || now.isBefore(end)
    }

    addStateAndCondition(
        key = "weekday",
        title = "Day of week",
        description = "Match ISO weekdays: 1=Monday through 7=Sunday",
        fields = listOf(FieldSchema.Text("days", "Days (comma separated, 1-7)", true)),
        keywords = setOf("weekday", "day", "week"),
    ) { feature ->
        val days = feature.config.string("days")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        require(days.isNotEmpty() && days.all { it in 1..7 })
        ZonedDateTime.now(clock).dayOfWeek.value in days
    }

    addStateAndCondition(
        key = "day_of_month",
        title = "Day of month",
        description = "Match local calendar day numbers from 1 through 31",
        fields = listOf(FieldSchema.Text("days", "Days (comma separated, 1-31)", true)),
        keywords = setOf("day of month", "calendar", "date", "macrodroid"),
    ) { feature ->
        val days = feature.config.string("days")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        require(days.isNotEmpty() && days.all { it in 1..31 })
        LocalDate.now(clock).dayOfMonth in days
    }

    addStateAndCondition(
        key = "month_of_year",
        title = "Month of year",
        description = "Match local calendar months from 1 through 12",
        fields = listOf(FieldSchema.Text("months", "Months (comma separated, 1-12)", true)),
        keywords = setOf("month", "calendar", "date", "macrodroid"),
    ) { feature ->
        val months = feature.config.string("months")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        require(months.isNotEmpty() && months.all { it in 1..12 })
        LocalDate.now(clock).monthValue in months
    }

    addStateAndCondition(
        key = "date_range",
        title = "Date range",
        description = "Match an inclusive local date range using yyyy-MM-dd",
        fields = listOf(
            FieldSchema.Text("start", "Start date (yyyy-MM-dd)", true),
            FieldSchema.Text("end", "End date (yyyy-MM-dd)", true),
        ),
        keywords = setOf("date", "calendar", "range"),
    ) { feature ->
        val start = LocalDate.parse(feature.config.string("start"), DateTimeFormatter.ISO_LOCAL_DATE)
        val end = LocalDate.parse(feature.config.string("end"), DateTimeFormatter.ISO_LOCAL_DATE)
        require(!end.isBefore(start))
        val today = LocalDate.now(clock)
        !today.isBefore(start) && !today.isAfter(end)
    }
}

private fun MutableList<FeatureDefinition>.addStateAndCondition(
    key: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String>,
    evaluate: (FeatureRef) -> Boolean,
) {
    add(
        stateFeature(
            timeDescriptor(
                id = "time.state.$key",
                kind = FeatureKind.STATE,
                title = title,
                description = description,
                fields = fields,
                keywords = keywords,
            )
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) },
    )
    add(
        conditionFeature(
            timeDescriptor(
                id = "time.condition.$key",
                kind = FeatureKind.CONDITION,
                title = title,
                description = description,
                fields = fields,
                keywords = keywords,
            )
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) },
    )
}

private fun parseClockTime(raw: String): LocalTime =
    LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm"))
