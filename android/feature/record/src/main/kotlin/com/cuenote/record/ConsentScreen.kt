package com.cuenote.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.ConsentStore
import com.cuenote.data.InvitedTeacher

/**
 * Nothing records until both people agree. The first time, both read the
 * full text and tick their own box; after that, one tap per lesson.
 *
 * With more than one teacher, the student also says who is teaching today,
 * so each teacher can be shown only their own lessons. [teachers] are the
 * active ones; with one, they're picked without asking.
 *
 * Posture tracking is a separate opt-in for each lesson, off by default,
 * for when the student or teacher asks for it.
 */
@Composable
fun ConsentScreen(
    store: ConsentStore,
    teachers: List<InvitedTeacher>,
    lastPicked: String?,
    onConsented: (consentAt: Long, teacherId: String?, posture: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var setup by rememberSaveable { mutableStateOf(store.firstSetup() != null) }
    val choices = teachers.filter { it.active && it.teacherId != null }
    var picked by rememberSaveable(choices.map { it.teacherId }) {
        mutableStateOf(
            when {
                choices.size == 1 -> choices[0].teacherId
                choices.any { it.teacherId == lastPicked } -> lastPicked
                else -> null
            },
        )
    }
    var posture by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Consent to record", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.padding(4.dp))
        Text(ConsentStore.TEXT, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(8.dp))
        if (!setup) {
            FirstSetup(onDone = { s, t -> store.saveFirstSetup(s, t); setup = true })
        } else {
            val names = store.firstSetup()!!
            Text(
                "${names.studentName} and ${names.teacherName}, do you both agree to record this lesson?",
                style = MaterialTheme.typography.titleMedium,
            )
            if (choices.size > 1) {
                Spacer(Modifier.padding(8.dp))
                Text("Who's teaching today?", style = MaterialTheme.typography.titleMedium)
                choices.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == t.teacherId, onClick = { picked = t.teacherId })
                        Text(t.title)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = picked == null, onClick = { picked = null })
                    Text("Someone else, or not sure")
                }
            }
            Spacer(Modifier.padding(8.dp))
            AgreeRow(posture, { posture = it }, "Track posture this lesson")
            if (posture) {
                Text(POSTURE_TEXT, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.padding(8.dp))
            Button(onClick = { onConsented(System.currentTimeMillis(), picked, posture) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (posture) "We both agree: record, with posture" else "We both agree: start recording")
            }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun FirstSetup(onDone: (student: String, teacher: String) -> Unit) {
    var student by rememberSaveable { mutableStateOf("") }
    var teacher by rememberSaveable { mutableStateOf("") }
    var studentOk by rememberSaveable { mutableStateOf(false) }
    var teacherOk by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(student, { student = it }, label = { Text("Student's name") }, modifier = Modifier.fillMaxWidth())
    AgreeRow(studentOk, { studentOk = it }, "I'm the student and I agree")
    OutlinedTextField(teacher, { teacher = it }, label = { Text("Teacher's name") }, modifier = Modifier.fillMaxWidth())
    AgreeRow(teacherOk, { teacherOk = it }, "I'm the teacher and I agree")
    Button(
        onClick = { onDone(student.trim(), teacher.trim()) },
        enabled = student.isNotBlank() && teacher.isNotBlank() && studentOk && teacherOk,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save consent") }
}

@Composable
private fun AgreeRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

private val POSTURE_TEXT = """
    The phone's camera watches the student while they sing, and works out body points (head, shoulders, hips) on the phone. No video is saved. A still photo is kept when it flags chin forward, raised shoulders or a head tilt. Only the student is tracked, though the teacher can appear in a flagged photo if they're in view. All of it stays on this phone.
""".trimIndent()
