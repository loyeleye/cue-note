package com.cuenote.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.OutlinedButton
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
 *
 * Below that, teachers who keep their lessons on this phone: a name only,
 * kept on the phone, with no invite, no account and no dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteTeachersScreen(
    shares: ShareRepository,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    /** Where the local-only teachers are kept; null hides that part. */
    lessonTeachers: com.cuenote.data.LessonTeacherStore? = null,
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
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).fillMaxSize()) {
            if (!signedIn) {
                Text("Sign in first, so your teacher knows who the invite is from.")
                Spacer(Modifier.padding(4.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
                lessonTeachers?.let { LocalOnlyTeachers(it) }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                teachers.orEmpty().forEach { t ->
                    androidx.compose.runtime.key(t.shareId) {
                        TeacherRow(
                            t,
                            enabled = !busy,
                            onRevoke = { confirmRevoke = t },
                            onRestrict = { on -> run { shares.setRestricted(t, on); null } },
                        )
                    }
                }
            }
            lessonTeachers?.let { LocalOnlyTeachers(it) }
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
                            else -> "Active: sees all your lessons (other teachers' once they agree)"
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
                            if (t.restrictedToTaught) "Off: only lessons marked as theirs."
                            else "Lessons your other teachers taught show once each of them agrees.",
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

/**
 * Teachers who keep their lessons on this phone. Picking one before a lesson
 * keeps that lesson off Cue Note's servers and away from AI: no invite, no
 * dashboard, and their name stays on the phone too.
 */
@Composable
private fun LocalOnlyTeachers(store: com.cuenote.data.LessonTeacherStore) {
    var names by remember { mutableStateOf(store.localOnlyTeachers) }
    var name by rememberSaveable { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val m = MaterialTheme.colorScheme
    fun add() {
        if (com.cuenote.data.LessonTeacher.tidy(name) == null) return
        store.addLocalOnlyTeacher(name); names = store.localOnlyTeachers; name = ""
    }
    Spacer(Modifier.padding(12.dp))
    Text("Teachers who keep lessons on this phone", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.padding(2.dp))
    Text(
        "For a teacher who'd rather their voice didn't go online. Their lessons record and get the phone's own notes, " +
            "but nothing from them goes to Cue Note's servers or to an AI, and they don't use the dashboard. " +
            "No invite is sent; their name stays on this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = m.onSurfaceVariant,
    )
    Spacer(Modifier.padding(4.dp))
    names.forEach { n ->
        androidx.compose.runtime.key(n) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Lock, contentDescription = null, tint = m.primary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(n, style = MaterialTheme.typography.titleSmall)
                        Text("Lessons stay on this phone", style = MaterialTheme.typography.bodySmall, color = m.primary)
                    }
                    TextButton(onClick = { confirmRemove = n }) { Text("Remove") }
                }
            }
        }
    }
    OutlinedTextField(
        name, { name = it.take(com.cuenote.data.LessonTeacher.MAX_NAME) },
        label = { Text("Teacher's name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.padding(4.dp))
    OutlinedButton(enabled = name.isNotBlank(), onClick = { add() }, modifier = Modifier.fillMaxWidth()) {
        Text("Add a teacher who keeps lessons on this phone")
    }
    confirmRemove?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $n?") },
            text = {
                Text(
                    "$n won't be offered before a lesson any more. Lessons already marked as theirs stay on this phone; " +
                        "change one from the lesson itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = { store.removeLocalOnlyTeacher(n); names = store.localOnlyTeachers; confirmRemove = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
}
