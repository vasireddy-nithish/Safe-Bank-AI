package com.safebank.ai

import com.safebank.ai.ml.*
import com.safebank.ai.data.security.SecureSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BehaviourContractV2Test {
    @Test fun durationCadenceDegreesAndReservedSlotsMatchContract() {
        val f=FeatureExtractor.extract(BehaviourSession(
            touches=listOf(TouchEvent(10f,0f,0f,0f,0f,1000,100,0.1f),TouchEvent(20f,0f,0f,0f,0f,1200,200,0.1f)),
            typingEvents=listOf(TypingEvent(0,100,false,1000,0f),TypingEvent(0,200,false,1200,0f)),
            motionSamples=listOf(MotionSample(9.81f,0f,9.81f,0f,0f,0f,1000))))
        assertEquals(150f,f[19],0.001f)
        assertEquals(400f,f[27],0.001f)
        assertEquals(45f,f[46],0.001f)
        for(i in intArrayOf(6,7,8,9,10,11,12,13,14,17,18,20,21,22,28,29,30,31,47)) assertEquals(0f,f[i],0f)
    }
    @Test fun all48SlotsMatchOrderedContract() {
        val actual=FeatureExtractor.extract(BehaviourSession(
            touches=listOf(TouchEvent(3f,4f,0f,0f,0f,1000,100,0.05f),TouchEvent(0f,20f,0f,0f,0f,1200,200,0.1f)),
            typingEvents=listOf(TypingEvent(0,100,false,1000,0f),TypingEvent(0,200,true,1200,0f)),
            motionSamples=listOf(MotionSample(0f,0f,10f,0f,2f,4f,1000),MotionSample(0f,0f,10f,2f,4f,6f,1200))))
        val expected=floatArrayOf(
            150f,50f,12.5f,7.5f,0.075f,0.1f,0f,0f,0f,0f,0f,0f,0f,0f,0f,200f,0.001f,0f,0f,150f,
            0f,0f,0f,150f,50f,150f,50f,400f,0f,0f,0f,0f,
            0f,0.001f,0f,0.001f,10f,0.001f,10f,0f,1f,1f,3f,1f,5f,1f,0f,0f)
        assertArrayEquals(expected,actual,0.001f)
    }
    @Test fun tiltUsesMeanAccelerationRatherThanMeanAngles() {
        val f=FeatureExtractor.extract(BehaviourSession(motionSamples=listOf(
            MotionSample(10f,0f,0f,0f,0f,0f,1000),
            MotionSample(-10f,0f,10f,0f,0f,0f,1100))))
        assertEquals(0f,f[46],0.001f)
    }
    @Test fun coldStartExpiredEventsAndDeclinedConsentCannotProduceFreshAssessment() {
        val m=BehaviourSessionManager.getInstance(RuntimeEnvironment.getApplication())
        m.resetSession();m.collectionConsented=false
        m.recordTypingTiming(false)
        assertEquals(0,m.getDataPointCount())
        repeat(6) { m.addTypingEvent(TypingEvent(0,100,false,System.currentTimeMillis()-31000,0f)) }
        assertEquals(0,m.getDataPointCount())
        assertFalse(m.classify().isFromLiveModel)
        assertEquals("insufficient_data",m.classify().inferenceStatus)
    }
    @Test fun personalizedDriftAddsBoundedPenalty() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        SecureSessionManager.getInstance(context).saveSession("test","v2-drift-test")
        val m=BehaviourSessionManager.getInstance(context);m.resetSession()
        repeat(5) { m.addMotionSample(MotionSample(0f,0f,9.81f,0f,0f,0f,System.currentTimeMillis())) }
        val profile=BehaviourProfileManager.getInstance(context)
        profile.resetEnrolment();profile.markEnrolmentComplete(FloatArray(48){100f})
        val before=m.classify().riskScore;val after=m.classifyWithBaseline().riskScore
        assertTrue(after>before);assertTrue(after<=minOf(100,before+35))
        m.resetSession()
    }
}
