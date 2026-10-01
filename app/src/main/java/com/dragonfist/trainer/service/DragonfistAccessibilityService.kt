package com.dragonfist.trainer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.dragonfist.trainer.data.ActionLogItem
import com.dragonfist.trainer.data.DesiredRunMode
import com.dragonfist.trainer.data.TrainerRepository
import com.dragonfist.trainer.engine.AssistantState
import com.dragonfist.trainer.engine.DragonfistStateMachine
import com.dragonfist.trainer.engine.SafeGestureCommand
import com.dragonfist.trainer.overlay.FloatingTrainerOverlay
import com.dragonfist.trainer.safety.SpendGuardInterceptor
import com.dragonfist.trainer.vision.PixelVisionAnalyzer
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class DragonfistAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val visionAnalyzer = PixelVisionAnalyzer()
    private val spendGuard = SpendGuardInterceptor()
    private val stateMachine = DragonfistStateMachine(spendGuard)
    private val screenshotInFlight = AtomicBoolean(false)
    private var overlay: FloatingTrainerOverlay? = null

    @Volatile
    private var lastEventForegroundPackage: String = ""

    @Volatile
    private var lastScreenWidth: Int = 2856

    @Volatile
    private var lastScreenHeight: Int = 1280

    @Volatile
    private var lastScreenshotTimeMs: Long = 0L

    // Android 13-15 enforces a 333ms minimum interval between takeScreenshot() calls
    @Volatile
    private var adaptiveMinScreenshotIntervalMs: Long = 350L

    companion object {
        const val GAME_PACKAGE_PRIMARY = "com.raventhe.DragonfistLimitless"
        const val GAME_PACKAGE_LOWER = "com.raventhe.dragonfistlimitless"

        fun isDragonfistPackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            return pkg.equals(GAME_PACKAGE_PRIMARY, ignoreCase = true) ||
                pkg.equals(GAME_PACKAGE_LOWER, ignoreCase = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        TrainerRepository.initialize(this)

        overlay = FloatingTrainerOverlay(
            serviceContext = this,
            onToggleStartPause = {
                val current = stateMachine.currentState
                if (current == AssistantState.STOPPED) {
                    TrainerRepository.requestStart()
                } else if (current == AssistantState.PAUSED) {
                    TrainerRepository.requestResume()
                } else {
                    TrainerRepository.requestPause()
                }
            },
            onStopRequested = {
                TrainerRepository.requestStop()
            }
        ).also { it.show() }

        TrainerRepository.updateTelemetry {
            it.copy(
                serviceConnected = true,
                statusHeadline = "Accessibility Service Connected",
                statusReasoning = "Open Dragonfist Limitless and tap Start on the floating overlay."
            )
        }

        // Observe explicit Start / Pause / Stop state transitions requested from either UI
        serviceScope.launch {
            TrainerRepository.desiredRunMode.collect { mode ->
                when (mode) {
                    DesiredRunMode.RUNNING -> {
                        spendGuard.clearLockoutIfSafe()
                        if (stateMachine.currentState == AssistantState.PAUSED) {
                            stateMachine.resume()
                        } else if (stateMachine.currentState == AssistantState.STOPPED) {
                            stateMachine.start()
                        }
                    }
                    DesiredRunMode.PAUSED -> {
                        if (stateMachine.currentState != AssistantState.STOPPED) {
                            stateMachine.pause()
                        }
                    }
                    DesiredRunMode.STOPPED -> {
                        stateMachine.stop()
                    }
                }
                syncTelemetryToUi()
            }
        }

        startAdaptiveVisionLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignore events originating from our own floating overlay or transient system UI bars
        if (pkg == packageName || pkg == "com.android.systemui") {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            lastEventForegroundPackage = pkg
        }

        if (spendGuard.isForbiddenBillingPackage(pkg)) {
            spendGuard.triggerImmediateLockout(
                reason = "Billing window ($pkg) detected in foreground."
            )
            stateMachine.forceSafeHold()
            syncTelemetryToUi()
            return
        }

        val rootNode = runCatching { rootInActiveWindow }.getOrNull()
        val windowList = runCatching { windows }.getOrNull()
        if (spendGuard.inspectWindowsAndTree(
                windows = windowList,
                rootNode = rootNode,
                screenWidth = lastScreenWidth,
                screenHeight = lastScreenHeight
            )
        ) {
            stateMachine.forceSafeHold()
            syncTelemetryToUi()
        }
    }

    /**
     * Resolves the current foreground application package by querying interactive application windows
     * first, falling back to rootInActiveWindow and the most recent window state event.
     */
    private fun resolveCurrentForegroundPackage(): String {
        val appWindowPkg = runCatching {
            windows?.firstOrNull { win ->
                win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName?.toString() != packageName
            }?.root?.packageName?.toString()
        }.getOrNull()

        if (!appWindowPkg.isNullOrBlank()) {
            return appWindowPkg
        }

        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!rootPkg.isNullOrBlank() && rootPkg != packageName && rootPkg != "com.android.systemui") {
            return rootPkg
        }

        return lastEventForegroundPackage
    }

    private fun startAdaptiveVisionLoop() {
        serviceScope.launch {
            while (isActive) {
                val currentState = stateMachine.currentState

                if (currentState == AssistantState.STOPPED || currentState == AssistantState.PAUSED) {
                    delay(250L)
                    continue
                }

                val currentForegroundPkg = resolveCurrentForegroundPackage()

                if (spendGuard.isForbiddenBillingPackage(currentForegroundPkg)) {
                    spendGuard.triggerImmediateLockout("Billing package ($currentForegroundPkg) active.")
                    stateMachine.forceSafeHold()
                    syncTelemetryToUi()
                    delay(400L)
                    continue
                }

                // Require explicit confirmation that Dragonfist Limitless is the active foreground package
                if (!isDragonfistPackage(currentForegroundPkg)) {
                    stateMachine.onGameNotInForeground()
                    val displayPkg = currentForegroundPkg.ifBlank { "Home / Launcher" }
                    TrainerRepository.updateTelemetry {
                        it.copy(
                            gameInForeground = false,
                            foregroundPackage = displayPkg,
                            assistantState = stateMachine.currentState,
                            statusHeadline = "Waiting for Dragonfist Limitless",
                            statusReasoning = "Active app is $displayPkg. Open Dragonfist Limitless to resume observation."
                        )
                    }
                    overlay?.updateTelemetry(TrainerRepository.telemetry.value)
                    delay(400L)
                    continue
                }

                val now = System.currentTimeMillis()
                val elapsedSinceLastCapture = now - lastScreenshotTimeMs
                if (elapsedSinceLastCapture < adaptiveMinScreenshotIntervalMs) {
                    delay((adaptiveMinScreenshotIntervalMs - elapsedSinceLastCapture).coerceAtLeast(25L))
                }

                captureAndProcessFrame()

                val cfg = TrainerRepository.config.value
                val desiredInterval = (1000L / cfg.adaptivePollRateHz.coerceIn(1, 3))
                    .coerceAtLeast(adaptiveMinScreenshotIntervalMs)
                delay(desiredInterval)
            }
        }
    }

    private fun captureAndProcessFrame() {
        if (!screenshotInFlight.compareAndSet(false, true)) {
            return
        }
        lastScreenshotTimeMs = System.currentTimeMillis()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            captureExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        screenshot.hardwareBuffer.use { hardwareBuffer ->
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            if (hwBitmap != null) {
                                val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()

                                if (swBitmap != null) {
                                    lastScreenWidth = swBitmap.width
                                    lastScreenHeight = swBitmap.height

                                    val cfg = TrainerRepository.config.value
                                    val cal = TrainerRepository.calibration.value
                                    val frameAnalysis = visionAnalyzer.analyzeFrame(swBitmap)
                                    swBitmap.recycle()

                                    // Slowly relax screenshot interval back toward 350ms after successful captures
                                    adaptiveMinScreenshotIntervalMs =
                                        (adaptiveMinScreenshotIntervalMs - 10L).coerceAtLeast(350L)

                                    val decision = stateMachine.step(frameAnalysis, cfg, cal)

                                    TrainerRepository.updateTelemetry { prev ->
                                        prev.copy(
                                            serviceConnected = true,
                                            gameInForeground = true,
                                            isLandscape = frameAnalysis.isLandscape,
                                            foregroundPackage = GAME_PACKAGE_PRIMARY,
                                            assistantState = decision.state,
                                            recognizedScene = frameAnalysis.recognizedScene.name,
                                            sceneConfidencePercent = (frameAnalysis.sceneConfidence * 100f).toInt(),
                                            staminaPercent = frameAnalysis.staminaPercent,
                                            healthPercent = frameAnalysis.healthPercent,
                                            spgChargePercent = frameAnalysis.spgChargePercent,
                                            captureIntervalMs = adaptiveMinScreenshotIntervalMs,
                                            statusHeadline = decision.headline,
                                            statusReasoning = decision.reasoning,
                                            spendGuardBlockedCount = spendGuard.blockedAttemptCount
                                        )
                                    }
                                    overlay?.updateTelemetry(TrainerRepository.telemetry.value)

                                    decision.gesture?.let { safeCommand ->
                                        val dispatched = dispatchVerifiedGestureCommand(
                                            command = safeCommand,
                                            screenWidth = frameAnalysis.width,
                                            screenHeight = frameAnalysis.height,
                                            safeViewport = frameAnalysis.safeViewportRect,
                                            strictSpendGuard = cfg.strictSpendGuard
                                        )
                                        if (dispatched) {
                                            val firstStroke = safeCommand.strokes.firstOrNull()
                                            val coordStr = if (firstStroke != null) {
                                                "(${String.format("%.2f", firstStroke.startU)}, ${
                                                    String.format("%.2f", firstStroke.startV)
                                                })"
                                            } else {
                                                ""
                                            }
                                            TrainerRepository.appendActionLog(
                                                ActionLogItem(
                                                    timestampMs = System.currentTimeMillis(),
                                                    sceneLabel = frameAnalysis.recognizedScene.name,
                                                    actionDescription = "${safeCommand.summaryType} $coordStr — ${safeCommand.reason}",
                                                    confidencePercent = (frameAnalysis.sceneConfidence * 100f).toInt(),
                                                    verified = decision.lastActionVerified
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        screenshotInFlight.set(false)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        adaptiveMinScreenshotIntervalMs =
                            (adaptiveMinScreenshotIntervalMs + 45L).coerceAtMost(1000L)
                    }
                    screenshotInFlight.set(false)
                }
            }
        )
    }

    private fun dispatchVerifiedGestureCommand(
        command: SafeGestureCommand,
        screenWidth: Int,
        screenHeight: Int,
        safeViewport: RectF,
        strictSpendGuard: Boolean
    ): Boolean {
        if (command.strokes.isEmpty()) return false

        val builder = GestureDescription.Builder()

        for (s in command.strokes) {
            if (!spendGuard.verifyNormalizedCoordinateSafe(s.startU, s.startV, strictSpendGuard) ||
                !spendGuard.verifyNormalizedCoordinateSafe(s.endU, s.endV, strictSpendGuard)
            ) {
                return false
            }

            val startX = (safeViewport.left + s.startU * safeViewport.width())
                .coerceIn(1f, (screenWidth - 1).toFloat())
            val startY = (safeViewport.top + s.startV * safeViewport.height())
                .coerceIn(1f, (screenHeight - 1).toFloat())
            val targetX = (safeViewport.left + s.endU * safeViewport.width())
                .coerceIn(1f, (screenWidth - 1).toFloat())
            val targetY = (safeViewport.top + s.endV * safeViewport.height())
                .coerceIn(1f, (screenHeight - 1).toFloat())

            val path = Path().apply {
                moveTo(startX, startY)
                if (startX != targetX || startY != targetY) {
                    lineTo(targetX, targetY)
                }
            }

            val strokeDesc = GestureDescription.StrokeDescription(
                path,
                s.startTimeOffsetMs.coerceIn(0L, 500L),
                s.durationMs.coerceIn(40L, 650L)
            )
            builder.addStroke(strokeDesc)
        }

        return dispatchGesture(builder.build(), null, null)
    }

    private fun syncTelemetryToUi() {
        TrainerRepository.updateTelemetry {
            it.copy(
                assistantState = stateMachine.currentState,
                spendGuardBlockedCount = spendGuard.blockedAttemptCount
            )
        }
        overlay?.updateTelemetry(TrainerRepository.telemetry.value)
    }

    override fun onInterrupt() {
        stateMachine.stop()
        TrainerRepository.requestStop()
        syncTelemetryToUi()
    }

    override fun onDestroy() {
        overlay?.hide()
        TrainerRepository.updateTelemetry {
            it.copy(
                serviceConnected = false,
                assistantState = AssistantState.STOPPED,
                statusHeadline = "Accessibility Service Disconnected"
            )
        }
        super.onDestroy()
        serviceScope.cancel()
        captureExecutor.shutdown()
    }
}
