package com.safebank.ai.ui.screens.enrolment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.security.SecureSessionManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ml.BehaviourProfileManager
import com.safebank.ai.ml.BehaviourSession
import com.safebank.ai.ml.BehaviourSessionManager
import com.safebank.ai.ml.FeatureExtractor
import com.safebank.ai.ml.MotionSample
import com.safebank.ai.ml.TouchEvent
import com.safebank.ai.ml.TypingEvent
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class EnrolmentStep { INTRO, TYPING, TAPPING, SWIPING, MOTION, COMPLETE }

private const val SAMPLE_PHRASE = "SafeBank Security Test"
private const val TARGET_REPS = 3

@Composable
fun BehaviourEnrolmentScreen(
    onEnrolmentComplete: () -> Unit,
    onSkip: () -> Unit,
    enrolmentModel: EnrolmentViewModel? = null
) {
    val context = LocalContext.current
    val profileManager = remember { BehaviourProfileManager.getInstance(context) }
    val sessionManager = remember { BehaviourSessionManager.getInstance(context) }

    val sessions = remember { SecureSessionManager.getInstance(context) }
    val userId = checkNotNull(sessions.getUserId())
    val modelKey = rememberSaveable(userId) { java.util.UUID.randomUUID().toString() }
    val model: EnrolmentViewModel = enrolmentModel ?: viewModel(key = "enrolment:$userId:$modelKey", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EnrolmentViewModel(BankingApiClient(sessions).apiService, userId) as T
    })
    val ui by model.state.collectAsState()
    var started by rememberSaveable(userId) { mutableStateOf(false) }
    val status = ui.status
    val step = if (status == null || (!started && status.counts.values.all { it == 0 }))
        EnrolmentStep.INTRO else EnrolmentStep.valueOf(status.current_stage)
    val canCapture = status != null && !ui.busy && ui.error == null
    LaunchedEffect(status?.completed) {
        if (status?.completed == true) {
            profileManager.markEnrolmentComplete(status.baseline_features?.toFloatArray())
            onEnrolmentComplete()
        }
    }
    val collectedTouches = remember { mutableStateListOf<TouchEvent>() }
    val collectedTyping = remember { mutableStateListOf<TypingEvent>() }
    val collectedMotion = remember { mutableStateListOf<MotionSample>() }

    // Exercise repetition counters
    val typingReps = status?.counts?.get("TYPING") ?: 0
    var typedText by remember { mutableStateOf("") }
    var lastKeyTimeMs by remember { mutableLongStateOf(0L) }

    val tapReps = status?.counts?.get("TAPPING") ?: 0
    var nextExpectedTap by remember { mutableIntStateOf(1) }
    var lastContactDuration by remember { mutableLongStateOf(0L) }
    var swipeStartedMs by remember { mutableLongStateOf(0L) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    val swipeReps = status?.counts?.get("SWIPING") ?: 0
    var swipeDragAccum by remember { mutableFloatStateOf(0f) }

    val motionReps = status?.counts?.get("MOTION") ?: 0
    var motionCapturing by remember { mutableStateOf(false) }
    var motionSecondsLeft by remember { mutableIntStateOf(3) }
    var captureError by remember { mutableStateOf<String?>(null) }

    fun submitSample(stage: EnrolmentStep) {
        if (!canCapture) return
        val sample = BehaviourSession(touches = collectedTouches.toList(),
            typingEvents = collectedTyping.toList(), motionSamples = collectedMotion.toList())
        val count = sample.touches.size + sample.typingEvents.size + sample.motionSamples.size
        model.submit(stage.name, FeatureExtractor.extract(sample), count)
        collectedTouches.clear()
        collectedTyping.clear()
        collectedMotion.clear()
    }

    // Sensor listener active only during MOTION capture
    DisposableEffect(step, motionCapturing) {
        if (step == EnrolmentStep.MOTION && motionCapturing) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            var ax = 0f; var ay = 0f; var az = 9.8f
            var gx = 0f; var gy = 0f; var gz = 0f

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                            val sample = MotionSample(ax, ay, az, gx, gy, gz, System.currentTimeMillis())
                            collectedMotion.add(sample)
                            sessionManager.addMotionSample(sample)
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            accel?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            gyro?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

            onDispose {
                sensorManager?.unregisterListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    LaunchedEffect(motionCapturing) {
        if (motionCapturing) {
            motionSecondsLeft = 3
            while (motionSecondsLeft > 0) {
                delay(1000L)
                motionSecondsLeft--
            }
            motionCapturing = false
            if (collectedMotion.size >= 2) {
                submitSample(EnrolmentStep.MOTION)
            } else {
                collectedMotion.clear()
                captureError = "No motion data received. Check device sensors and try again."
            }
        }
    }

    val progress = when (step) {
        EnrolmentStep.INTRO -> 0.05f
        EnrolmentStep.TYPING -> 0.25f
        EnrolmentStep.TAPPING -> 0.50f
        EnrolmentStep.SWIPING -> 0.75f
        EnrolmentStep.MOTION -> 0.90f
        EnrolmentStep.COMPLETE -> 1.0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Behavioural Baseline",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            if (step != EnrolmentStep.COMPLETE) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Skip for now", color = TextMuted)
                }
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = PrimaryPurple,
            trackColor = CardBackground
        )

        ui.error?.let { message ->
            Text(message, color = StatusWarning)
            SafeBankButton(text = "Retry saving progress", onClick = model::retry,
                enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
        }
        captureError?.let { Text(it, color = StatusWarning) }
        if (ui.busy) Text("Saving enrolment progress…", color = TextMuted)

        when (step) {
            EnrolmentStep.INTRO -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "On-Device Behavioural Enrolment",
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Complete 4 short guided exercises (3 repetitions each) to build an initial on-device profile. Raw samples stay on this device; derived baseline features and progress are saved securely to your account.",
                            color = TextMuted,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        Text(
                            text = "Note: 3 repetitions is a prototype starting point, not proof of full biometric reliability. Standard Android text APIs measure inter-key intervals rather than hardware key-hold dwell on soft keyboards.",
                            color = StatusWarning,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
                SafeBankButton(
                    text = "Start Enrolment",
                    onClick = { started = true },
                    enabled = canCapture,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EnrolmentStep.TYPING -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Keyboard, contentDescription = null, tint = PrimaryPurple)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "Step 1/4: Typing Cadence ($typingReps/$TARGET_REPS)",
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = "Type the phrase below exactly 3 times: \"$SAMPLE_PHRASE\"",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        SafeBankTextField(
                            value = typedText,
                            onValueChange = { newVal ->
                                if (!canCapture || model.state.value.busy) return@SafeBankTextField
                                val now = System.currentTimeMillis()
                                val flight = if (lastKeyTimeMs == 0L) -1L else (now - lastKeyTimeMs).coerceAtMost(2000L)
                                val isBack = newVal.length < typedText.length
                                val event = TypingEvent(
                                    holdTimeMs = 0L, // unavailable on soft keyboard; v2 reserves dwell slots
                                    flightTimeMs = flight,
                                    isBackspace = isBack,
                                    timestampMs = now,
                                    pressure = 0f
                                )
                                collectedTyping.add(event)
                                sessionManager.addTypingEvent(event)
                                lastKeyTimeMs = now
                                typedText = newVal
                                if (newVal == SAMPLE_PHRASE && collectedTyping.size >= SAMPLE_PHRASE.length) {
                                    submitSample(EnrolmentStep.TYPING)
                                    typedText = ""
                                    lastKeyTimeMs = 0L
                                }
                            },
                            label = "Sample Phrase",
                            placeholder = SAMPLE_PHRASE
                        )
                        Text(
                            text = "Typing events captured: ${collectedTyping.size}",
                            color = PrimaryPurple,
                            fontSize = 12.sp
                        )
                    }
                }
                Text("Complete 3 samples to advance automatically.", color = TextMuted)
            }

            EnrolmentStep.TAPPING -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Step 2/4: Tap Sequence ($tapReps/$TARGET_REPS)",
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Tap numbers 1 through 9 in order (next: $nextExpectedTap).",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        for (row in 0 until 3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (col in 1..3) {
                                    val num = row * 3 + col
                                    val isDone = num < nextExpectedTap
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 54.dp)
                                            .background(
                                                if (isDone) StatusSuccess.copy(alpha = 0.25f) else PrimaryPurpleGlow,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .pointerInput(num) {
                                                awaitEachGesture {
                                                    val down=awaitFirstDown(requireUnconsumed=false)
                                                    val up=waitForUpOrCancellation()
                                                    lastContactDuration=if(up!=null) (up.uptimeMillis-down.uptimeMillis).coerceAtLeast(1) else 0
                                                }
                                            }
                                            .clickable(enabled = canCapture) {
                                                if (model.state.value.busy || num != nextExpectedTap) return@clickable
                                                val now = System.currentTimeMillis()
                                                val dt = if (lastTapTimeMs == 0L) 90L else (now - lastTapTimeMs).coerceIn(30L, 1500L)
                                                val touch = TouchEvent(
                                                    x = 0f,
                                                    y = 0f,
                                                    pressure = 0f,
                                                    majorAxis = 0f,
                                                    minorAxis = 0f,
                                                    timestampMs = now,
                                                    durationMs = lastContactDuration,
                                                    velocity = 0f
                                                )
                                                collectedTouches.add(touch)
                                                sessionManager.addTouchEvent(touch)
                                                lastTapTimeMs = now
                                                if (num == nextExpectedTap) {
                                                    if (nextExpectedTap == 9) {
                                                        submitSample(EnrolmentStep.TAPPING)
                                                        lastTapTimeMs = 0L
                                                        nextExpectedTap = 1
                                                    } else {
                                                        nextExpectedTap++
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$num",
                                            color = if (isDone) StatusSuccess else TextMain,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Complete 3 samples to advance automatically.", color = TextMuted)
            }

            EnrolmentStep.SWIPING -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SwipeRight, contentDescription = null, tint = PrimaryPurple)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "Step 3/4: Swipe Gesture ($swipeReps/$TARGET_REPS)",
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = "Swipe horizontally across the pad below $TARGET_REPS times.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(PrimaryPurpleGlow, shape = RoundedCornerShape(14.dp))
                                .pointerInput(swipeReps, canCapture) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { swipeDragAccum = 0f; swipeStartedMs = android.os.SystemClock.elapsedRealtime() },
                                        onDragEnd = {
                                            if (abs(swipeDragAccum) > 60f && canCapture && !model.state.value.busy) {
                                                val touch = TouchEvent(
                                                    x = abs(swipeDragAccum),
                                                    y = 0f,
                                                    pressure = 0f,
                                                    majorAxis = 0f,
                                                    minorAxis = 0f,
                                                    timestampMs = System.currentTimeMillis(),
                                                    durationMs = (android.os.SystemClock.elapsedRealtime()-swipeStartedMs).coerceAtLeast(1),
                                                    velocity = abs(swipeDragAccum) / (android.os.SystemClock.elapsedRealtime()-swipeStartedMs).coerceAtLeast(1)
                                                )
                                                collectedTouches.add(touch)
                                                sessionManager.addTouchEvent(touch)
                                                submitSample(EnrolmentStep.SWIPING)
                                                swipeDragAccum = 0f
                                            }
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            swipeDragAccum += dragAmount
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Swipe Left → Right ($swipeReps/$TARGET_REPS)",
                                color = TextMain,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
                Text("Complete 3 samples to advance automatically.", color = TextMuted)
            }

            EnrolmentStep.MOTION -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = PrimaryPurple)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "Step 4/4: Device Motion ($motionReps/$TARGET_REPS)",
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = "Hold the device naturally while tapping Capture (3 seconds each, $TARGET_REPS repetitions).",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        SafeBankButton(
                            text = if (motionCapturing) "Capturing... ${motionSecondsLeft}s" else "Capture Motion Sample ($motionReps/$TARGET_REPS)",
                            onClick = {
                                if (canCapture && !motionCapturing) {
                                    collectedMotion.clear()
                                    captureError = null
                                    motionCapturing = true
                                }
                            },
                            enabled = canCapture && !motionCapturing && motionReps < TARGET_REPS,
                            variant = SafeBankButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Motion samples captured: ${collectedMotion.size}",
                            color = PrimaryPurple,
                            fontSize = 12.sp
                        )
                    }
                }
                Text("The third accepted sample completes enrolment automatically.", color = TextMuted)
            }

            EnrolmentStep.COMPLETE -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, StatusSuccess)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusSuccess,
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = "Profile Created (12 accepted samples)",
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "3 repetitions is a prototype starting point. Continue using the app to improve your baseline during authenticated low-risk sessions.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text("Opening dashboard…", color = TextMuted)
            }
        }
    }
}

