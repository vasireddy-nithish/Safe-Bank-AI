package com.safebank.ai.ui

import com.safebank.ai.data.network.BankingApiService
import com.safebank.ai.data.network.EnrolmentSample
import com.safebank.ai.data.network.EnrolmentStatus
import com.safebank.ai.ui.screens.enrolment.EnrolmentViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class EnrolmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Server {
        val stages = listOf("TYPING", "TAPPING", "SWIPING", "MOTION")
        val counts = stages.associateWith { 0 }.toMutableMap()
        val submitted = mutableListOf<EnrolmentSample>()
        var loseReply = false
        var gate: CompletableDeferred<Unit>? = null
        fun status() = EnrolmentStatus("alice", counts.toMap(),
            stages.firstOrNull { counts.getValue(it) < 3 } ?: "COMPLETE",
            counts.values.all { it == 3 }, List(48) { 1f })
        private val unused = Proxy.newProxyInstance(BankingApiService::class.java.classLoader,
            arrayOf(BankingApiService::class.java)) { _, method, _ -> error("Unexpected API: ${method.name}") } as BankingApiService
        val api = object : BankingApiService by unused {
            override suspend fun enrolmentStatus() = Response.success(status())
            override suspend fun enrolmentSample(sample: EnrolmentSample): Response<EnrolmentStatus> {
                submitted.add(sample)
                gate?.await()
                counts[sample.stage] = maxOf(counts.getValue(sample.stage), sample.ordinal)
                if (loseReply) { loseReply = false; error("Connection lost after commit") }
                return Response.success(status())
            }
        }
    }

    @Test fun eachThirdAcceptedSampleAdvancesAndResetsNextStage() = runTest(dispatcher) {
        val server = Server()
        val vm = EnrolmentViewModel(server.api, "alice")
        testScheduler.runCurrent()
        for ((index, stage) in server.stages.withIndex()) {
            for (number in 1..3) {
                vm.submit(stage, FloatArray(48) { 1f }, 25)
                testScheduler.runCurrent()
                val state = vm.state.value.status!!
                assertEquals(number, state.counts[stage])
                val next = server.stages.getOrNull(index + 1) ?: "COMPLETE"
                assertEquals(if (number < 3) stage else next, state.current_stage)
                if (number == 3 && next != "COMPLETE") assertEquals(0, state.counts[next])
            }
        }
        assertTrue(vm.state.value.status!!.completed)
        assertEquals(12, server.submitted.size)
    }

    @Test fun invalidSamplesAndRepeatedCallbacksDoNotSubmitTwice() = runTest(dispatcher) {
        val server = Server()
        val vm = EnrolmentViewModel(server.api, "alice")
        testScheduler.runCurrent()
        vm.submit("TYPING", FloatArray(48), 25)
        vm.submit("TYPING", FloatArray(48) { 1f }, 1)
        vm.submit("TAPPING", FloatArray(48) { 1f }, 25)
        vm.submit("TYPING", FloatArray(48) { Float.NaN }, 25)
        testScheduler.runCurrent()
        assertTrue(server.submitted.isEmpty())
        server.gate = CompletableDeferred()
        repeat(10) { vm.submit("TYPING", FloatArray(48) { 1f }, 25) }
        testScheduler.runCurrent()
        assertEquals(1, server.submitted.size)
        repeat(10) { vm.retry() }
        assertEquals(1, server.submitted.size)
        server.gate!!.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, vm.state.value.status!!.counts["TYPING"])
    }

    @Test fun lostResponseRetriesSameOrdinalAndNewViewModelResumes() = runTest(dispatcher) {
        val server = Server()
        val vm = EnrolmentViewModel(server.api, "alice")
        testScheduler.runCurrent()
        server.loseReply = true
        vm.submit("TYPING", FloatArray(48) { 1f }, 25)
        testScheduler.runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals(0, vm.state.value.status!!.counts["TYPING"])
        vm.submit("TYPING", FloatArray(48) { 2f }, 25)
        vm.retry()
        testScheduler.runCurrent()
        assertEquals(listOf(1, 1), server.submitted.map { it.ordinal })
        assertEquals(1, vm.state.value.status!!.counts["TYPING"])
        val restored = EnrolmentViewModel(server.api, "alice")
        testScheduler.runCurrent()
        assertEquals(1, restored.state.value.status!!.counts["TYPING"])
        assertFalse(restored.state.value.status!!.completed)
        val wrongOwner = EnrolmentViewModel(server.api, "bob")
        testScheduler.runCurrent()
        assertNull(wrongOwner.state.value.status)
        assertNotNull(wrongOwner.state.value.error)
    }
}
