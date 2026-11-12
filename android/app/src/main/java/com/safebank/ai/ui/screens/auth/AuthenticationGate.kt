package com.safebank.ai.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.safebank.ai.BuildConfig
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.DemoLoginRequest
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.ui.scaffold.MainScaffold
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Server session gate. Local biometric success never creates a server session. */
@Composable
fun AuthenticationGate() {
    val context = LocalContext.current
    val sessions = remember { SecureSessionManager.getInstance(context) }
    val api = remember { BankingApiClient(sessions).apiService }
    val scope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(false) }
    var restricted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(sessions) {
        val listener: (Boolean) -> Unit = { active -> if (!active) authenticated = false }
        sessions.addAuthListener(listener)
        onDispose { sessions.removeAuthListener(listener) }
    }
    LaunchedEffect(Unit) {
        try {
            if (sessions.hasActiveSession()) {
                val result = api.securityStatus()
                if (result.isSuccessful) {
                    restricted = result.body()?.restricted == true
                    authenticated = true
                } else error = "Session verification failed (${result.code()})."
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "Cannot reach the bank server. Check your connection."
        } finally { busy = false }
    }
    if (authenticated && !restricted) {
        MainScaffold()
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("SafeBank AI", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text(if (restricted) "Account access is temporarily limited. Contact your bank for assistance."
             else "Welcome. Sign in to access your simulated bank accounts.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        if (!restricted && BuildConfig.DEBUG) {
            Text("Development demo login — not passkey authentication. Requires seeded accounts and an enabled local backend.",
                 modifier = Modifier.padding(vertical = 16.dp))
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        val result = api.demoLogin(DemoLoginRequest("usr_dev_alex_chen", "dev_pixel_8_pro_01"))
                        val body = result.body()
                        if (result.isSuccessful && body != null) {
                            sessions.saveSession(body.access_token, body.user_id)
                            restricted = body.is_restricted
                            authenticated = true
                        } else error = "Demo login unavailable (${result.code()}). Check backend setup."
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "Cannot reach the bank server."
                    } finally { busy = false }
                }
            }) { Text("Sign in to demo account") }
        }
        if (!BuildConfig.DEBUG && !restricted) Text("Passkey authentication is not yet configured.")
        if (restricted) Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val response = api.logout()
                    if (response.isSuccessful) {
                        sessions.clearSession(); restricted = false; authenticated = false
                    } else error = "Unable to sign out. Please retry."
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { error = "Unable to reach the bank server."
                } finally { busy = false }
            }
        }) { Text("Sign out") }
    }
}
