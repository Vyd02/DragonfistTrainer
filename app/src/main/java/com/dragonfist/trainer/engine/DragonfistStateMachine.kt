package com.dragonfist.trainer.engine

import com.dragonfist.trainer.data.CalibratedControlPoints
import com.dragonfist.trainer.safety.SpendGuardInterceptor
import com.dragonfist.trainer.vision.FrameTelemetry
import com.dragonfist.trainer.vision.RecognizedGameScene
import kotlin.math.abs

enum class TrainingGoal(val displayName: String, val description: String) {
    AUTO_OPTIMAL(
        "Auto-Detect & Adapt",
        "Automatically recognizes the active training equipment or combat stage on screen and applies its closed-loop controller."
    ),
    PUNCHING_BAG(
        "Punching Bag Cadence",
        "Adaptive strike pacing scaled to real-time stamina regeneration with Shadow Boxing SPG prep."
    ),
    WEIGHTLIFTING(
        "Weightlifting Tension Hold",
        "Closed-loop proportional hold and release controller keeping the tension needle centered in the green sweet spot."
    ),
    BALANCING(
        "Pole Balancing Equilibrium",
        "Corrective left and right micro-taps countering center-of-gravity tilt drift."
    ),
    WATER_SLAPPING(
        "Shaolin Water Slapping",
        "Phase-locked strike timing triggered when the returning water wave crest aligns with the target zone."
    ),
    PLANK_CHOP(
        "Karate Plank Chopping",
        "Precision single-strike trigger timed to the oscillating focus indicator."
    ),
    COMBAT_PATROL(
        "Planet Patrol & Combat",
        "Manages melee spacing, evades incoming Ki Blasts and heavy boss windups, and weaves charged Shadow Kicks."
    )
}

enum class AssistantState {
    STOPPED,
    PAUSED,
    WAITING_FOR_GAME,
    OBSERVING,
    SHADOW_BOXING_SPG,
    EXECUTING_TRAINING,
    STAMINA_RECOVERY,
    MEDITATING_EXP_CONVERT,
    COMBAT_ENGAGED,
    COMBAT_EVADING,
    HANDLING_TRANSITION,
    SPEND_GUARD_LOCKOUT,
    STUCK_RECOVERY,
    SAFE_HOLD_UNKNOWN
}

data class AutomationConfig(
    val goal: TrainingGoal = TrainingGoal.AUTO_OPTIMAL,
    val staminaFloorPercent: Int = 22,
    val staminaResumePercent: Int = 84,
    val enableShadowBoxingSPG: Boolean = true,
    val autoMeditateOnFullExp: Boolean = true,
    val combatUseChargedMoves: Boolean = true,
    val combatAutoDodgeProjectiles: Boolean = true,
    val minConfidenceThreshold: Float = 0.80f,
    val stuckTimeoutMs: Long = 4500L,
    val adaptivePollRateHz: Int = 3,
    val strictSpendGuard: Boolean = true
)

data class SafeGestureStroke(
    val startTimeOffsetMs: Long = 0L,
    val durationMs: Long = 55L,
    val startU: Float,
    val startV: Float,
    val endU: Float = startU,
    val endV: Float = startV
)

data class SafeGestureCommand(
    val summaryType: String,
    val strokes: List<SafeGestureStroke>,
    val reason: String
)

data class StateMachineDecision(
    val state: AssistantState,
    val headline: String,
    val reasoning: String,
    val gesture: SafeGestureCommand?,
    val lastActionVerified: Boolean
)

class DragonfistStateMachine(
    private val spendGuard: SpendGuardInterceptor
) {
    @Volatile
    var currentState: AssistantState = AssistantState.STOPPED
        private set

    private var isRecoveringStamina = false
    private var lastActionTimeMs = 0L
    private var lastVerifiedOutcomeTimeMs = System.currentTimeMillis()
    private var prevStamina = -1
    private var prevSpg = -1
    private var prevGaugePos = -1f
    private var prevEnemyHp = -1
    private var consecutiveUnverifiedActions = 0
    private var stuckProbesSent = 0

    fun start() {
        currentState = AssistantState.OBSERVING
        isRecoveringStamina = false
        lastVerifiedOutcomeTimeMs = System.currentTimeMillis()
        consecutiveUnverifiedActions = 0
        stuckProbesSent = 0
    }

    fun pause() {
        currentState = AssistantState.PAUSED
    }

    fun resume() {
        currentState = AssistantState.OBSERVING
        lastVerifiedOutcomeTimeMs = System.currentTimeMillis()
        consecutiveUnverifiedActions = 0
        stuckProbesSent = 0
    }

    fun stop() {
        currentState = AssistantState.STOPPED
        isRecoveringStamina = false
    }

    fun forceSafeHold() {
        currentState = AssistantState.SPEND_GUARD_LOCKOUT
    }

    fun onGameNotInForeground() {
        if (currentState != AssistantState.STOPPED && currentState != AssistantState.PAUSED) {
            currentState = AssistantState.WAITING_FOR_GAME
        }
    }

    fun step(
        frame: FrameTelemetry,
        config: AutomationConfig,
        calibration: CalibratedControlPoints
    ): StateMachineDecision {
        val now = System.currentTimeMillis()

        if (currentState == AssistantState.STOPPED || currentState == AssistantState.PAUSED) {
            return StateMachineDecision(
                state = currentState,
                headline = if (currentState == AssistantState.PAUSED) "Assistant Paused" else "Assistant Stopped",
                reasoning = "Observation idle. Tap Start or Resume to engage automation.",
                gesture = null,
                lastActionVerified = true
            )
        }

        // Resolve orientation-appropriate control coordinates
        val strikeU = if (frame.isLandscape) calibration.landscapeStrikeU else calibration.portraitStrikeU
        val strikeV = if (frame.isLandscape) calibration.landscapeStrikeV else calibration.portraitStrikeV
        val heavyU = if (frame.isLandscape) calibration.landscapeHeavyU else calibration.portraitHeavyU
        val heavyV = if (frame.isLandscape) calibration.landscapeHeavyV else calibration.portraitHeavyV
        val dodgeLeftU = if (frame.isLandscape) calibration.landscapeDodgeLeftU else calibration.portraitDodgeLeftU
        val dodgeLeftV = if (frame.isLandscape) calibration.landscapeDodgeLeftV else calibration.portraitDodgeLeftV
        val advanceRightU = if (frame.isLandscape) calibration.landscapeAdvanceRightU else calibration.portraitAdvanceRightU
        val advanceRightV = if (frame.isLandscape) calibration.landscapeAdvanceRightV else calibration.portraitAdvanceRightV
        val meditateU = if (frame.isLandscape) calibration.landscapeMeditateU else calibration.portraitMeditateU
        val meditateV = if (frame.isLandscape) calibration.landscapeMeditateV else calibration.portraitMeditateV

        // 1. MULTI-LAYER SPEND GUARD CHECK
        if (frame.spendModalDetected ||
            frame.recognizedScene == RecognizedGameScene.MONETIZATION_MODAL_LOCKED ||
            spendGuard.isLockedOut
        ) {
            currentState = AssistantState.SPEND_GUARD_LOCKOUT
            return StateMachineDecision(
                state = AssistantState.SPEND_GUARD_LOCKOUT,
                headline = "Spend Guard Active — Touch Dispatch Locked",
                reasoning = frame.spendModalReason
                    ?: spendGuard.lockoutReason
                    ?: "Protected store or billing dialog detected. Zero gestures dispatched.",
                gesture = null,
                lastActionVerified = false
            )
        }

        // 2. SCENE CONFIDENCE & LOADING/UNRECOGNIZED GATE
        if (frame.recognizedScene == RecognizedGameScene.UNRECOGNIZED_SCREEN ||
            frame.recognizedScene == RecognizedGameScene.LOADING_OR_TRANSITION ||
            frame.sceneConfidence < config.minConfidenceThreshold
        ) {
            currentState = AssistantState.SAFE_HOLD_UNKNOWN
            val isTransition = frame.recognizedScene == RecognizedGameScene.LOADING_OR_TRANSITION
            return StateMachineDecision(
                state = AssistantState.SAFE_HOLD_UNKNOWN,
                headline = if (isTransition) {
                    "Waiting for Scene Transition"
                } else {
                    "Safe Hold — Low Recognition Confidence (${(frame.sceneConfidence * 100).toInt()}%)"
                },
                reasoning = if (isTransition) {
                    "Dark transition or loading frame detected. Holding actions until HUD stabilizes."
                } else {
                    "Screen confidence is below ${(config.minConfidenceThreshold * 100).toInt()}% gate. Refusing to tap blindly."
                },
                gesture = null,
                lastActionVerified = false
            )
        }

        // 3. CLOSED-LOOP ACTION-OUTCOME VERIFICATION
        val outcomeVerified = frame.actionZoneMotionEnergy > 0.015f ||
            frame.globalMotionEnergy > 0.020f ||
            abs(frame.staminaPercent - prevStamina) >= 2 ||
            abs(frame.spgChargePercent - prevSpg) >= 2 ||
            (frame.gaugeDetected && abs(frame.gaugeMarkerPos - prevGaugePos) >= 0.015f) ||
            (frame.enemyDetected && abs(frame.enemyHealthPercent - prevEnemyHp) >= 2)

        if (outcomeVerified) {
            lastVerifiedOutcomeTimeMs = now
            consecutiveUnverifiedActions = 0
            stuckProbesSent = 0
        }

        prevStamina = frame.staminaPercent
        prevSpg = frame.spgChargePercent
        prevGaugePos = frame.gaugeMarkerPos
        prevEnemyHp = frame.enemyHealthPercent

        // 4. BOUNDED STUCK DETECTION & SAFE RECOVERY
        val elapsedWithoutOutcomeMs = now - lastVerifiedOutcomeTimeMs
        if (elapsedWithoutOutcomeMs > config.stuckTimeoutMs || consecutiveUnverifiedActions >= 5) {
            currentState = AssistantState.STUCK_RECOVERY

            if (stuckProbesSent >= 3) {
                currentState = AssistantState.PAUSED
                return StateMachineDecision(
                    state = AssistantState.PAUSED,
                    headline = "Auto-Paused After 3 Unverified Recovery Attempts",
                    reasoning = "Game screen did not respond to 3 bounded recovery probes. Paused safely.",
                    gesture = null,
                    lastActionVerified = false
                )
            }

            if (now - lastActionTimeMs >= 1500L) {
                stuckProbesSent++
                lastActionTimeMs = now
                return StateMachineDecision(
                    state = AssistantState.STUCK_RECOVERY,
                    headline = "Stuck Recovery Probe #$stuckProbesSent/3",
                    reasoning = "No verified pixel response for ${elapsedWithoutOutcomeMs / 1000.0}s. Tapping safe neutral lower dojo floor.",
                    gesture = buildSingleTap(
                        u = 0.50f,
                        v = 0.85f,
                        durationMs = 65L,
                        reason = "Safe neutral recovery probe #$stuckProbesSent",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = false
                )
            }

            return StateMachineDecision(
                state = AssistantState.STUCK_RECOVERY,
                headline = "Evaluating Recovery Response",
                reasoning = "Waiting for frame telemetry delta after recovery probe #$stuckProbesSent.",
                gesture = null,
                lastActionVerified = false
            )
        }

        // 5. VICTORY / DEFEAT TRANSITION MODALS
        if (frame.recognizedScene == RecognizedGameScene.VICTORY_OR_CONTINUE_MODAL ||
            frame.recognizedScene == RecognizedGameScene.DEFEAT_MODAL
        ) {
            currentState = AssistantState.HANDLING_TRANSITION
            if (now - lastActionTimeMs >= 850L) {
                lastActionTimeMs = now
                val isVictory = frame.recognizedScene == RecognizedGameScene.VICTORY_OR_CONTINUE_MODAL
                return StateMachineDecision(
                    state = AssistantState.HANDLING_TRANSITION,
                    headline = if (isVictory) "Stage Cleared — Continuing" else "Combat Defeat — Returning to Dojo",
                    reasoning = "Recognized post-battle summary banner. Tapping safe Continue button.",
                    gesture = buildSingleTap(
                        u = 0.50f,
                        v = 0.68f,
                        durationMs = 75L,
                        reason = "Advance past post-combat summary modal",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }
        }

        // 6. AUTO-MEDITATION ON FULL STORED EXP
        if (config.autoMeditateOnFullExp &&
            frame.expReadyForMeditation &&
            frame.recognizedScene != RecognizedGameScene.COMBAT_ACTIVE
        ) {
            currentState = AssistantState.MEDITATING_EXP_CONVERT
            if (now - lastActionTimeMs >= 600L) {
                lastActionTimeMs = now
                return StateMachineDecision(
                    state = AssistantState.MEDITATING_EXP_CONVERT,
                    headline = "Meditating to Convert Stored EXP",
                    reasoning = "Detected Meditation readiness aura. Triggering Meditation control.",
                    gesture = buildSingleTap(
                        u = meditateU,
                        v = meditateV,
                        durationMs = 95L,
                        reason = "Trigger Meditation EXP conversion",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }
        }

        // 7. HYSTERESIS STAMINA CONSERVATION
        if (frame.staminaExhaustedWarning ||
            (frame.staminaBarDetected && frame.staminaPercent <= config.staminaFloorPercent)
        ) {
            isRecoveringStamina = true
        } else if (!frame.staminaBarDetected || frame.staminaPercent >= config.staminaResumePercent) {
            isRecoveringStamina = false
        }

        if (isRecoveringStamina) {
            currentState = AssistantState.STAMINA_RECOVERY
            return StateMachineDecision(
                state = AssistantState.STAMINA_RECOVERY,
                headline = "Regenerating Stamina (${frame.staminaPercent}% -> ${config.staminaResumePercent}%)",
                reasoning = "Holding strikes above exhaustion floor (${config.staminaFloorPercent}%) to avoid 0% stamina penalty lockout.",
                gesture = null,
                lastActionVerified = true
            )
        }

        // 8. SHADOW BOXING (SPG) PRE-ROUTINE
        if (config.enableShadowBoxingSPG &&
            frame.spgChargePercent in 1..99 &&
            frame.recognizedScene != RecognizedGameScene.COMBAT_ACTIVE &&
            frame.recognizedScene != RecognizedGameScene.GYM_BALANCING
        ) {
            currentState = AssistantState.SHADOW_BOXING_SPG
            lastActionTimeMs = now
            if (!outcomeVerified) consecutiveUnverifiedActions++
            return StateMachineDecision(
                state = AssistantState.SHADOW_BOXING_SPG,
                headline = "Shadow Boxing SPG (${frame.spgChargePercent}% / 100%)",
                reasoning = "Dispatching 2-stroke air-punch cadence to build 100% Special Power Gains multiplier.",
                gesture = buildCadenceBurst(
                    u = strikeU,
                    v = strikeV,
                    tapCount = 2,
                    spacingMs = 165L,
                    reason = "Shadow boxing air-strike burst for SPG bonus",
                    strict = config.strictSpendGuard
                ),
                lastActionVerified = outcomeVerified
            )
        }

        // 9. COMBAT CONTROLLER (PLANET PATROL / DOJO BOSSES)
        if (frame.recognizedScene == RecognizedGameScene.COMBAT_ACTIVE ||
            (config.goal == TrainingGoal.COMBAT_PATROL && frame.enemyDetected)
        ) {
            if (config.combatAutoDodgeProjectiles &&
                (frame.incomingProjectileDetected || frame.enemyHeavyWindupDetected)
            ) {
                currentState = AssistantState.COMBAT_EVADING
                lastActionTimeMs = now
                return StateMachineDecision(
                    state = AssistantState.COMBAT_EVADING,
                    headline = if (frame.incomingProjectileDetected) {
                        "Evading Incoming Ki Blast"
                    } else {
                        "Evading Boss Heavy Windup"
                    },
                    reasoning = "Tapping Dodge/Move-Left control to evade incoming heavy attack.",
                    gesture = buildSingleTap(
                        u = dodgeLeftU,
                        v = dodgeLeftV,
                        durationMs = 140L,
                        reason = "Defensive backward evade",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }

            currentState = AssistantState.COMBAT_ENGAGED
            if (frame.enemyDistanceNormalized > 0.34f) {
                lastActionTimeMs = now
                return StateMachineDecision(
                    state = AssistantState.COMBAT_ENGAGED,
                    headline = "Advancing into Melee Range",
                    reasoning = "Closing normalized distance (${(frame.enemyDistanceNormalized * 100).toInt()}%) to enemy.",
                    gesture = buildSingleTap(
                        u = advanceRightU,
                        v = advanceRightV,
                        durationMs = 160L,
                        reason = "Advance right toward enemy",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }

            if (config.combatUseChargedMoves && frame.staminaPercent >= 65) {
                lastActionTimeMs = now
                return StateMachineDecision(
                    state = AssistantState.COMBAT_ENGAGED,
                    headline = "Executing Charged Shadow Kick",
                    reasoning = "Stamina headroom high (${frame.staminaPercent}%). Holding 320ms heavy attack.",
                    gesture = buildSingleTap(
                        u = heavyU,
                        v = heavyV,
                        durationMs = 320L,
                        reason = "Charged heavy technique strike",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }

            lastActionTimeMs = now
            if (!outcomeVerified) consecutiveUnverifiedActions++
            return StateMachineDecision(
                state = AssistantState.COMBAT_ENGAGED,
                headline = "Melee Combo (Enemy HP ${frame.enemyHealthPercent}%)",
                reasoning = "Delivering stamina-paced 2-hit martial arts combo.",
                gesture = buildCadenceBurst(
                    u = strikeU,
                    v = strikeV,
                    tapCount = 2,
                    spacingMs = 160L,
                    reason = "2-hit melee combo burst",
                    strict = config.strictSpendGuard
                ),
                lastActionVerified = outcomeVerified
            )
        }

        // 10. TRAINING ACTIVITY CONTROLLERS
        currentState = AssistantState.EXECUTING_TRAINING

        val effectiveScene = when (config.goal) {
            TrainingGoal.WEIGHTLIFTING -> RecognizedGameScene.GYM_WEIGHTLIFTING
            TrainingGoal.BALANCING -> RecognizedGameScene.GYM_BALANCING
            TrainingGoal.WATER_SLAPPING -> RecognizedGameScene.GYM_WATER_SLAPPING
            TrainingGoal.PLANK_CHOP -> RecognizedGameScene.GYM_PLANK_CHOP
            TrainingGoal.PUNCHING_BAG -> RecognizedGameScene.GYM_PUNCHING_BAG
            else -> frame.recognizedScene
        }

        // A. WEIGHTLIFTING (Closed-loop proportional hold controller)
        if (effectiveScene == RecognizedGameScene.GYM_WEIGHTLIFTING && frame.gaugeDetected) {
            val sweetCenter = (frame.gaugeSweetSpotMin + frame.gaugeSweetSpotMax) * 0.5f
            val distanceBelow = sweetCenter - frame.gaugeMarkerPos
            if (distanceBelow > -0.02f) {
                lastActionTimeMs = now
                val holdMs = (140L + (distanceBelow.coerceIn(0f, 0.4f) * 420f).toLong()).coerceIn(90L, 310L)
                return StateMachineDecision(
                    state = AssistantState.EXECUTING_TRAINING,
                    headline = "Weightlifting — Proportional Lift Hold (${holdMs}ms)",
                    reasoning = "Tension needle at ${(frame.gaugeMarkerPos * 100).toInt()}%. Holding strike button for ${holdMs}ms to center inside sweet spot.",
                    gesture = buildSingleTap(
                        u = strikeU,
                        v = strikeV,
                        durationMs = holdMs,
                        reason = "Proportional barbell lift hold (${holdMs}ms)",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }
            return StateMachineDecision(
                state = AssistantState.EXECUTING_TRAINING,
                headline = "Weightlifting — Feathering Upper Sweet Spot",
                reasoning = "Allowing needle (${(frame.gaugeMarkerPos * 100).toInt()}%) to settle inside green multiplier zone.",
                gesture = null,
                lastActionVerified = outcomeVerified
            )
        }

        // B. POLE BALANCING (Corrective left/right tilt counter-taps)
        if (effectiveScene == RecognizedGameScene.GYM_BALANCING && frame.gaugeDetected) {
            val center = (frame.gaugeSweetSpotMin + frame.gaugeSweetSpotMax) * 0.5f
            val drift = frame.gaugeMarkerPos - center
            if (abs(drift) > 0.04f) {
                lastActionTimeMs = now
                val counterLeft = drift > 0f
                return StateMachineDecision(
                    state = AssistantState.EXECUTING_TRAINING,
                    headline = "Balancing — Countering ${if (drift > 0f) "Right" else "Left"} Drift",
                    reasoning = "Center of gravity drifted ${(drift * 100).toInt()}%. Tapping corrective ${if (counterLeft) "left" else "right"} control.",
                    gesture = buildSingleTap(
                        u = if (counterLeft) dodgeLeftU else strikeU,
                        v = if (counterLeft) dodgeLeftV else strikeV,
                        durationMs = 55L,
                        reason = "Corrective ${if (counterLeft) "left" else "right"} balance tap",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }
            return StateMachineDecision(
                state = AssistantState.EXECUTING_TRAINING,
                headline = "Pole Balancing — Equilibrium Locked",
                reasoning = "Center of gravity stabilized inside green target band.",
                gesture = null,
                lastActionVerified = outcomeVerified
            )
        }

        // C. WATER SLAPPING & PLANK CHOP (Phase-locked sweet-spot strike)
        if ((effectiveScene == RecognizedGameScene.GYM_WATER_SLAPPING ||
                effectiveScene == RecognizedGameScene.GYM_PLANK_CHOP) &&
            frame.gaugeDetected
        ) {
            val inSweetSpot =
                frame.gaugeMarkerPos in frame.gaugeSweetSpotMin..frame.gaugeSweetSpotMax
            if (inSweetSpot) {
                lastActionTimeMs = now
                val label = if (effectiveScene == RecognizedGameScene.GYM_WATER_SLAPPING) {
                    "Shaolin Water Slap"
                } else {
                    "Karate Plank Chop"
                }
                return StateMachineDecision(
                    state = AssistantState.EXECUTING_TRAINING,
                    headline = "$label — Precision Sweet-Spot Strike",
                    reasoning = "Indicator (${(frame.gaugeMarkerPos * 100).toInt()}%) aligned inside target window.",
                    gesture = buildSingleTap(
                        u = strikeU,
                        v = strikeV,
                        durationMs = 50L,
                        reason = "Precision $label strike",
                        strict = config.strictSpendGuard
                    ),
                    lastActionVerified = outcomeVerified
                )
            }
            return StateMachineDecision(
                state = AssistantState.EXECUTING_TRAINING,
                headline = "Tracking Sweet-Spot Window (${(frame.gaugeMarkerPos * 100).toInt()}%)",
                reasoning = "Waiting for indicator to enter green timing window before striking.",
                gesture = null,
                lastActionVerified = outcomeVerified
            )
        }

        // D. PUNCHING BAG (Adaptive stamina-scaled single or dual-tap cadence burst)
        val highStaminaHeadroom = frame.staminaPercent >= (config.staminaFloorPercent + 25)
        lastActionTimeMs = now
        if (!outcomeVerified) consecutiveUnverifiedActions++

        return if (highStaminaHeadroom) {
            StateMachineDecision(
                state = AssistantState.EXECUTING_TRAINING,
                headline = "Punching Bag — High-Headroom 2-Strike Cadence",
                reasoning = "Stamina at ${frame.staminaPercent}%. Dispatching paced 2-stroke strike burst within capture window.",
                gesture = buildCadenceBurst(
                    u = strikeU,
                    v = strikeV,
                    tapCount = 2,
                    spacingMs = 175L,
                    reason = "Adaptive 2-stroke heavy bag cadence",
                    strict = config.strictSpendGuard
                ),
                lastActionVerified = outcomeVerified
            )
        } else {
            StateMachineDecision(
                state = AssistantState.EXECUTING_TRAINING,
                headline = "Punching Bag — Conserved Single-Strike Cadence",
                reasoning = "Stamina approaching conservation band (${frame.staminaPercent}%). Feathering to single strike per window.",
                gesture = buildSingleTap(
                    u = strikeU,
                    v = strikeV,
                    durationMs = 55L,
                    reason = "Conserved single heavy bag strike",
                    strict = config.strictSpendGuard
                ),
                lastActionVerified = outcomeVerified
            )
        }
    }

    private fun buildSingleTap(
        u: Float,
        v: Float,
        durationMs: Long,
        reason: String,
        strict: Boolean
    ): SafeGestureCommand? {
        if (!spendGuard.verifyNormalizedCoordinateSafe(u, v, strict)) {
            return null
        }
        return SafeGestureCommand(
            summaryType = if (durationMs > 120L) "HOLD (${durationMs}ms)" else "TAP",
            strokes = listOf(
                SafeGestureStroke(
                    startTimeOffsetMs = 0L,
                    durationMs = durationMs,
                    startU = u,
                    startV = v
                )
            ),
            reason = reason
        )
    }

    private fun buildCadenceBurst(
        u: Float,
        v: Float,
        tapCount: Int,
        spacingMs: Long,
        reason: String,
        strict: Boolean
    ): SafeGestureCommand? {
        if (!spendGuard.verifyNormalizedCoordinateSafe(u, v, strict)) {
            return null
        }
        val strokeList = (0 until tapCount.coerceIn(1, 3)).map { idx ->
            SafeGestureStroke(
                startTimeOffsetMs = idx * spacingMs,
                durationMs = 50L,
                startU = u,
                startV = v
            )
        }
        return SafeGestureCommand(
            summaryType = "BURST_${strokeList.size}X",
            strokes = strokeList,
            reason = reason
        )
    }
}
