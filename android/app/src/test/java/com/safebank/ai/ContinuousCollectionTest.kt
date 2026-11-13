package com.safebank.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.safebank.ai.ml.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ContinuousCollectionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun consentEnablesTouchCaptureAndRevocationClearsEvents() {
        val enabled=mutableStateOf(false)
        val manager=BehaviourSessionManager.getInstance(RuntimeEnvironment.getApplication())
        manager.resetSession()
        compose.setContent { Box(Modifier.size(240.dp).then(continuousCollection(enabled.value)).testTag("surface")) }
        compose.onNodeWithTag("surface").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(manager.snapshotSession().touches.isEmpty());enabled.value=true }
        compose.waitForIdle()
        compose.onNodeWithTag("surface").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertTrue(manager.collectionConsented)
            assertEquals(1,manager.snapshotSession().touches.size)
            assertTrue(manager.snapshotSession().touches.single().durationMs>0)
            enabled.value=false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(manager.collectionConsented)
            assertEquals(0,manager.getDataPointCount())
        }
    }
}
