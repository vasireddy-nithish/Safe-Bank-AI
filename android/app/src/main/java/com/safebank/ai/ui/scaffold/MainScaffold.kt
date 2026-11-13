package com.safebank.ai.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.repository.BankingRepository
import com.safebank.ai.ui.navigation.NavRoute
import com.safebank.ai.ui.screens.history.HistoryScreen
import com.safebank.ai.ui.screens.home.HomeScreen
import com.safebank.ai.ui.screens.profile.ProfileScreen
import com.safebank.ai.ui.screens.security.SecurityScreen
import com.safebank.ai.ui.screens.transfers.TransfersScreen
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

/**
 * MainScaffold — The primary application layout container.
 * Hosts the NeoSecure top app bar, bottom navigation bar, and NavHost.
 * Strictly enforces minimum 48dp interactive touch targets across navigation items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    navController: NavHostController = rememberNavController(),
    repository: BankingRepository = BankingRepositoryProvider.getRepository()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoute.Home.route
    var repoMode by remember { mutableStateOf(repository.getRepositoryMode()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val preferences = remember(context) {
        context.getSharedPreferences(
            "safebank_behaviour_consent",
            android.content.Context.MODE_PRIVATE
        )
    }

    val sessions = com.safebank.ai.data.security.SecureSessionManager.getInstance(context)
    val owner = sessions.getUserId()
    val consentKey = "consent_allowed_v2:$owner"

    var consent by remember(owner) {
        mutableStateOf(owner != null && preferences.getBoolean(consentKey, false))
    }

    var consentAsked by remember(owner) {
        mutableStateOf(preferences.contains(consentKey))
    }

    fun saveConsent(allowed: Boolean) {
        preferences.edit()
            .putBoolean(consentKey, allowed)
            .apply()

        consent = allowed
        consentAsked = true
    }

    if (!consentAsked) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                saveConsent(false)
            },
            title = {
                Text("Behavioural security")
            },
            text = {
                Text(
                    "Allow foreground touch, typing timing and motion analysis " +
                    "on banking screens? Typed contents and PINs are never collected. " +
                    "Raw signals stay on this device. You can pause collection at any time."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        saveConsent(true)
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        saveConsent(false)
                    }
                ) {
                    Text("Not now")
                }
            }
        )
    }

    val capture = com.safebank.ai.ml.continuousCollection(
        consent && owner != null && sessions.hasActiveSession() && currentRoute in listOf(
            "home", "transfers", "history", "security"
        )
    )
    Scaffold(
        modifier = Modifier.fillMaxSize().then(capture),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(PrimaryPurpleGlow, shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = PrimaryPurple,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = "SafeBank AI",
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Privacy-First • Coercion-Aware",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { saveConsent(!consent) }) {
                        Text(if (consent) "Pause collection" else "Enable collection")
                    }
                    val isMock = repoMode == RepositoryMode.DEMO_MOCK
                    val badgeBg = if (isMock) StatusSuccess.copy(alpha = 0.15f) else PrimaryPurpleGlow
                    val badgeDotColor = if (isMock) StatusSuccess else PrimaryPurple
                    val badgeLabel = if (isMock) "Simulated Mode" else "Backend Live"
                    val isModeSwitchingAllowed = repository.isModeSwitchingAllowed()

                    if (isModeSwitchingAllowed) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable {
                                    val nextMode = if (isMock) RepositoryMode.BACKEND_CONNECTED else RepositoryMode.DEMO_MOCK
                                    repository.setRepositoryMode(nextMode)
                                    repoMode = nextMode
                                }
                                .semantics { role = Role.Button },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(badgeBg, shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(badgeDotColor, shape = CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = badgeLabel,
                                        color = badgeDotColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    } else {
                        // Production Build: Static non-interactive indicator. Mode switching is prohibited.
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .defaultMinSize(minHeight = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(badgeBg, shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(badgeDotColor, shape = CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = badgeLabel,
                                        color = badgeDotColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark,
                    titleContentColor = TextMain
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp),
                containerColor = CardBackground,
                contentColor = TextMuted,
                tonalElevation = 8.dp
            ) {
                NavRoute.bottomNavItems.forEach { screen ->
                    val isSelected = currentRoute == screen.route

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryPurple,
                            selectedTextColor = TextMain,
                            indicatorColor = PrimaryPurpleGlow,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { role = Role.Tab }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavRoute.Home.route) {
                HomeScreen(
                    onNavigateToTransfers = {
                        navController.navigate(NavRoute.Transfers.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToHistory = {
                        navController.navigate(NavRoute.History.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSecurity = {
                        navController.navigate(NavRoute.Security.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(NavRoute.Transfers.route) {
                TransfersScreen()
            }

            composable(NavRoute.History.route) {
                HistoryScreen()
            }

            composable(NavRoute.Security.route) {
                SecurityScreen()
            }

            composable(NavRoute.Profile.route) {
                ProfileScreen()
            }
        }
    }
}
