package com.dragonfist.trainer.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RecognizedGameScene {
    GYM_PUNCHING_BAG,
    GYM_WEIGHTLIFTING,
    GYM_BALANCING,
    GYM_WATER_SLAPPING,
    GYM_PLANK_CHOP,
    SHADOW_BOXING_FLOOR,
    MEDITATION_READY,
    COMBAT_ACTIVE,
    VICTORY_OR_CONTINUE_MODAL,
    DEFEAT_MODAL,
    MONETIZATION_MODAL_LOCKED,
    LOADING_OR_TRANSITION,
    UNRECOGNIZED_SCREEN
}

data class FrameTelemetry(
    val width: Int,
    val height: Int,
    val isLandscape: Boolean,
    val safeViewportRect: RectF,
    val recognizedScene: RecognizedGameScene,
    val sceneConfidence: Float,
    val staminaPercent: Int,
    val staminaBarDetected: Boolean,
    val staminaExhaustedWarning: Boolean,
    val healthPercent: Int,
    val spgChargePercent: Int,
    val expReadyForMeditation: Boolean,
    val gaugeDetected: Boolean,
    val gaugeMarkerPos: Float,
    val gaugeSweetSpotMin: Float,
    val gaugeSweetSpotMax: Float,
    val enemyDetected: Boolean,
    val enemyHealthPercent: Int,
    val enemyDistanceNormalized: Float,
    val incomingProjectileDetected: Boolean,
    val enemyHeavyWindupDetected: Boolean,
    val globalMotionEnergy: Float,
    val actionZoneMotionEnergy: Float,
    val spendModalDetected: Boolean,
    val spendModalReason: String?
)

/**
 * Resolution- and orientation-adaptive computer vision analyzer for Dragonfist Limitless.
 * Downsamples screenshots once to 320x180, detects active letterbox/pillarbox insets directly
 * on the pixel array, and uses horizontal run-length bar segmentation to locate HUD Stamina/HP
 * bars in both Landscape and Portrait layouts.
 */
class PixelVisionAnalyzer {
    private val analysisW = 320
    private val analysisH = 180
    private val pixels = IntArray(analysisW * analysisH)

    private val gridW = 32
    private val gridH = 18
    private val prevLumaGrid = FloatArray(gridW * gridH)
    private var hasPrevFrame = false

    fun analyzeFrame(source: Bitmap): FrameTelemetry {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val isLandscape = srcW >= srcH

        val scaled = Bitmap.createScaledBitmap(source, analysisW, analysisH, false)
        scaled.getPixels(pixels, 0, analysisW, 0, 0, analysisW, analysisH)
        if (scaled !== source) {
            scaled.recycle()
        }

        // 1. Detect active game viewport in normalized [0..1] space (stripping black cutout/letterbox bars)
        val normViewport = detectNormalizedActiveViewport()
        val safeRect = RectF(
            normViewport.left * srcW,
            normViewport.top * srcH,
            normViewport.right * srcW,
            normViewport.bottom * srcH
        )

        val hsv = FloatArray(3)

        // Samples a pixel inside the active game viewport at normalized (u, v) in [0..1]
        fun sampleUV(u: Float, v: Float): FloatArray {
            val mappedU = normViewport.left + u.coerceIn(0f, 1f) * normViewport.width()
            val mappedV = normViewport.top + v.coerceIn(0f, 1f) * normViewport.height()
            val x = (mappedU * (analysisW - 1)).toInt().coerceIn(0, analysisW - 1)
            val y = (mappedV * (analysisH - 1)).toInt().coerceIn(0, analysisH - 1)
            val c = pixels[y * analysisW + x]
            Color.colorToHSV(c, hsv)
            return floatArrayOf(
                Color.red(c).toFloat(),
                Color.green(c).toFloat(),
                Color.blue(c).toFloat(),
                hsv[0],
                hsv[1],
                hsv[2]
            )
        }

        // 2. Compute Global & Action-Zone L1 Motion Energy
        var globalMotionSum = 0f
        var actionZoneSum = 0f
        var actionZoneCells = 0

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val u = (gx + 0.5f) / gridW
                val v = (gy + 0.5f) / gridH
                val sample = sampleUV(u, v)
                val luma = (0.299f * sample[0] + 0.587f * sample[1] + 0.114f * sample[2]) / 255f
                val idx = gy * gridW + gx
                if (hasPrevFrame) {
                    val diff = abs(luma - prevLumaGrid[idx])
                    globalMotionSum += diff
                    val inPlayfield = if (isLandscape) {
                        u in 0.18f..0.82f && v in 0.25f..0.78f
                    } else {
                        u in 0.12f..0.88f && v in 0.16f..0.58f
                    }
                    if (inPlayfield) {
                        actionZoneSum += diff
                        actionZoneCells++
                    }
                }
                prevLumaGrid[idx] = luma
            }
        }
        hasPrevFrame = true

        val globalMotionEnergy = min(1f, (globalMotionSum / (gridW * gridH)) * 8.5f)
        val actionZoneMotionEnergy = if (actionZoneCells > 0) {
            min(1f, (actionZoneSum / actionZoneCells) * 9.5f)
        } else {
            globalMotionEnergy
        }

        // 3. Connected Horizontal Run-Length Bar Scanner for Stamina, Health, and Ki/SPG
        // Scans both upper HUD (v: 0.04..0.20) and lower/character HUD (v: 0.68..0.88)
        val hudCandidateRows = floatArrayOf(
            0.055f, 0.072f, 0.088f, 0.105f, 0.122f, 0.140f, 0.160f, 0.180f,
            0.700f, 0.740f, 0.780f, 0.820f, 0.860f
        )

        var bestHpPercent = 0
        var bestStaminaPercent = 0
        var bestSpgPercent = 0
        var staminaTrackFound = false
        var staminaRedFlashPixels = 0
        val scanCols = 48

        for (rowV in hudCandidateRows) {
            var hpRun = 0
            var stamRun = 0
            var spgRun = 0
            var darkTrackFollowingStam = 0

            for (col in 0 until scanCols) {
                val u = 0.04f + (col.toFloat() / (scanCols - 1)) * 0.44f
                val s = sampleUV(u, rowV)
                val h = s[3]
                val sat = s[4]
                val value = s[5]

                // Crimson/Red HP signature
                if ((h <= 20f || h >= 340f) && sat > 0.48f && value > 0.38f) {
                    if (rowV < 0.10f) hpRun++ else staminaRedFlashPixels++
                }
                // Yellow/Amber/Green Stamina bar fill signature
                if (h in 24f..138f && sat > 0.45f && value > 0.40f) {
                    stamRun++
                } else if (stamRun >= 3 && value < 0.24f) {
                    darkTrackFollowingStam++
                }
                // Cyan/Blue SPG / Ki bar fill signature
                if (h in 172f..225f && sat > 0.45f && value > 0.42f) {
                    spgRun++
                }
            }

            if (stamRun + darkTrackFollowingStam >= 10) {
                staminaTrackFound = true
                val rowPct = ((stamRun.toFloat() / (stamRun + darkTrackFollowingStam).coerceAtLeast(1)) * 100f).toInt()
                bestStaminaPercent = max(bestStaminaPercent, rowPct.coerceIn(0, 100))
            } else if (stamRun >= 8) {
                staminaTrackFound = true
                val rowPct = ((stamRun * 100f) / scanCols).toInt().coerceIn(0, 100)
                bestStaminaPercent = max(bestStaminaPercent, rowPct)
            }

            if (hpRun >= 4) {
                bestHpPercent = max(bestHpPercent, ((hpRun * 100f) / scanCols).toInt().coerceIn(0, 100))
            }
            if (spgRun >= 3) {
                bestSpgPercent = max(bestSpgPercent, ((spgRun * 100f) / scanCols).toInt().coerceIn(0, 100))
            }
        }

        val staminaExhaustedWarning = staminaTrackFound && bestStaminaPercent <= 10 && staminaRedFlashPixels >= 5

        // 4. Detect Meditation Ready Aura / Indicator
        var meditationGlowCount = 0
        var mu = 0.76f
        while (mu <= 0.94f) {
            var mv = 0.44f
            while (mv <= 0.92f) {
                val ms = sampleUV(mu, mv)
                if (ms[3] in 245f..305f && ms[4] > 0.48f && ms[5] > 0.55f) {
                    meditationGlowCount++
                }
                mv += 0.04f
            }
            mu += 0.03f
        }
        val expReadyForMeditation = meditationGlowCount >= 5

        // 5. Multi-Layer Chromatic & Modal Scrim Spend Guard Inspection
        var purpleStorePixels = 0
        var playSheetWhitePixels = 0
        var playBuyGreenPixels = 0
        var cosmeticPinkPixels = 0
        var victoryGoldPixels = 0
        var defeatCrimsonPixels = 0

        var su = 0.20f
        while (su <= 0.80f) {
            var sv = 0.22f
            while (sv <= 0.78f) {
                val s = sampleUV(su, sv)
                val h = s[3]
                val sat = s[4]
                val value = s[5]

                if (h in 280f..325f && sat > 0.52f && value > 0.45f) purpleStorePixels++
                if (sv >= 0.48f && s[0] > 230f && s[1] > 232f && s[2] > 235f && sat < 0.08f) playSheetWhitePixels++
                if (sv in 0.62f..0.78f && h in 135f..170f && sat > 0.58f && value > 0.45f) playBuyGreenPixels++
                if (h in 326f..346f && sat > 0.52f && value > 0.55f) cosmeticPinkPixels++
                if (sv in 0.28f..0.48f && h in 40f..58f && sat > 0.68f && value > 0.72f) victoryGoldPixels++
                if (sv in 0.28f..0.48f && (h <= 12f || h >= 350f) && sat > 0.70f && value > 0.52f) defeatCrimsonPixels++
                sv += 0.04f
            }
            su += 0.035f
        }

        val spendDetected = purpleStorePixels >= 18 ||
            (playSheetWhitePixels >= 22 && playBuyGreenPixels >= 3) ||
            cosmeticPinkPixels >= 16

        val spendReason = when {
            purpleStorePixels >= 18 -> "Dragon Token store modal signature detected"
            playSheetWhitePixels >= 22 && playBuyGreenPixels >= 3 -> "Google Play Billing sheet signature detected"
            cosmeticPinkPixels >= 16 -> "Cosmetic vendor dialog detected"
            else -> null
        }

        // 6. Dynamic Minigame Tension / Balance / Ripple Gauge Scan
        var greenSweetCount = 0
        var minSweet = 1f
        var maxSweet = 0f
        var markerPos = 0.5f
        var brightestNeedleVal = 0f

        if (!spendDetected) {
            val gaugeSteps = 60
            val gaugeV = if (isLandscape) 0.245f else 0.20f
            for (i in 0 until gaugeSteps) {
                val norm = i.toFloat() / (gaugeSteps - 1)
                val gu = 0.25f + norm * 0.50f
                val gs = sampleUV(gu, gaugeV)
                if (gs[0] > 232f && gs[1] > 232f && gs[2] > 232f && gs[5] > brightestNeedleVal) {
                    brightestNeedleVal = gs[5]
                    markerPos = norm
                }
                if (gs[3] in 110f..168f && gs[4] > 0.45f && gs[5] > 0.42f) {
                    greenSweetCount++
                    minSweet = min(minSweet, norm)
                    maxSweet = max(maxSweet, norm)
                }
            }
        }
        val gaugeActive = !spendDetected && greenSweetCount >= 3 && brightestNeedleVal > 0.88f

        // 7. Combat Enemy & Projectile Scan
        var enemyHpSamples = 0
        for (i in 0 until 30) {
            val u = 0.56f + (i / 29f) * 0.36f
            val es = sampleUV(u, 0.075f)
            if ((es[3] <= 24f || es[3] >= 336f) && es[4] > 0.45f && es[5] > 0.38f) {
                enemyHpSamples++
            }
        }
        val enemyDetected = !spendDetected && enemyHpSamples >= 3
        val enemyHealthPct = ((enemyHpSamples * 100f) / 30f).toInt().coerceIn(0, 100)

        var enemyDistanceNorm = 0.45f
        var incomingProjectile = false
        var enemyHeavyWindup = false

        if (enemyDetected) {
            val planeV = if (isLandscape) 0.56f else 0.42f
            var enemyU = 0.72f
            var cu = 0.38f
            while (cu <= 0.88f) {
                val cs = sampleUV(cu, planeV)
                if ((cs[3] >= 310f || cs[3] <= 22f) && cs[4] > 0.52f && cs[5] > 0.42f) {
                    enemyU = cu
                    val windupSample = sampleUV(cu, planeV - 0.10f)
                    if (windupSample[3] in 35f..65f && windupSample[4] > 0.68f && windupSample[5] > 0.78f) {
                        enemyHeavyWindup = true
                    }
                    break
                }
                cu += 0.02f
            }
            enemyDistanceNorm = (enemyU - 0.28f).coerceIn(0.05f, 0.65f)

            var pu = 0.32f
            while (pu < enemyU - 0.04f) {
                val ps = sampleUV(pu, planeV)
                if (ps[3] in 175f..215f && ps[4] > 0.60f && ps[5] > 0.78f) {
                    incomingProjectile = true
                    break
                }
                pu += 0.02f
            }
        }

        // 8. Scene Classification & Calibrated Confidence
        val targetV = if (isLandscape) 0.56f else 0.42f
        val propSample = sampleUV(0.62f, targetV)
        val centerSample = sampleUV(0.50f, 0.50f)
        val floorSample = sampleUV(0.50f, if (isLandscape) 0.82f else 0.55f)

        val recognizedScene: RecognizedGameScene
        val confidence: Float

        when {
            spendDetected -> {
                recognizedScene = RecognizedGameScene.MONETIZATION_MODAL_LOCKED
                confidence = 0.98f
            }
            centerSample[5] < 0.08f && floorSample[5] < 0.08f && !staminaTrackFound -> {
                recognizedScene = RecognizedGameScene.LOADING_OR_TRANSITION
                confidence = 0.60f
            }
            victoryGoldPixels >= 10 && !staminaTrackFound -> {
                recognizedScene = RecognizedGameScene.VICTORY_OR_CONTINUE_MODAL
                confidence = 0.92f
            }
            defeatCrimsonPixels >= 10 && !staminaTrackFound -> {
                recognizedScene = RecognizedGameScene.DEFEAT_MODAL
                confidence = 0.91f
            }
            enemyDetected -> {
                recognizedScene = RecognizedGameScene.COMBAT_ACTIVE
                confidence = 0.93f
            }
            gaugeActive -> {
                recognizedScene = when {
                    propSample[3] in 185f..225f && propSample[4] > 0.42f -> RecognizedGameScene.GYM_WATER_SLAPPING
                    propSample[3] in 25f..48f && propSample[4] > 0.42f -> RecognizedGameScene.GYM_BALANCING
                    propSample[3] in 12f..24f && propSample[4] > 0.45f -> RecognizedGameScene.GYM_PLANK_CHOP
                    else -> RecognizedGameScene.GYM_WEIGHTLIFTING
                }
                confidence = 0.92f
            }
            staminaTrackFound && propSample[3] in 0f..25f && propSample[4] > 0.45f -> {
                recognizedScene = RecognizedGameScene.GYM_PUNCHING_BAG
                confidence = 0.94f
            }
            staminaTrackFound || bestHpPercent > 0 -> {
                recognizedScene = if (expReadyForMeditation) {
                    RecognizedGameScene.MEDITATION_READY
                } else {
                    RecognizedGameScene.SHADOW_BOXING_FLOOR
                }
                confidence = 0.88f
            }
            else -> {
                recognizedScene = RecognizedGameScene.UNRECOGNIZED_SCREEN
                confidence = 0.30f
            }
        }

        return FrameTelemetry(
            width = srcW,
            height = srcH,
            isLandscape = isLandscape,
            safeViewportRect = safeRect,
            recognizedScene = recognizedScene,
            sceneConfidence = confidence,
            staminaPercent = bestStaminaPercent,
            staminaBarDetected = staminaTrackFound,
            staminaExhaustedWarning = staminaExhaustedWarning,
            healthPercent = bestHpPercent,
            spgChargePercent = bestSpgPercent,
            expReadyForMeditation = expReadyForMeditation,
            gaugeDetected = gaugeActive,
            gaugeMarkerPos = markerPos,
            gaugeSweetSpotMin = if (gaugeActive) minSweet else 0.42f,
            gaugeSweetSpotMax = if (gaugeActive) maxSweet else 0.58f,
            enemyDetected = enemyDetected,
            enemyHealthPercent = enemyHealthPct,
            enemyDistanceNormalized = enemyDistanceNorm,
            incomingProjectileDetected = incomingProjectile,
            enemyHeavyWindupDetected = enemyHeavyWindup,
            globalMotionEnergy = globalMotionEnergy,
            actionZoneMotionEnergy = actionZoneMotionEnergy,
            spendModalDetected = spendDetected,
            spendModalReason = spendReason
        )
    }

    private fun detectNormalizedActiveViewport(): RectF {
        fun lumaAt(x: Int, y: Int): Float {
            val c = pixels[y.coerceIn(0, analysisH - 1) * analysisW + x.coerceIn(0, analysisW - 1)]
            return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }

        var left = 0
        var right = analysisW - 1
        var top = 0
        var bottom = analysisH - 1

        val midY = analysisH / 2
        val midX = analysisW / 2
        val maxXInset = analysisW / 6
        val maxYInset = analysisH / 6

        while (left < maxXInset && lumaAt(left, midY) < 0.015f) left++
        while (right > analysisW - maxXInset && lumaAt(right, midY) < 0.015f) right--
        while (top < maxYInset && lumaAt(midX, top) < 0.015f) top++
        while (bottom > analysisH - maxYInset && lumaAt(midX, bottom) < 0.015f) bottom--

        return RectF(
            (left.toFloat() / analysisW).coerceIn(0f, 0.2f),
            (top.toFloat() / analysisH).coerceIn(0f, 0.2f),
            ((right + 1).toFloat() / analysisW).coerceIn(0.8f, 1f),
            ((bottom + 1).toFloat() / analysisH).coerceIn(0.8f, 1f)
        )
    }
}
