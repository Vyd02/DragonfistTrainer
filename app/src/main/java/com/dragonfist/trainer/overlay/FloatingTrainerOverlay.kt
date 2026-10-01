package com.dragonfist.trainer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dragonfist.trainer.data.RuntimeTelemetrySnapshot
import com.dragonfist.trainer.data.TrainerRepository
import com.dragonfist.trainer.engine.AssistantState
import kotlin.math.abs

/**
 * Production TYPE_ACCESSIBILITY_OVERLAY floating controller for Google Pixel 9 Pro.
 * Supports drag-to-reposition with screen-bounds clamping across Portrait/Landscape rotation,
 * Start/Pause/Resume/Stop controls, Collapse/Expand, and an interactive in-game target reticle
 * to calibrate exact button coordinates directly over Dragonfist Limitless.
 */
class FloatingTrainerOverlay(
    private val serviceContext: Context,
    private val onToggleStartPause: () -> Unit,
    private val onStopRequested: () -> Unit
) {
    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: LinearLayout? = null
    private var headlineView: TextView? = null
    private var detailView: TextView? = null
    private var metricsView: TextView? = null
    private var startPauseButton: TextView? = null
    private var calibrateButton: TextView? = null
    private var bodyContainer: LinearLayout? = null
    private var isExpanded: Boolean = true

    // Optional floating calibration reticle window
    private var reticleView: TextView? = null
    private lateinit var reticleParams: WindowManager.LayoutParams
    private var calibratingTargetIndex: Int = 0 // 0=Off, 1=Strike, 2=Heavy, 3=Meditate

    private lateinit var layoutParams: WindowManager.LayoutParams

    private fun dp(value: Int): Int {
        val density = serviceContext.resources.displayMetrics.density
        return (value * density).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootLayout != null) return

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(48)
        }

        val cardBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#EC0F172A"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#334155"))
        }

        val root = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val headerRow = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(serviceContext).apply {
            text = "DF Trainer"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(0, 0, dp(8), 0)
        }

        val actionBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#10B981"))
            cornerRadius = dp(8).toFloat()
        }

        val startPauseBtn = TextView(serviceContext).apply {
            text = "Start"
            setTextColor(Color.parseColor("#090D16"))
            textSize = 11f
            paint.isFakeBoldText = true
            background = actionBtnBg
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { onToggleStartPause() }
        }

        val stopBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#7F1D1D"))
            cornerRadius = dp(8).toFloat()
        }

        val stopBtn = TextView(serviceContext).apply {
            text = "Stop"
            setTextColor(Color.parseColor("#FECACA"))
            textSize = 11f
            background = stopBtnBg
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
            setOnClickListener { onStopRequested() }
        }

        val calBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.parseColor("#475569"))
        }

        val calBtn = TextView(serviceContext).apply {
            text = "Target"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            background = calBtnBg
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
            setOnClickListener { cycleCalibrationReticle() }
        }

        val collapseBtn = TextView(serviceContext).apply {
            text = "–"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                isExpanded = !isExpanded
                bodyContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                text = if (isExpanded) "–" else "+"
            }
        }

        headerRow.addView(titleText)
        headerRow.addView(startPauseBtn)
        headerRow.addView(stopBtn)
        headerRow.addView(calBtn)
        headerRow.addView(collapseBtn)

        var downRawX = 0f
        var downRawY = 0f
        var startWinX = 0
        var startWinY = 0
        val touchSlop = dp(6)

        headerRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWinX = layoutParams.x
                    startWinY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        val dm = serviceContext.resources.displayMetrics
                        layoutParams.x = (startWinX + dx).coerceIn(0, (dm.widthPixels - dp(140)).coerceAtLeast(0))
                        layoutParams.y = (startWinY + dy).coerceIn(0, (dm.heightPixels - dp(80)).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(root, layoutParams) }
                    }
                    true
                }
                else -> false
            }
        }

        val body = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val headline = TextView(serviceContext).apply {
            text = "Waiting for Dragonfist Limitless"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 11.5f
            paint.isFakeBoldText = true
        }

        val detail = TextView(serviceContext).apply {
            text = "Open Dragonfist Limitless and tap Start."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10.5f
            maxWidth = dp(260)
        }

        val metrics = TextView(serviceContext).apply {
            text = "Stamina: --%  ·  SPG: --%  ·  Conf: --%"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10f
            setPadding(0, dp(4), 0, 0)
        }

        body.addView(headline)
        body.addView(detail)
        body.addView(metrics)

        root.addView(headerRow)
        root.addView(body)

        rootLayout = root
        headlineView = headline
        detailView = detail
        metricsView = metrics
        startPauseButton = startPauseBtn
        calibrateButton = calBtn
        bodyContainer = body

        runCatching {
            windowManager.addView(root, layoutParams)
        }
    }

    /**
     * Allows the user to drag a visual reticle over their exact in-game Strike, Heavy, or Meditate
     * button and save its normalized (u, v) coordinate for the current screen orientation.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun cycleCalibrationReticle() {
        val dm = serviceContext.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val isLandscape = screenW >= screenH

        // Save previous reticle position before advancing to the next target
        reticleView?.let {
            val centerU = ((reticleParams.x + dp(36)).toFloat() / screenW).coerceIn(0.05f, 0.95f)
            val centerV = ((reticleParams.y + dp(22)).toFloat() / screenH).coerceIn(0.15f, 0.95f)
            when (calibratingTargetIndex) {
                1 -> TrainerRepository.updateCalibration { c ->
                    if (isLandscape) c.copy(landscapeStrikeU = centerU, landscapeStrikeV = centerV)
                    else c.copy(portraitStrikeU = centerU, portraitStrikeV = centerV)
                }
                2 -> TrainerRepository.updateCalibration { c ->
                    if (isLandscape) c.copy(landscapeHeavyU = centerU, landscapeHeavyV = centerV)
                    else c.copy(portraitHeavyU = centerU, portraitHeavyV = centerV)
                }
                3 -> TrainerRepository.updateCalibration { c ->
                    if (isLandscape) c.copy(landscapeMeditateU = centerU, landscapeMeditateV = centerV)
                    else c.copy(portraitMeditateU = centerU, portraitMeditateV = centerV)
                }
            }
        }

        calibratingTargetIndex = (calibratingTargetIndex + 1) % 4

        if (calibratingTargetIndex == 0) {
            hideReticle()
            calibrateButton?.text = "Target"
            return
        }

        val cal = TrainerRepository.calibration.value
        val (targetLabel, initU, initV) = when (calibratingTargetIndex) {
            1 -> Triple(
                "DRAG TO STRIKE BTN",
                if (isLandscape) cal.landscapeStrikeU else cal.portraitStrikeU,
                if (isLandscape) cal.landscapeStrikeV else cal.portraitStrikeV
            )
            2 -> Triple(
                "DRAG TO HEAVY BTN",
                if (isLandscape) cal.landscapeHeavyU else cal.portraitHeavyU,
                if (isLandscape) cal.landscapeHeavyV else cal.portraitHeavyV
            )
            else -> Triple(
                "DRAG TO MEDITATE",
                if (isLandscape) cal.landscapeMeditateU else cal.portraitMeditateU,
                if (isLandscape) cal.landscapeMeditateV else cal.portraitMeditateV
            )
        }

        calibrateButton?.text = if (calibratingTargetIndex < 3) "Next" else "Save"

        if (reticleView == null) {
            reticleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val reticleBg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0284C7"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }

            val rv = TextView(serviceContext).apply {
                setTextColor(Color.WHITE)
                textSize = 10.5f
                paint.isFakeBoldText = true
                background = reticleBg
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }

            var rDownX = 0f
            var rDownY = 0f
            var rStartX = 0
            var rStartY = 0

            rv.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        rDownX = ev.rawX
                        rDownY = ev.rawY
                        rStartX = reticleParams.x
                        rStartY = reticleParams.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val curDm = serviceContext.resources.displayMetrics
                        reticleParams.x = (rStartX + (ev.rawX - rDownX).toInt())
                            .coerceIn(0, (curDm.widthPixels - dp(80)).coerceAtLeast(0))
                        reticleParams.y = (rStartY + (ev.rawY - rDownY).toInt())
                            .coerceIn(0, (curDm.heightPixels - dp(50)).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(rv, reticleParams) }
                        true
                    }
                    else -> false
                }
            }

            reticleView = rv
            reticleParams.x = (initU * screenW - dp(36)).toInt().coerceAtLeast(0)
            reticleParams.y = (initV * screenH - dp(22)).toInt().coerceAtLeast(0)
            rv.text = "[ + $targetLabel ]"
            runCatching { windowManager.addView(rv, reticleParams) }
        } else {
            reticleParams.x = (initU * screenW - dp(36)).toInt().coerceAtLeast(0)
            reticleParams.y = (initV * screenH - dp(22)).toInt().coerceAtLeast(0)
            reticleView?.text = "[ + $targetLabel ]"
            runCatching { windowManager.updateViewLayout(reticleView, reticleParams) }
        }
    }

    private fun hideReticle() {
        val rv = reticleView ?: return
        reticleView = null
        runCatching { windowManager.removeView(rv) }
    }

    fun updateTelemetry(snapshot: RuntimeTelemetrySnapshot) {
        val root = rootLayout ?: return
        root.post {
            // Keep overlay within visible screen bounds after Portrait <-> Landscape rotation
            val dm = serviceContext.resources.displayMetrics
            val maxX = (dm.widthPixels - dp(140)).coerceAtLeast(0)
            val maxY = (dm.heightPixels - dp(80)).coerceAtLeast(0)
            if (layoutParams.x > maxX || layoutParams.y > maxY) {
                layoutParams.x = layoutParams.x.coerceIn(0, maxX)
                layoutParams.y = layoutParams.y.coerceIn(0, maxY)
                runCatching { windowManager.updateViewLayout(root, layoutParams) }
            }

            headlineView?.text = snapshot.statusHeadline
            detailView?.text = snapshot.statusReasoning
            metricsView?.text =
                "Stamina: ${snapshot.staminaPercent}%  ·  SPG: ${snapshot.spgChargePercent}%  ·  Conf: ${snapshot.sceneConfidencePercent}%"

            val isRunning = snapshot.assistantState != AssistantState.STOPPED &&
                snapshot.assistantState != AssistantState.PAUSED

            startPauseButton?.text = when (snapshot.assistantState) {
                AssistantState.STOPPED -> "Start"
                AssistantState.PAUSED -> "Resume"
                else -> "Pause"
            }

            val btnColor = if (isRunning) "#F59E0B" else "#10B981"
            (startPauseButton?.background as? GradientDrawable)?.setColor(Color.parseColor(btnColor))
        }
    }

    fun hide() {
        hideReticle()
        val view = rootLayout ?: return
        rootLayout = null
        runCatching {
            windowManager.removeView(view)
        }
    }
}
