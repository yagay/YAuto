package com.yagay.yauto

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.R as TextR
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
internal fun ShortXWorkdayScreen(context: Context) {
    val scope = rememberCoroutineScope()
    var region by remember { mutableStateOf("UK") }
    var year by remember { mutableStateOf(LocalDate.now().year.toString()) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val saved = stringResource(TextR.string.sx_saved)
    val failed = stringResource(TextR.string.sx_workday_failed)
    val working = stringResource(TextR.string.sx_workday_yes)
    val holiday = stringResource(TextR.string.sx_workday_no)

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(TextR.string.sx_workday_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("UK", "CN").forEach { entry ->
                    FilterChip(selected = region == entry, onClick = { region = entry; refreshToken++ },
                        label = { Text(entry) })
                }
            }
        }
        item {
            OutlinedTextField(year, { year = it.take(4) },
                label = { Text(stringResource(TextR.string.sx_workday_year)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                pending = true
                scope.launch {
                    status = runCatching {
                        val count = WorkdayCalendarStore.refresh(context, region,
                            year.toIntOrNull() ?: error("Invalid year"))
                        "$saved ($count)"
                    }.getOrElse { failed + ": " + (it.message ?: "") }
                    refreshToken++
                    pending = false
                }
            }, enabled = !pending) { Text(stringResource(TextR.string.sx_workday_refresh)) }
            Text(stringResource(TextR.string.sx_workday_entries,
                WorkdayCalendarStore.count(context, region) + refreshToken * 0))
        }
        item {
            OutlinedTextField(date, { date = it.take(10) },
                label = { Text(stringResource(TextR.string.sx_workday_date)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val selected = runCatching { LocalDate.parse(date) }.getOrNull()
            if (selected != null) {
                Text(if (WorkdayCalendarStore.status(context, region, selected)) working else holiday)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        WorkdayCalendarStore.manual(context, region, selected, false)
                        refreshToken++
                    }) { Text(working) }
                    Button(onClick = {
                        WorkdayCalendarStore.manual(context, region, selected, true)
                        refreshToken++
                    }) { Text(holiday) }
                    TextButton(onClick = {
                        WorkdayCalendarStore.clear(context, region, selected)
                        refreshToken++
                    }) { Text(stringResource(TextR.string.sx_clear)) }
                }
            }
        }
        item { status?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
