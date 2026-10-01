package com.dragonfist.trainer.data

import android.content.Context
import android.content.SharedPreferences
import com.dragonfist.trainer.engine.AssistantState
import com.dragonfist.trainer.engine.AutomationConfig
import com.dragonfist.trainer.engine.TrainingGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DesiredRunMode {
    STOPPED,
    PAUSED,
    RUNNING
}

data class CalibratedControlPoints(
    // Landscape normalized (u, v) coordinates
    val landscapeStrikeU: Float = 0.86f,
    val landscapeStrikeV: Float = 0.76f,
    val landscapeHeavyU: Float = 0.76f,
    val landscapeHeavyV: Float = 0.82f,
    val landscapeDodgeLeftU: Float = 0.14f,
    val landscapeDodgeLeftV: Float = 0.78f,
    val landscapeAdvanceRightU: Float = 0.26f,
    val landscapeAdvanceRightV: Float = 0.78f,
    val landscapeMeditateU: Float = 0.88f,
    val landscapeMeditateV: Float = 0.48f,
    // Portrait normalized (u, v) coordinates
    val portraitStrikeU: Float = 0.82f,
    val portraitStrikeV: Float = 0.74f,
    val portraitHeavyU: Float = 0.66f,
    val portraitHeavyV: Float = 0.78f,
    val portraitDodgeLeftU: Float = 0.18f,
    val portraitDodgeLeftV: Float = 0.74f,
    val portraitAdvanceRightU: Float = 0.34f,
    val portraitAdvanceRightV: Float = 0.74f,
    val portraitMeditateU: Float = 0.84f,
    val portraitMeditateV: Float = 0.56f
)

data class ActionLogItem(
    val timestampMs: Long,
    val sceneLabel: String,
    val actionDescription: String,
    val confidencePercent: Int,
    val verified: Boolean
)

data class RuntimeTelemetrySnapshot(
    val serviceConnected: Boolean = false,
    val gameInForeground: Boolean = false,
    val isLandscape: Boolean = true,
    val foregroundPackage: String = "",
    val assistantState: AssistantState = AssistantState.STOPPED,
    val recognizedScene: String = "WAITING_FOR_GAME",
    val sceneConfidencePercent: Int = 0,
    val staminaPercent: Int = 0,
    val healthPercent: Int = 0,
    val spgChargePercent: Int = 0,
    val captureIntervalMs: Long = 350L,
    val statusHeadline: String = "Assistant Stopped",
    val statusReasoning: String = "Enable Dragonfist Trainer in Accessibility Settings and open Dragonfist Limitless.",
    val spendGuardBlockedCount: Int = 0,
    val verifiedActionCount: Int = 0,
    val recentLogs: List<ActionLogItem> = emptyList()
)

/**
 * Thread-safe singleton repository coordinating user configuration, calibrated control coordinates,
 * and real-time runtime telemetry between MainActivity, FloatingTrainerOverlay, and DragonfistAccessibilityService.
 */
object TrainerRepository {
    private const val PREFS_NAME = "dragonfist_trainer_prefs"
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(AutomationConfig())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()

    private val _calibration = MutableStateFlow(CalibratedControlPoints())
    val calibration: StateFlow<CalibratedControlPoints> = _calibration.asStateFlow()

    private val _telemetry = MutableStateFlow(RuntimeTelemetrySnapshot())
    val telemetry: StateFlow<RuntimeTelemetrySnapshot> = _telemetry.asStateFlow()

    private val _desiredRunMode = MutableStateFlow(DesiredRunMode.STOPPED)
    val desiredRunMode: StateFlow<DesiredRunMode> = _desiredRunMode.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val savedGoal = sp.getString("goal", TrainingGoal.AUTO_OPTIMAL.name) ?: TrainingGoal.AUTO_OPTIMAL.name
        val goalEnum = runCatching { TrainingGoal.valueOf(savedGoal) }.getOrDefault(TrainingGoal.AUTO_OPTIMAL)

        _config.value = AutomationConfig(
            goal = goalEnum,
            staminaFloorPercent = sp.getInt("staminaFloor", 22).coerceIn(10, 55),
            staminaResumePercent = sp.getInt("staminaResume", 84).coerceIn(55, 98),
            enableShadowBoxingSPG = sp.getBoolean("shadowBoxing", true),
            autoMeditateOnFullExp = sp.getBoolean("autoMeditate", true),
            combatUseChargedMoves = sp.getBoolean("chargedMoves", true),
            combatAutoDodgeProjectiles = sp.getBoolean("autoDodge", true),
            minConfidenceThreshold = sp.getFloat("minConfidence", 0.80f).coerceIn(0.65f, 0.95f),
            stuckTimeoutMs = sp.getLong("stuckTimeoutMs", 4500L).coerceIn(2500L, 10000L),
            adaptivePollRateHz = sp.getInt("pollRateHz", 3).coerceIn(1, 3),
            strictSpendGuard = sp.getBoolean("strictSpendGuard", true)
        )

        _calibration.value = CalibratedControlPoints(
            landscapeStrikeU = sp.getFloat("ls_strike_u", 0.86f).coerceIn(0.05f, 0.95f),
            landscapeStrikeV = sp.getFloat("ls_strike_v", 0.76f).coerceIn(0.15f, 0.95f),
            landscapeHeavyU = sp.getFloat("ls_heavy_u", 0.76f).coerceIn(0.05f, 0.95f),
            landscapeHeavyV = sp.getFloat("ls_heavy_v", 0.82f).coerceIn(0.15f, 0.95f),
            landscapeDodgeLeftU = sp.getFloat("ls_dodge_u", 0.14f).coerceIn(0.05f, 0.95f),
            landscapeDodgeLeftV = sp.getFloat("ls_dodge_v", 0.78f).coerceIn(0.15f, 0.95f),
            landscapeAdvanceRightU = sp.getFloat("ls_adv_u", 0.26f).coerceIn(0.05f, 0.95f),
            landscapeAdvanceRightV = sp.getFloat("ls_adv_v", 0.78f).coerceIn(0.15f, 0.95f),
            landscapeMeditateU = sp.getFloat("ls_med_u", 0.88f).coerceIn(0.05f, 0.95f),
            landscapeMeditateV = sp.getFloat("ls_med_v", 0.48f).coerceIn(0.18f, 0.95f),
            portraitStrikeU = sp.getFloat("pt_strike_u", 0.82f).coerceIn(0.05f, 0.95f),
            portraitStrikeV = sp.getFloat("pt_strike_v", 0.74f).coerceIn(0.15f, 0.95f),
            portraitHeavyU = sp.getFloat("pt_heavy_u", 0.66f).coerceIn(0.05f, 0.95f),
            portraitHeavyV = sp.getFloat("pt_heavy_v", 0.78f).coerceIn(0.15f, 0.95f),
            portraitDodgeLeftU = sp.getFloat("pt_dodge_u", 0.18f).coerceIn(0.05f, 0.95f),
            portraitDodgeLeftV = sp.getFloat("pt_dodge_v", 0.74f).coerceIn(0.15f, 0.95f),
            portraitAdvanceRightU = sp.getFloat("pt_adv_u", 0.34f).coerceIn(0.05f, 0.95f),
            portraitAdvanceRightV = sp.getFloat("pt_adv_v", 0.74f).coerceIn(0.15f, 0.95f),
            portraitMeditateU = sp.getFloat("pt_med_u", 0.84f).coerceIn(0.05f, 0.95f),
            portraitMeditateV = sp.getFloat("pt_med_v", 0.56f).coerceIn(0.18f, 0.95f)
        )
    }

    fun updateConfig(transform: (AutomationConfig) -> AutomationConfig) {
        _config.update { current ->
            val updated = transform(current)
            prefs?.edit()?.apply {
                putString("goal", updated.goal.name)
                putInt("staminaFloor", updated.staminaFloorPercent)
                putInt("staminaResume", updated.staminaResumePercent)
                putBoolean("shadowBoxing", updated.enableShadowBoxingSPG)
                putBoolean("autoMeditate", updated.autoMeditateOnFullExp)
                putBoolean("chargedMoves", updated.combatUseChargedMoves)
                putBoolean("autoDodge", updated.combatAutoDodgeProjectiles)
                putFloat("minConfidence", updated.minConfidenceThreshold)
                putLong("stuckTimeoutMs", updated.stuckTimeoutMs)
                putInt("pollRateHz", updated.adaptivePollRateHz)
                putBoolean("strictSpendGuard", updated.strictSpendGuard)
                apply()
            }
            updated
        }
    }

    fun updateCalibration(transform: (CalibratedControlPoints) -> CalibratedControlPoints) {
        _calibration.update { current ->
            val updated = transform(current)
            prefs?.edit()?.apply {
                putFloat("ls_strike_u", updated.landscapeStrikeU)
                putFloat("ls_strike_v", updated.landscapeStrikeV)
                putFloat("ls_heavy_u", updated.landscapeHeavyU)
                putFloat("ls_heavy_v", updated.landscapeHeavyV)
                putFloat("ls_dodge_u", updated.landscapeDodgeLeftU)
                putFloat("ls_dodge_v", updated.landscapeDodgeLeftV)
                putFloat("ls_adv_u", updated.landscapeAdvanceRightU)
                putFloat("ls_adv_v", updated.landscapeAdvanceRightV)
                putFloat("ls_med_u", updated.landscapeMeditateU)
                putFloat("ls_med_v", updated.landscapeMeditateV)
                putFloat("pt_strike_u", updated.portraitStrikeU)
                putFloat("pt_strike_v", updated.portraitStrikeV)
                putFloat("pt_heavy_u", updated.portraitHeavyU)
                putFloat("pt_heavy_v", updated.portraitHeavyV)
                putFloat("pt_dodge_u", updated.portraitDodgeLeftU)
                putFloat("pt_dodge_v", updated.portraitDodgeLeftV)
                putFloat("pt_adv_u", updated.portraitAdvanceRightU)
                putFloat("pt_adv_v", updated.portraitAdvanceRightV)
                putFloat("pt_med_u", updated.portraitMeditateU)
                putFloat("pt_med_v", updated.portraitMeditateV)
                apply()
            }
            updated
        }
    }

    fun requestStart() {
        _desiredRunMode.value = DesiredRunMode.RUNNING
    }

    fun requestPause() {
        _desiredRunMode.value = DesiredRunMode.PAUSED
    }

    fun requestResume() {
        _desiredRunMode.value = DesiredRunMode.RUNNING
    }

    fun requestStop() {
        _desiredRunMode.value = DesiredRunMode.STOPPED
    }

    fun updateTelemetry(transform: (RuntimeTelemetrySnapshot) -> RuntimeTelemetrySnapshot) {
        _telemetry.update(transform)
    }

    fun appendActionLog(item: ActionLogItem) {
        _telemetry.update { current ->
            val updatedLogs = (listOf(item) + current.recentLogs).take(30)
            current.copy(
                verifiedActionCount = if (item.verified) current.verifiedActionCount + 1 else current.verifiedActionCount,
                recentLogs = updatedLogs
            )
        }
    }
}
