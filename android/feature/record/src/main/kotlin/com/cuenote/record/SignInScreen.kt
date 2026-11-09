package com.cuenote.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuenote.data.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(auth: AuthRepository, signedInEmail: String?, linkStatus: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.padding(16.dp)) {
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.padding(4.dp))
        if (signedInEmail == null) linkStatus?.let {
            Text(it, color = if (it.startsWith("Sign-in failed") || it.startsWith("That link")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.padding(4.dp))
        }
        when {
            !auth.isConfigured -> Text(
                "Sign-in isn't set up in this build yet. Recording works without it; uploads start in a later version.",
            )
            signedInEmail != null -> {
                Text("Signed in as $signedInEmail")
                Button(onClick = { scope.launch { auth.signOut() } }) { Text("Sign out") }
            }
            else -> {
                Text("We'll email you a link. Open it on this phone to sign in. No password needed.")
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = email.contains('@'),
                    onClick = {
                        auth.clearLinkStatus()
                        scope.launch {
                            message = runCatching { auth.sendMagicLink(email) }
                                .fold({ "Check your email for the sign-in link." }, { "Couldn't send: ${it.message}" })
                        }
                    },
                ) { Text("Email me a link") }
                message?.let { Text(it) }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
