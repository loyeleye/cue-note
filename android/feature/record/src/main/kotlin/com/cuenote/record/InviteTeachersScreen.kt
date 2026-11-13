package com.cuenote.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuenote.data.InvitedTeacher
import com.cuenote.data.ShareRepository
import kotlinx.coroutines.launch

/**
 * The student invites teachers by email, sees which invites are waiting and
 * which teachers can already see their lessons, and takes any of them back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteTeachersScreen(
    shares: ShareRepository,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var teachers by remember { mutableStateOf<List<InvitedTeacher>?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<InvitedTeacher?>(null) }

    fun run(action: suspend () -> String?) {
        busy = true
        scope.launch {
            message = runCatching { action() }.getOrElse { "Something went wrong: ${it.message}" }
            teachers = runCatching { shares.list() }.getOrElse { teachers }
            busy = false
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) run { null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your teachers") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            if (!signedIn) {
                Text("Sign in first, so your teacher knows who the invite is from.")
                Spacer(Modifier.padding(4.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
                return@Column
            }

            Text(
                "Invite a teacher by email. Once they accept on the Cue Note website with that " +
                    "email, they can see your lessons and edit the feedback.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(4.dp))
            fun send() = run {
                shares.invite(email, teachers.orEmpty())
                    ?: "Invite sent to ${email.trim()}. Ask them to sign in on the website with that email."
                        .also { email = "" }
            }
            OutlinedTextField(
                email, { email = it; message = null },
                label = { Text("Teacher's email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy && email.isNotBlank()) send() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(4.dp))
            Button(enabled = !busy && email.isNotBlank(), onClick = { send() }, modifier = Modifier.fillMaxWidth()) {
                Text("Send invite")
            }
            message?.let {
                Spacer(Modifier.padding(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.padding(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Invited", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { run { null } }) { Text("Refresh") }
            }
            when {
                teachers == null -> Text(if (busy) "Loading…" else "Couldn't load your invites. Tap Refresh.")
                teachers!!.isEmpty() -> Text("No teachers yet.")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(teachers.orEmpty(), key = { it.shareId }) { t ->
                    TeacherRow(
                        t,
                        enabled = !busy,
                        onRevoke = { confirmRevoke = t },
                        onRestrict = { on -> run { shares.setRestricted(t, on); null } },
                    )
                }
            }
        }
    }

    confirmRevoke?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(if (t.active) "Remove ${t.title}?" else "Cancel this invite?") },
            text = {
                Text(
                    if (t.active) "They will stop seeing your lessons straight away. You can invite them again later."
                    else "The invite to ${t.email} will no longer work.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = null
                    run { shares.revoke(t); if (t.active) "Removed ${t.title}." else "Invite to ${t.email} cancelled." }
                }) { Text(if (t.active) "Remove" else "Cancel invite") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TeacherRow(t: InvitedTeacher, enabled: Boolean, onRevoke: () -> Unit, onRestrict: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                    if (t.title != t.email) Text(t.email, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            !t.active -> "Invite sent. Waiting on them."
                            t.restrictedToTaught -> "Active: sees only the lessons they taught"
                            else -> "Active: can see all your lessons"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(enabled = enabled, onClick = onRevoke) { Text(if (t.active) "Remove" else "Cancel") }
            }
            if (t.active) {
                // Lessons are linked to a teacher by "Who's teaching today?"
                // (or later, from the lesson). By default a teacher sees only
                // those; this lets them see every lesson.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Can see all my lessons", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off: only lessons marked as theirs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = !t.restrictedToTaught, enabled = enabled, onCheckedChange = { all -> onRestrict(!all) })
                }
            }
        }
    }
}
