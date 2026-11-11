package com.safebank.ai.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.ui.scaffold.MainScaffold
import com.safebank.ai.ui.theme.SafeBankTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationAndScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        BankingRepositoryProvider.setRepositoryForTesting(
            FakeBankingRepository(initialMode = RepositoryMode.DEMO_MOCK)
        )
    }

    @After
    fun tearDown() {
        BankingRepositoryProvider.resetForTesting()
    }

    private fun hasRole(role: Role): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun mainScaffold_rendersAllBottomNavItemsWithMinimum48dpTouchTargets() {
        composeTestRule.setContent {
            SafeBankTheme {
                MainScaffold()
            }
        }

        // Verify Top Bar Title & Security Badge
        composeTestRule.onNodeWithText("SafeBank AI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Simulated Mode").assertIsDisplayed()

        // Verify All 5 Bottom Navigation destinations exist with min 48dp targets
        val destinations = listOf("Home", "Transfers", "History", "Security", "Profile")
        destinations.forEach { tabTitle ->
            val node = composeTestRule.onNode(hasText(tabTitle) and hasRole(Role.Tab))
            node.assertIsDisplayed()
            node.assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun mainScaffold_navigatesBetweenTabs() {
        composeTestRule.setContent {
            SafeBankTheme {
                MainScaffold()
            }
        }

        // Start on Home: Verify Home greeting header
        composeTestRule.onNodeWithText("Welcome back,").assertIsDisplayed()

        // Navigate to Transfers tab
        composeTestRule.onNode(hasText("Transfers") and hasRole(Role.Tab)).performClick()
        composeTestRule.onNodeWithText("Transfer Money").assertIsDisplayed()
        composeTestRule.onNodeWithText("Simulated Zero-Risk Financial Engine").assertIsDisplayed()

        // Navigate to History tab
        composeTestRule.onNode(hasText("History") and hasRole(Role.Tab)).performClick()
        composeTestRule.onNodeWithText("Transaction History").assertIsDisplayed()

        // Navigate to Security tab
        composeTestRule.onNode(hasText("Security") and hasRole(Role.Tab)).performClick()
        composeTestRule.onNodeWithText("Security Centre").assertIsDisplayed()

        // Navigate to Profile tab
        composeTestRule.onNode(hasText("Profile") and hasRole(Role.Tab)).performClick()
        composeTestRule.onNodeWithText("Profile & Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alex Chen").assertIsDisplayed()
    }
}
