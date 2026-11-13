package com.safebank.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.safebank.ai.ml.BehaviourClassifier
import com.safebank.ai.ml.BehaviourSession
import com.safebank.ai.ml.FeatureExtractor
import com.safebank.ai.ml.MotionSample
import com.safebank.ai.ml.TouchEvent
import com.safebank.ai.ml.TypingEvent
import com.safebank.ai.ui.screens.register.RegisterViewModel
import com.safebank.ai.ui.screens.restricted.RestrictedScreen
import com.safebank.ai.ui.screens.welcome.WelcomeScreen
import com.safebank.ai.ui.theme.SafeBankTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BehaviouralMlAndAuthUnitTests {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun featureExtractor_emptySessionProduces48FiniteFeatures() {
        val features = FeatureExtractor.extract(BehaviourSession())
        assertEquals(48, features.size)
        assertTrue(features.all { it.isFinite() })
    }

    @Test
    fun featureExtractor_populatedSessionComputesValidFeatures() {
        val session = BehaviourSession(
            touches = listOf(
                TouchEvent(100f, 50f, 0.6f, 12f, 8f, 1000L, 120L, 1.2f),
                TouchEvent(220f, 60f, 0.65f, 13f, 9f, 1200L, 150L, 1.6f)
            ),
            typingEvents = listOf(
                TypingEvent(110L, -1L, false, 1000L, 0.5f),
                TypingEvent(120L, 85L, false, 1200L, 0.55f),
                TypingEvent(95L, 90L, true, 1400L, 0.5f)
            ),
            motionSamples = listOf(
                MotionSample(0.05f, 0.02f, 9.81f, 0.01f, 0.02f, 0.01f, 1000L),
                MotionSample(0.07f, 0.03f, 9.80f, 0.02f, 0.01f, 0.02f, 1100L)
            )
        )
        val features = FeatureExtractor.extract(session)
        assertEquals(48, features.size)
        assertTrue(features.all { it.isFinite() })
        assertEquals(135f, features[19], 0.01f)
        assertEquals(135f, features[0], 0.01f) // mean touch duration
        assertTrue(features[26] > 30f)        // 1/3 backspaces = 33.3%
        assertTrue(features[38] > 9.0f)       // accel magnitude near gravity
    }

    @Test
    fun behaviourClassifier_handlesInferenceGracefully() {
        val context = RuntimeEnvironment.getApplication()
        val classifier = BehaviourClassifier(context)
        val features = FloatArray(48) { 0f }
        val result = classifier.classify(features)
        assertTrue(result.riskScore in 0..100)
        assertTrue(result.predictedClass in 0..2)
        val probSum = result.genuineProbability + result.uncertainProbability + result.anomalyProbability
        assertEquals(1.0f, probSum, 0.05f)
        classifier.close()
    }

    @Test
    fun registerViewModel_validatesFieldsProperly() {
        val vm = RegisterViewModel()
        vm.onFullNameChange("Priya Sharma")
        assertEquals("priya_sharma", vm.uiState.value.username)

        vm.onEnrolmentCodeChange("WRONG")
        vm.onDuresspinChange("12")
        vm.register {}
        assertNotNull(vm.uiState.value.enrolmentError)
        assertNotNull(vm.uiState.value.duressPinError)

        vm.onEnrolmentCodeChange("SAFEBANK2024")
        vm.onDuresspinChange("654321")
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun welcomeScreen_rendersBrandingAndActions() {
        composeTestRule.setContent {
            SafeBankTheme {
                WelcomeScreen(onNavigateToRegister = {}, onNavigateToLogin = {})
            }
        }
        composeTestRule.onNodeWithText("SafeBank AI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Register Account").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign In").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun restrictedScreen_protectsAccountWithoutRevealingDuress() {
        composeTestRule.setContent {
            SafeBankTheme {
                RestrictedScreen(onSignOut = {})
            }
        }
        composeTestRule.onNodeWithText("Account Protected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign Out").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Duress", substring = true, ignoreCase = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Coercion", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
