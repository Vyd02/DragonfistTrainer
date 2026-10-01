package com.dragonfist.trainer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dragonfist.trainer.data.CalibratedControlPoints
import com.dragonfist.trainer.data.TrainerRepository
import com.dragonfist.trainer.engine.AssistantState
import com.dragonfist.trainer.engine.TrainingGoal
import com.dragonfist.trainer.service.DragonfistAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrainerRepository.initialize(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF59E0B),
                    secondary = Color(0xFF10B981),
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF111827)
                )
            ) {
                DragonfistTrainerProductionScreen(
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onLaunchOrInstallGame = {
                        launchRealDragonfistGame()
                    }
                )
            }
        }
    }

    private fun launchRealDragonfistGame() {
        val primaryIntent = packageManager.getLaunchIntentForPackage(
            DragonfistAccessibilityService.GAME_PACKAGE_PRIMARY
        ) ?: packageManager.getLaunchIntentForPackage(
            DragonfistAccessibilityService.GAME_PACKAGE_LOWER
        )

        if (primaryIntent != null) {
            primaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(primaryIntent)
        } else {
            val playStoreIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${DragonfistAccessibilityService.GAME_PACKAGE_PRIMARY}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(playStoreIntent) }.onFailure {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${DragonfistAccessibilityService.GAME_PACKAGE_PRIMARY}")
                    )
                )
            }
        }
    }

    companion object {
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            return enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        }

        fun isDragonfistGameInstalled(context: Context): Boolean {
            val pm = context.packageManager
            return pm.getLaunchIntentForPackage(DragonfistAccessibilityService.GAME_PACKAGE_PRIMARY) != null ||
                pm.getLaunchIntentForPackage(DragonfistAccessibilityService.GAME_PACKAGE_LOWER) != null
        }
    }
}

@Composable
fun DragonfistTrainerProductionScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onLaunchOrInstallGame: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val config by TrainerRepository.config.collectAsState()
    val calibration by TrainerRepository.calibration.collectAsState()
    val telemetry by TrainerRepository.telemetry.collectAsState()

    var serviceEnabledInOs by remember {
        mutableStateOf(MainActivity.isAccessibilityServiceEnabled(context))
    }
    var gameInstalled by remember {
        mutableStateOf(MainActivity.isDragonfistGameInstalled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabledInOs = MainActivity.isAccessibilityServiceEnabled(context)
                gameInstalled = MainActivity.isDragonfistGameInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isRunning = telemetry.assistantState != AssistantState.STOPPED &&
        telemetry.assistantState != AssistantState.PAUSED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Dragonfist Trainer",
                color = Color(0xFFF8FAFC),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Intelligent Gameplay Assistant for Dragonfist Limitless",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp
            )
        }

        // 1. Live Assistant Status & Controls Card
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = telemetry.statusHeadline,
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = telemetry.assistantState.name,
                        color = if (isRunning) Color(0xFF34D399) else Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = telemetry.statusReasoning,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Scene", color = Color(0xFF64748B), fontSize = 11.sp)
                        Text(
                            text = telemetry.recognizedScene,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Column {
                        Text("Stamina", color = Color(0xFF64748B), fontSize = 11.sp)
                        Text(
                            text = if (telemetry.gameInForeground) "${telemetry.staminaPercent}%" else "--%",
                            color = Color(0xFF34D399),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Column {
                        Text("SPG Boost", color = Color(0xFF64748B), fontSize = 11.sp)
                        Text(
                            text = if (telemetry.gameInForeground) "${telemetry.spgChargePercent}%" else "--%",
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Column {
                        Text("Confidence", color = Color(0xFF64748B), fontSize = 11.sp)
                        Text(
                            text = if (telemetry.gameInForeground) "${telemetry.sceneConfidencePercent}%" else "--%",
                            color = Color(0xFFF8FAFC),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!serviceEnabledInOs) {
                                onOpenAccessibilitySettings()
                            } else if (telemetry.assistantState == AssistantState.STOPPED) {
                                TrainerRepository.requestStart()
                            } else if (telemetry.assistantState == AssistantState.PAUSED) {
                                TrainerRepository.requestResume()
                            } else {
                                TrainerRepository.requestPause()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color(0xFFF59E0B) else Color(0xFF10B981)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                !serviceEnabledInOs -> "Enable Accessibility Service"
                                isRunning -> "Pause Assistant"
                                telemetry.assistantState == AssistantState.PAUSED -> "Resume Assistant"
                                else -> "Start Assistant"
                            },
                            color = Color(0xFF090D16),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (telemetry.assistantState != AssistantState.STOPPED) {
                        OutlinedButton(
                            onClick = { TrainerRepository.requestStop() },
                            modifier = Modifier.weight(0.55f)
                        ) {
                            Text("Stop", color = Color(0xFFFCA5A5))
                        }
                    }
                }

                Button(
                    onClick = onLaunchOrInstallGame,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (gameInstalled) {
                            "Open Dragonfist Limitless"
                        } else {
                            "Install Dragonfist Limitless from Google Play"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 2. System Readiness & In-Game Target Calibration
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "System Readiness & Target Calibration",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (serviceEnabledInOs) {
                            "Accessibility Service & Floating Overlay: Enabled"
                        } else {
                            "Accessibility Service: Not Enabled"
                        },
                        color = if (serviceEnabledInOs) Color(0xFF34D399) else Color(0xFFFBBF24),
                        fontSize = 13.sp
                    )
                    TextButton(onClick = onOpenAccessibilitySettings) {
                        Text("Settings", color = Color(0xFFF59E0B))
                    }
                }
                Text(
                    text = if (gameInstalled) {
                        "Dragonfist Limitless (com.raventhe.DragonfistLimitless): Installed"
                    } else {
                        "Dragonfist Limitless: Not detected on device"
                    },
                    color = if (gameInstalled) Color(0xFF34D399) else Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Strike Target (Landscape): (${String.format("%.2f", calibration.landscapeStrikeU)}, ${String.format("%.2f", calibration.landscapeStrikeV)})",
                        color = Color(0xFFCBD5E1),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    TextButton(
                        onClick = {
                            TrainerRepository.updateCalibration { CalibratedControlPoints() }
                        }
                    ) {
                        Text("Reset Targets", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Training Objective Selector
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Training Objective",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )

                TrainingGoal.entries.forEach { goal ->
                    val selected = config.goal == goal
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Color(0xFF1E293B) else Color(0xFF0B0F17),
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                1.dp,
                                if (selected) Color(0xFFF59E0B) else Color(0xFF1E293B),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                TrainerRepository.updateConfig { it.copy(goal = goal) }
                            }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = goal.displayName,
                            color = if (selected) Color(0xFFFBBF24) else Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = goal.description,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 4. Stamina Hysteresis & Intelligent Safeguards
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Stamina Management & Safeguards",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )

                Text(
                    text = "Stamina Conservation Floor (Pause Strikes): ${config.staminaFloorPercent}%",
                    color = Color(0xFFFBBF24),
                    fontSize = 13.sp
                )
                Slider(
                    value = config.staminaFloorPercent.toFloat(),
                    onValueChange = { v ->
                        TrainerRepository.updateConfig { it.copy(staminaFloorPercent = v.toInt()) }
                    },
                    valueRange = 10f..50f
                )

                Text(
                    text = "Stamina Recovery Resume Threshold: ${config.staminaResumePercent}%",
                    color = Color(0xFF34D399),
                    fontSize = 13.sp
                )
                Slider(
                    value = config.staminaResumePercent.toFloat(),
                    onValueChange = { v ->
                        TrainerRepository.updateConfig { it.copy(staminaResumePercent = v.toInt()) }
                    },
                    valueRange = 55f..98f
                )

                Text(
                    text = "Minimum Recognition Confidence Gate: ${(config.minConfidenceThreshold * 100).toInt()}%",
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp
                )
                Slider(
                    value = config.minConfidenceThreshold * 100f,
                    onValueChange = { v ->
                        TrainerRepository.updateConfig {
                            it.copy(minConfidenceThreshold = (v / 100f).coerceIn(0.65f, 0.95f))
                        }
                    },
                    valueRange = 65f..95f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shadow Boxing (SPG) Pre-Routine", color = Color.White, fontSize = 13.sp)
                        Text(
                            "Air-punch after Meditation to build 100% Special Power Gains",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.enableShadowBoxingSPG,
                        onCheckedChange = { enabled ->
                            TrainerRepository.updateConfig { it.copy(enableShadowBoxingSPG = enabled) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Meditate on Full Stored EXP", color = Color.White, fontSize = 13.sp)
                        Text(
                            "Convert accumulated EXP into permanent Power Level automatically",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.autoMeditateOnFullExp,
                        onCheckedChange = { enabled ->
                            TrainerRepository.updateConfig { it.copy(autoMeditateOnFullExp = enabled) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Strict Spend Guard Firewall", color = Color(0xFFF472B6), fontSize = 13.sp)
                        Text(
                            "Block all taps near Dragon Tokens, shops, and Google Play Billing (${telemetry.spendGuardBlockedCount} blocked)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.strictSpendGuard,
                        onCheckedChange = { enabled ->
                            TrainerRepository.updateConfig { it.copy(strictSpendGuard = enabled) }
                        }
                    )
                }
            }
        }

        // 5. Recent Verified Actions Log
        if (telemetry.recentLogs.isNotEmpty()) {
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Recent Verified Actions (${telemetry.verifiedActionCount})",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    telemetry.recentLogs.take(6).forEach { entry ->
                        Text(
                            text = "[${entry.sceneLabel}] ${entry.actionDescription} (${entry.confidencePercent}%)",
                            color = Color(0xFFCBD5E1),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
