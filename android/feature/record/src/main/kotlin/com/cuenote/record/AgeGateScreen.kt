package com.cuenote.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuenote.ui.Fraunces
import com.cuenote.ui.StartButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Asked once, before anything else. The question is neutral on purpose: it
 * doesn't say what age is needed, so nobody is nudged towards a "right" answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgeGateScreen(onAnswer: (LocalDate) -> Unit) {
    val thisYear = LocalDate.now().year
    val picker = rememberDatePickerState(
        initialDisplayMode = DisplayMode.Input,
        yearRange = 1900..thisYear,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int) = year <= thisYear
        },
    )
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Welcome to Cue Note",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        )
        Text("Before we start, what's your date of birth? We only use it to check you can use the app, and we don't keep it.")
        DatePicker(
            state = picker,
            showModeToggle = false,
            title = null,
            headline = null,
        )
        StartButton(
            "Continue",
            enabled = picker.selectedDateMillis != null,
            dot = false,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                picker.selectedDateMillis?.let {
                    onAnswer(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            },
        )
    }
}

/** Shown from then on to anyone under 13. There's no way past it in the app. */
@Composable
fun AgeBlockedScreen() {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Sorry, Cue Note isn't available yet",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        )
        Text("Cue Note is for people aged 13 and over. Nothing has been recorded or saved about you.")
    }
}
