package com.dragonfist.trainer.safety

import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multi-layer hardware & semantic Spend Guard firewall preventing any automated interaction
 * with real-money billing sheets, Dragon Token shops, cosmetic vendors, or currency confirmation dialogs.
 */
class SpendGuardInterceptor {

    @Volatile
    var isLockedOut: Boolean = false
        private set

    @Volatile
    var lockoutReason: String? = null
        private set

    @Volatile
    var blockedAttemptCount: Int = 0
        private set

    // Layer 1: Permanent Normalized UV Exclusion Masks
    // Protects the top-right HUD currency & shop icon cluster and top-left menu/settings store triggers
    private val permanentExclusionZones = listOf(
        RectF(0.68f, 0.0f, 1.0f, 0.17f),
        RectF(0.0f, 0.0f, 0.05f, 0.12f)
    )

    // Dynamic exclusion zones populated from AccessibilityNodeInfo bounds containing currency/buy labels
    private val dynamicNodeExclusionZones = CopyOnWriteArrayList<RectF>()

    // Layer 2: Forbidden Foreground & Overlay Packages
    private val billingPackages = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.apps.walletnfcrel",
        "com.sec.android.app.billing"
    )

    // Layer 3: Structural & Semantic Purchase Signatures
    private val fullLockoutPatterns = listOf(
        Regex("""[$€£¥₩]\s*\d+([.,]\d{1,2})?"""),
        Regex(
            """\b(1-tap\s*buy|google\s*play\s*balance|payment\s*method|confirm\s*purchase|in-app\s*purchase|buy\s*dragon\s*tokens?|token\s*shop|cosmetic\s*shop|aura\s*shop)\b""",
            RegexOption.IGNORE_CASE
        )
    )

    private val buttonExclusionPatterns = listOf(
        Regex(
            """\b(buy|purchase|spend|unlock\s*for|dragon\s*tokens?|dt\b|real\s*money|shop|store|gem|premium)\b""",
            RegexOption.IGNORE_CASE
        )
    )

    fun isForbiddenBillingPackage(packageName: String): Boolean {
        return billingPackages.any { it.equals(packageName, ignoreCase = true) }
    }

    fun triggerImmediateLockout(reason: String) {
        if (!isLockedOut) {
            blockedAttemptCount++
        }
        isLockedOut = true
        lockoutReason = reason
    }

    fun clearLockoutIfSafe() {
        isLockedOut = false
        lockoutReason = null
        dynamicNodeExclusionZones.clear()
    }

    /**
     * Inspects all interactive AccessibilityWindowInfo layers and the active AccessibilityNodeInfo tree.
     * Returns true if a monetization screen or billing overlay is present.
     */
    fun inspectWindowsAndTree(
        windows: List<AccessibilityWindowInfo>?,
        rootNode: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (windows != null) {
            for (window in windows) {
                val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull() ?: ""
                if (isForbiddenBillingPackage(pkg)) {
                    triggerImmediateLockout("Billing overlay window active ($pkg)")
                    return true
                }
            }
        }

        val discoveredZones = mutableListOf<RectF>()
        val locked = runCatching {
            scanNodeRecursive(
                node = rootNode,
                screenWidth = screenWidth.coerceAtLeast(1),
                screenHeight = screenHeight.coerceAtLeast(1),
                discoveredZones = discoveredZones,
                depth = 0
            )
        }.getOrDefault(false)

        dynamicNodeExclusionZones.clear()
        dynamicNodeExclusionZones.addAll(discoveredZones)

        return locked
    }

    private fun scanNodeRecursive(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        discoveredZones: MutableList<RectF>,
        depth: Int
    ): Boolean {
        if (node == null || depth > 28) return false

        val text = runCatching {
            buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it).append(' ') }
                node.viewIdResourceName?.let { append(it) }
            }.trim()
        }.getOrDefault("")

        if (text.isNotEmpty()) {
            for (pattern in fullLockoutPatterns) {
                if (pattern.containsMatchIn(text)) {
                    triggerImmediateLockout("Blocked monetization screen signature: \"${text.take(36)}\"")
                    return true
                }
            }

            for (btnPattern in buttonExclusionPatterns) {
                if (btnPattern.containsMatchIn(text)) {
                    val rect = Rect()
                    runCatching { node.getBoundsInScreen(rect) }
                    if (!rect.isEmpty) {
                        discoveredZones.add(
                            RectF(
                                (rect.left.toFloat() / screenWidth).coerceIn(0f, 1f),
                                (rect.top.toFloat() / screenHeight).coerceIn(0f, 1f),
                                (rect.right.toFloat() / screenWidth).coerceIn(0f, 1f),
                                (rect.bottom.toFloat() / screenHeight).coerceIn(0f, 1f)
                            )
                        )
                    }
                }
            }
        }

        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (scanNodeRecursive(child, screenWidth, screenHeight, discoveredZones, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Verifies that a normalized UV coordinate (and its surrounding safety margin)
     * does not intersect any permanent or dynamic Spend Guard exclusion zone.
     */
    fun verifyNormalizedCoordinateSafe(u: Float, v: Float, strictMode: Boolean = true): Boolean {
        if (isLockedOut) {
            blockedAttemptCount++
            return false
        }

        val margin = if (strictMode) 0.02f else 0.01f

        for (zone in permanentExclusionZones) {
            if (u >= zone.left - margin &&
                u <= zone.right + margin &&
                v >= zone.top - margin &&
                v <= zone.bottom + margin
            ) {
                blockedAttemptCount++
                return false
            }
        }

        for (dynZone in dynamicNodeExclusionZones) {
            if (u >= dynZone.left - margin &&
                u <= dynZone.right + margin &&
                v >= dynZone.top - margin &&
                v <= dynZone.bottom + margin
            ) {
                blockedAttemptCount++
                return false
            }
        }

        return true
    }
}
