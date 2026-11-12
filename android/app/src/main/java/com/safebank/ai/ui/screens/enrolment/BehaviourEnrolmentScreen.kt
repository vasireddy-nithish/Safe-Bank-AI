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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class EnrolmentStep { INTRO, TYPING, TAPPING, SWIPING, MOTION, COMPLETE }

private const val SAMPLE_PHRASE = "SafeBank Security Test"
private const val TARGET_REPS = 3

@Composable
fun BehaviourEnrolmentScreen(
    onEnrolmentComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileManager = remember { BehaviourProfileManager.getInstance(context) }
    val sessionManager = remember { BehaviourSessionManager.getInstance(context) }

    var step by remember { mutableStateOf(EnrolmentStep.INTRO) }
    val collectedTouches = remember { mutableStateListOf<TouchEvent>() }
    val collectedTyping = remember { mutableStateListOf<TypingEvent>() }
    val collectedMotion = remember { mutableStateListOf<MotionSample>() }

    // Exercise repetition counters
    var typingReps by remember { mutableIntStateOf(0) }
    var typedText by remember { mutableStateOf("") }
    var lastKeyTimeMs by remember { mutableLongStateOf(0L) }

    var tapReps by remember { mutableIntStateOf(0) }
    var nextExpectedTap by remember { mutableIntStateOf(1) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    var swipeReps by remember { mutableIntStateOf(0) }
    var swipeDragAccum by remember { mutableFloatStateOf(0f) }

    var motionReps by remember { mutableIntStateOf(0) }
    var motionCapturing by remember { mutableStateOf(false) }
    var motionSecondsLeft by remember { mutableIntStateOf(3) }

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
            // Ensure at least a fallback sample on emulators without physical sensors
            if (collectedMotion.isEmpty()) {
                val fallback = MotionSample(0.05f, 0.02f, 9.81f, 0.01f, 0.01f, 0.01f, System.currentTimeMillis())
                collectedMotion.add(fallback)
                sessionManager.addMotionSample(fallback)
            }
            motionCapturing = false
            motionReps++
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
                TextButton(onClick = onSkip) {
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
                            text = "Complete 4 short guided exercises (3 repetitions each) to build an initial on-device profile. Raw touch, typing, and motion samples stay strictly on this device.",
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
                    onClick = { step = EnrolmentStep.TYPING },
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
                                val now = System.currentTimeMillis()
                                val flight = if (lastKeyTimeMs == 0L) -1L else (now - lastKeyTimeMs).coerceAtMost(2000L)
                                val isBack = newVal.length < typedText.length
                                val event = TypingEvent(
                                    holdTimeMs = if (flight in 1..400) (flight * 0.4f).toLong().coerceAtLeast(45L) else 95L,
                                    flightTimeMs = flight,
                                    isBackspace = isBack,
                                    timestampMs = now,
                                    pressure = 0f
                                )
                                collectedTyping.add(event)
                                sessionManager.addTypingEvent(event)
                                lastKeyTimeMs = now
                                typedText = newVal
                                if (newVal.equals(SAMPLE_PHRASE, ignoreCase = false)) {
                                    typingReps++
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
                SafeBankButton(
                    text = if (typingReps >= TARGET_REPS) "Next: Tap Sequence" else "Complete $TARGET_REPS repetitions (${TARGET_REPS - typingReps} left)",
                    onClick = { step = EnrolmentStep.TAPPING },
                    enabled = typingReps >= TARGET_REPS,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
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
                                            .clickable {
                                                val now = System.currentTimeMillis()
                                                val dt = if (lastTapTimeMs == 0L) 90L else (now - lastTapTimeMs).coerceIn(30L, 1500L)
                                                val touch = TouchEvent(
                                                    x = col * 120f,
                                                    y = row * 120f,
                                                    pressure = 0.55f,
                                                    majorAxis = 12f,
                                                    minorAxis = 9f,
                                                    timestampMs = now,
                                                    durationMs = dt.coerceAtMost(220L),
                                                    velocity = 0.8f
                                                )
                                                collectedTouches.add(touch)
                                                sessionManager.addTouchEvent(touch)
                                                lastTapTimeMs = now
                                                if (num == nextExpectedTap) {
                                                    if (nextExpectedTap == 9) {
                                                        tapReps++
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
                SafeBankButton(
                    text = if (tapReps >= TARGET_REPS) "Next: Swipe Gesture" else "Complete $TARGET_REPS sequences (${TARGET_REPS - tapReps} left)",
                    onClick = { step = EnrolmentStep.SWIPING },
                    enabled = tapReps >= TARGET_REPS,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
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
                                .pointerInput(swipeReps) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { swipeDragAccum = 0f },
                                        onDragEnd = {
                                            if (abs(swipeDragAccum) > 60f) {
                                                val touch = TouchEvent(
                                                    x = abs(swipeDragAccum),
                                                    y = 40f,
                                                    pressure = 0.6f,
                                                    majorAxis = 14f,
                                                    minorAxis = 10f,
                                                    timestampMs = System.currentTimeMillis(),
                                                    durationMs = 160L,
                                                    velocity = (abs(swipeDragAccum) / 160f).coerceAtLeast(0.5f)
                                                )
                                                collectedTouches.add(touch)
                                                sessionManager.addTouchEvent(touch)
                                                swipeReps++
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
                SafeBankButton(
                    text = if (swipeReps >= TARGET_REPS) "Next: Phone Motion" else "Complete $TARGET_REPS swipes (${TARGET_REPS - swipeReps} left)",
                    onClick = { step = EnrolmentStep.MOTION },
                    enabled = swipeReps >= TARGET_REPS,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
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
                            onClick = { motionCapturing = true },
                            enabled = !motionCapturing && motionReps < TARGET_REPS,
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
                SafeBankButton(
                    text = "Finish & Build Baseline",
                    onClick = {
                        scope.launch {
                            val session = BehaviourSession(
                                touches = collectedTouches.toList(),
                                typingEvents = collectedTyping.toList(),
                                motionSamples = collectedMotion.toList()
                            )
                            val vector = FeatureExtractor.extract(session)
                            profileManager.markEnrolmentComplete(vector)
                            step = EnrolmentStep.COMPLETE
                        }
                    },
                    enabled = motionReps >= TARGET_REPS,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EnrolmentStep.COMPLETE -> {
                val totalPoints = collectedTouches.size + collectedTyping.size + collectedMotion.size
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
                            text = "Profile Created ($totalPoints data points)",
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
                SafeBankButton(
                    text = "Go to Dashboard",
                    onClick = onEnrolmentComplete,
                    variant = SafeBankButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

