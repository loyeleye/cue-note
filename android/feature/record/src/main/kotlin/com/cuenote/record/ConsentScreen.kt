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

/**
 * Nothing records until both people agree. The first time, both read the
 * full text and tick their own box; after that, one tap per lesson.
 */
@Composable
fun ConsentScreen(store: ConsentStore, onConsented: (consentAt: Long) -> Unit, onCancel: () -> Unit) {
    var setup by rememberSaveable { mutableStateOf(store.firstSetup() != null) }
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
            Spacer(Modifier.padding(8.dp))
            Button(onClick = { onConsented(System.currentTimeMillis()) }, modifier = Modifier.fillMaxWidth()) {
                Text("We both agree: start recording")
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
