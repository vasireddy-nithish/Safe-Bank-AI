package com.safebank.ai.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.BuildConfig
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.DemoLoginRequest
import com.safebank.ai.data.network.DevUsernameLoginRequest
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.ml.BehaviourProfileManager
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.scaffold.MainScaffold
import com.safebank.ai.ui.screens.enrolment.BehaviourEnrolmentScreen
import com.safebank.ai.ui.screens.register.RegisterScreen
import com.safebank.ai.ui.screens.restricted.RestrictedScreen
import com.safebank.ai.ui.screens.welcome.WelcomeScreen
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AuthRoute { WELCOME, REGISTER, LOGIN, ENROLMENT }

/**
 * Server session gate. Local biometric success never creates a server session.
 * Coordinates Welcome, Registration, Demo/Username Sign-In, Behavioural Enrolment,
 * Restricted Protective Mode, and MainScaffold.
 */
@Composable
fun AuthenticationGate() {
    val context = LocalContext.current
    val sessions = remember { SecureSessionManager.getInstance(context) }
    val api = remember { BankingApiClient(sessions).apiService }
    val profileManager = remember { BehaviourProfileManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var authenticated by remember { mutableStateOf(false) }
    var restricted by remember { mutableStateOf(false) }
    var needsEnrolment by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf(AuthRoute.WELCOME) }
    var loginUsername by remember { mutableStateOf("usr_dev_alex_chen") }

    DisposableEffect(sessions) {
        val listener: (Boolean) -> Unit = { active ->
            if (!active) {
                authenticated = false
                route = AuthRoute.WELCOME
            }
        }
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
                    needsEnrolment = !restricted && !profileManager.isEnrolmentComplete()
                } else {
                    error = "Session verification failed (${result.code()})."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Cannot reach the bank server. Check your connection."
        } finally {
            busy = false
        }
    }

    if (busy) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (restricted) {
        RestrictedScreen(
            onSignOut = {
                scope.launch {
                    busy = true
                    try {
                        val response = api.logout()
                        if (response.isSuccessful) {
                            sessions.clearSession()
                            restricted = false
                            authenticated = false
                            route = AuthRoute.WELCOME
                        } else {
                            error = "Unable to sign out. Please retry."
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        sessions.clearSession()
                        restricted = false
                        authenticated = false
                        route = AuthRoute.WELCOME
                    } finally {
                        busy = false
                    }
                }
            }
        )
        return
    }

    if (authenticated) {
        if (needsEnrolment || route == AuthRoute.ENROLMENT) {
            BehaviourEnrolmentScreen(
                onEnrolmentComplete = {
                    needsEnrolment = false
                    route = AuthRoute.WELCOME
                },
                onSkip = {
                    needsEnrolment = false
                    route = AuthRoute.WELCOME
                }
            )
        } else {
            MainScaffold()
        }
        return
    }

    when (route) {
        AuthRoute.WELCOME -> {
            WelcomeScreen(
                onNavigateToRegister = {
                    error = null
                    route = AuthRoute.REGISTER
                },
                onNavigateToLogin = {
                    error = null
                    route = AuthRoute.LOGIN
                }
            )
        }

        AuthRoute.REGISTER -> {
            RegisterScreen(
                onRegistrationComplete = { registeredUsername ->
                    loginUsername = registeredUsername
                    route = AuthRoute.LOGIN
                },
                onNavigateBack = {
                    route = AuthRoute.WELCOME
                }
            )
        }

        AuthRoute.LOGIN, AuthRoute.ENROLMENT -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDark)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Sign In to SafeBank AI",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (BuildConfig.DEBUG) {
                        "Development demo login — not passkey authentication. Requires seeded or registered account and an enabled local backend."
                    } else {
                        "Passkey authentication is not yet configured in release builds."
                    },
                    color = StatusWarning,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(20.dp))

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (BuildConfig.DEBUG) {
                    SafeBankTextField(
                        value = loginUsername,
                        onValueChange = { loginUsername = it; error = null },
                        label = "Username (demo account)",
                        placeholder = "usr_dev_alex_chen"
                    )

                    Spacer(Modifier.height(16.dp))

                    SafeBankButton(
                        text = "Sign In",
                        variant = SafeBankButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val trimmed = loginUsername.trim()
                                    val result = if (trimmed == "usr_dev_alex_chen" || trimmed.isEmpty()) {
                                        api.demoLogin(DemoLoginRequest("usr_dev_alex_chen", "dev_pixel_8_pro_01"))
                                    } else {
                                        api.demoLoginByUsername(DevUsernameLoginRequest(trimmed))
                                    }
                                    val body = result.body()
                                    if (result.isSuccessful && body != null) {
                                        sessions.saveSession(body.access_token, body.user_id)
                                        // Check server status in case account has an active duress incident
                                        val statusResp = api.securityStatus()
                                        restricted = if (statusResp.isSuccessful) {
                                            statusResp.body()?.restricted == true
                                        } else {
                                            body.is_restricted
                                        }
                                        authenticated = true
                                        needsEnrolment = !restricted && !profileManager.isEnrolmentComplete()
                                    } else {
                                        error = "Demo login unavailable (${result.code()}). Check backend setup."
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    error = "Cannot reach the bank server."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { route = AuthRoute.WELCOME },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Back to Welcome", color = TextMuted)
                }
            }
        }
    }
}
