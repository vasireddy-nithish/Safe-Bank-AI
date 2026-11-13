package com.safebank.ai.ui

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.safebank.ai.data.network.BankingApiService
import com.safebank.ai.data.network.EnrolmentSample
import com.safebank.ai.data.network.EnrolmentStatus
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.ml.BehaviourProfileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import com.safebank.ai.ui.screens.enrolment.BehaviourEnrolmentScreen
import com.safebank.ai.ui.screens.enrolment.EnrolmentViewModel
import com.safebank.ai.ui.theme.SafeBankTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BehaviourEnrolmentScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun skipHasUsableTouchTargetOnNarrowScreenWhileLoading() {
        val context=RuntimeEnvironment.getApplication()
        SecureSessionManager.getInstance(context).saveSession("test-token", "skip-loading")
        val unused = Proxy.newProxyInstance(BankingApiService::class.java.classLoader,
            arrayOf(BankingApiService::class.java)) { _, method, _ -> error(method.name) } as BankingApiService
        val api=object : BankingApiService by unused {
            override suspend fun enrolmentStatus(): Response<EnrolmentStatus> {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm=EnrolmentViewModel(api,"skip-loading")
        var skipped=0
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(280.dp)) {
                SafeBankTheme { BehaviourEnrolmentScreen({}, { skipped++ }, vm) }
            }
        }
        val skip=compose.onNodeWithText("Skip for now")
        skip.assertIsDisplayed()
        assertTrue("Skip touch target must remain at least 48dp wide",
            skip.fetchSemanticsNode().boundsInRoot.width >= with(compose.density) { 48.dp.toPx() })
        skip.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1,skipped);assertTrue(vm.state.value.busy) }
    }

    @Test fun typingAndTappingAutomaticallyAdvanceAfterThirdAcceptedSample() {
        val userId = "compose-enrolment-user"
        SecureSessionManager.getInstance(RuntimeEnvironment.getApplication()).saveSession("test-token", userId)
        val stages = listOf("TYPING", "TAPPING", "SWIPING", "MOTION")
        val counts = stages.associateWith { 0 }.toMutableMap()
        val submitted = mutableListOf<EnrolmentSample>()
        fun status() = EnrolmentStatus(userId, counts.toMap(), stages.first { counts.getValue(it) < 3 }, false, null)
        val unused = Proxy.newProxyInstance(BankingApiService::class.java.classLoader,
            arrayOf(BankingApiService::class.java)) { _, method, _ -> error(method.name) } as BankingApiService
        val api = object : BankingApiService by unused {
            override suspend fun enrolmentStatus() = Response.success(status())
            override suspend fun enrolmentSample(sample: EnrolmentSample): Response<EnrolmentStatus> {
                submitted.add(sample)
                counts[sample.stage] = sample.ordinal
                return Response.success(status())
            }
        }
        val vm = EnrolmentViewModel(api, userId)
        compose.setContent {
            SafeBankTheme {
                BehaviourEnrolmentScreen(onEnrolmentComplete = {}, onSkip = {}, enrolmentModel = vm)
            }
        }
        compose.onNodeWithText("Start Enrolment").performScrollTo().performClick()
        // Pasting a phrase does not provide enough cadence events and must not increment.
        compose.onNode(hasSetTextAction()).performTextInput("SafeBank Security Test")
        compose.runOnIdle { assertEquals(0, submitted.size) }
        compose.onNode(hasSetTextAction()).performTextClearance()
        for (n in 1..3) {
            for (character in "SafeBank Security Test") {
                compose.onNode(hasSetTextAction()).performTextInput(character.toString())
            }
            if (n < 3) compose.onNodeWithText("Step 1/4: Typing Cadence ($n/3)").assertExists()
        }
        compose.onNodeWithText("Step 2/4: Tap Sequence (0/3)").assertExists()
        for (n in 1..3) {
            for (number in 1..9) compose.onNodeWithText(number.toString()).performScrollTo().performClick()
            if (n < 3) compose.onNodeWithText("Step 2/4: Tap Sequence ($n/3)").assertExists()
        }
        compose.onNodeWithText("Step 3/4: Swipe Gesture (0/3)").assertExists()
        compose.runOnIdle {
            assertEquals(6, submitted.size)
            assertEquals(listOf(1, 2, 3, 1, 2, 3), submitted.map { it.ordinal })
        }
    }
    @Test fun localBaselineAndCompletionAreScopedToAuthenticatedUser() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sessions = SecureSessionManager.getInstance(context)
        val profile = BehaviourProfileManager.getInstance(context)
        sessions.saveSession("token-a", "profile-test-a")
        profile.markEnrolmentComplete(FloatArray(48) { 1f })
        sessions.saveSession("token-b", "profile-test-b")
        assertFalse(profile.isEnrolmentComplete())
        assertNull(profile.getBaselineVector())
        profile.markEnrolmentComplete(FloatArray(48) { 2f })
        sessions.saveSession("token-a2", "profile-test-a")
        assertTrue(profile.isEnrolmentComplete())
        assertEquals(1f, profile.getBaselineVector()!![0])
        sessions.clearSession()
        assertNull(profile.getBaselineVector())
        assertFalse(profile.isEnrolmentComplete())
    }

}
