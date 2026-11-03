package com.aistudio.mixedreality.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.aistudio.mixedreality.ui.theme.NeonCyan
import com.aistudio.mixedreality.ui.theme.NeonGreen
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ARTracking"
private const val RECOVERY_GRACE_NANOS = 1_000_000_000L

/** A screen-space tap waiting for a valid current camera frame. */
data class PendingPlacementTap(
    val offset: Offset,
    val creationTime: Long = System.currentTimeMillis()
)

/** Owns AR anchors and resolves user placement taps against tracked horizontal planes. */
class ARTrackingManager(
    val modeName: String,
    val onHapticFeedback: () -> Unit = {}
) {
    var activeAnchor by mutableStateOf<Anchor?>(null)
        private set

    var anchorTrackingState by mutableStateOf<TrackingState?>(null)
        private set

    var isPlaneDetected by mutableStateOf(false)
        private set

    private val pendingPlacementRef = AtomicReference<PendingPlacementTap?>(null)
    private val recoveryGate = AnchorRecoveryGate()
    private var lastCameraTrackingState: TrackingState? = null

    // Only an anchor pose read while both anchor and camera track can become this baseline.
    private var lastVerifiedWorldPose: Pose? = null
    private var pendingPlacementAnchor: Anchor? = null
    private var recoveryCandidate: Anchor? = null
    private var trackingLostSinceNanos: Long? = null

    fun onSingleTap(tapOffset: Offset, isModelVisible: Boolean) {
        if (!isModelVisible) {
            Log.d(TAG, "[$modeName] Tap ignored: model is not visible")
            return
        }
        pendingPlacementRef.set(PendingPlacementTap(offset = tapOffset))
        Log.d(TAG, "[$modeName] Placement tap queued at ($tapOffset)")
    }

    fun onAnchorTrackingStateChanged(newState: TrackingState) {
        if (anchorTrackingState != newState) {
            Log.i(TAG, "[$modeName] Anchor tracking state changed: $anchorTrackingState -> $newState")
            anchorTrackingState = newState
        }
    }

    fun onSessionUpdated(
        session: Session,
        frame: Frame,
        isModelVisible: Boolean,
        splitWidthPx: Float = 0f,
        onPlaced: () -> Unit = {}
    ) {
        val cameraState = runCatching { frame.camera.trackingState }.getOrNull()
        if (lastCameraTrackingState != cameraState) {
            Log.i(TAG, "[$modeName] Camera tracking state changed: $lastCameraTrackingState -> $cameraState")
            lastCameraTrackingState = cameraState
        }

        val pending = pendingPlacementRef.get()
        if (pending != null) {
            when (placementTapDecision(
                isModelVisible = isModelVisible,
                cameraIsTracking = cameraState == TrackingState.TRACKING,
                elapsedMs = (System.currentTimeMillis() - pending.creationTime).coerceAtLeast(0L)
            )) {
                PlacementTapDecision.DISCARD -> {
                    pendingPlacementRef.compareAndSet(pending, null)
                    Log.d(TAG, "[$modeName] Discarded expired, hidden-model, or untracked placement tap")
                }
                PlacementTapDecision.HIT_TEST_ON_CURRENT_FRAME -> if (pendingPlacementRef.compareAndSet(pending, null)) {
                    val hitX = if (splitWidthPx > 0f && pending.offset.x > splitWidthPx) {
                        pending.offset.x - splitWidthPx
                    } else {
                        pending.offset.x
                    }
                    val floorHit = runCatching { frame.hitTest(hitX, pending.offset.y) }
                        .onFailure { Log.w(TAG, "[$modeName] Hit test failed", it) }
                        .getOrNull()
                        .orEmpty()
                        .firstOrNull { hit ->
                            val plane = hit.trackable as? Plane
                            plane != null && plane.trackingState == TrackingState.TRACKING &&
                                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                plane.subsumedBy == null && plane.isPoseInPolygon(hit.hitPose)
                        }
                    val candidate = floorHit?.let { hit ->
                        runCatching { hit.createAnchor() }
                            .onFailure { Log.w(TAG, "[$modeName] Could not create anchor from valid floor hit", it) }
                            .getOrNull()
                    }
                    when {
                        candidate == null -> Log.w(TAG, "[$modeName] No tracked horizontal plane hit inside its polygon")
                        runCatching { candidate.trackingState }.getOrNull() == TrackingState.STOPPED -> {
                            runCatching { candidate.detach() }
                            Log.w(TAG, "[$modeName] Placement rejected: new anchor is STOPPED")
                        }
                        else -> {
                            val pose = verifiedAnchorPose(candidate, cameraState)
                            if (pose != null) {
                                promotePlacementAnchor(candidate, pose, onPlaced)
                                onHapticFeedback()
                            } else {
                                // Retain one genuine hit anchor while ARCore's asynchronous pose
                                // update is PAUSED. Keep the existing active placement untouched.
                                runCatching { pendingPlacementAnchor?.detach() }
                                pendingPlacementAnchor = candidate
                                Log.i(TAG, "[$modeName] Valid floor-hit anchor is PAUSED; awaiting tracking")
                            }
                        }
                    }
                }
            }
        }

        pendingPlacementAnchor?.let { candidate ->
            val pose = verifiedAnchorPose(candidate, cameraState)
            when {
                pose != null -> {
                    promotePlacementAnchor(candidate, pose, onPlaced)
                    onHapticFeedback()
                }
                runCatching { candidate.trackingState }.getOrNull() == TrackingState.STOPPED -> {
                    runCatching { candidate.detach() }
                    pendingPlacementAnchor = null
                    Log.w(TAG, "[$modeName] Delayed placement anchor stopped before tracking; discarded")
                }
            }
        }

        val anchor = activeAnchor
        if (anchor != null) {
            val state = runCatching { anchor.trackingState }.getOrNull()
            if (state != null && anchorTrackingState != state) {
                anchorTrackingState = state
                Log.i(TAG, "[$modeName] Active anchor tracking state: $state")
            }

            val verifiedPose = verifiedAnchorPose(anchor, cameraState)
            if (verifiedPose != null) {
                lastVerifiedWorldPose = verifiedPose
                trackingLostSinceNanos = null
                // Natural relocalization wins; discard any unused replacement.
                runCatching { recoveryCandidate?.detach() }
                recoveryCandidate = null
                recoveryGate.reset()
            } else if (shouldAttemptAnchorRecovery(
                    cameraState = cameraState,
                    anchorState = state,
                    lostDurationNanos = trackingLossDurationNanos(cameraState)
                )
            ) {
                val originalPose = lastVerifiedWorldPose
                val candidate = recoveryCandidate
                if (candidate != null) {
                    val candidateState = runCatching { candidate.trackingState }.getOrNull()
                    val candidatePose = verifiedAnchorPose(candidate, cameraState)
                    when {
                        isRecoveryCandidateAcceptable(cameraState, candidateState, originalPose, candidatePose) -> {
                            val safeCandidatePose = candidatePose ?: return
                            // Acceptance is based on the saved verified pose, not a fresh/stale
                            // replacement pose. Preserve that exact baseline across recovery.
                            if (isRecoveryTranslationContinuous(
                                    originalPose?.tx() ?: Float.NaN,
                                    originalPose?.ty() ?: Float.NaN,
                                    originalPose?.tz() ?: Float.NaN,
                                    safeCandidatePose.tx(),
                                    safeCandidatePose.ty(),
                                    safeCandidatePose.tz()
                                )
                            ) {
                                activeAnchor = candidate
                                anchorTrackingState = TrackingState.TRACKING
                                lastVerifiedWorldPose = originalPose
                                recoveryCandidate = null
                                trackingLostSinceNanos = null
                                recoveryGate.reset()
                                runCatching { anchor.detach() }
                                Log.i(TAG, "[$modeName] Recovered continuously at the verified world position")
                            } else {
                                runCatching { candidate.detach() }
                                recoveryCandidate = null
                                Log.w(TAG, "[$modeName] Recovery candidate would jump >5 cm; kept active anchor and saved pose")
                            }
                        }
                        candidateState == TrackingState.STOPPED || candidateState == null -> {
                            runCatching { candidate.detach() }
                            recoveryCandidate = null
                            // Keep the gate timestamp: a failed candidate cannot trigger per-frame
                            // anchor creation, but may be retried after the bounded backoff.
                            Log.w(TAG, "[$modeName] Recovery candidate stopped; bounded retry remains enabled")
                        }
                        // A PAUSED candidate is kept and polled; no duplicate is created.
                    }
                } else if (originalPose != null) {
                    val targetPlane = runCatching {
                        session.getAllTrackables(Plane::class.java).firstOrNull { plane ->
                            plane.trackingState == TrackingState.TRACKING &&
                                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                plane.subsumedBy == null && plane.isPoseInPolygon(originalPose)
                        }
                    }.onFailure {
                        Log.w(TAG, "[$modeName] Could not inspect tracked planes for recovery", it)
                    }.getOrNull()

                    // Never consume an attempt without the exact saved pose inside a real plane.
                    if (targetPlane != null && recoveryGate.shouldAttempt()) {
                        recoveryCandidate = runCatching { targetPlane.createAnchor(originalPose) }
                            .onFailure { Log.w(TAG, "[$modeName] Recovery anchor creation failed", it) }
                            .getOrNull()
                        if (recoveryCandidate == null) {
                            Log.w(TAG, "[$modeName] Recovery failed; retry is rate-limited")
                        }
                    }
                } else {
                    Log.w(TAG, "[$modeName] No verified world pose; refusing arbitrary recovery")
                }
            } else if (cameraState != TrackingState.TRACKING) {
                // Tracking interruptions reset the grace timer, not the verified pose.
                trackingLostSinceNanos = null
            }
        }

        val hasTrackedHorizontalSurface = cameraState == TrackingState.TRACKING && runCatching {
            session.getAllTrackables(Plane::class.java).any { plane ->
                plane.trackingState == TrackingState.TRACKING &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.subsumedBy == null
            }
        }.getOrDefault(false)
        if (hasTrackedHorizontalSurface != isPlaneDetected) {
            isPlaneDetected = hasTrackedHorizontalSurface
            Log.d(TAG, "[$modeName] Tracked upward-facing ARCore horizontal surface detected: $hasTrackedHorizontalSurface")
        }
    }

    private fun trackingLossDurationNanos(cameraState: TrackingState?): Long {
        if (cameraState != TrackingState.TRACKING) {
            trackingLostSinceNanos = null
            return 0L
        }
        val now = System.nanoTime()
        val since = trackingLostSinceNanos ?: now.also { trackingLostSinceNanos = it }
        return (now - since).coerceAtLeast(0L)
    }

    private fun verifiedAnchorPose(anchor: Anchor, cameraState: TrackingState?): Pose? {
        val before = runCatching { anchor.trackingState }.getOrNull()
        if (!isVerifiedTrackingPose(cameraState, before)) return null
        val pose = runCatching { anchor.pose }
            .onFailure { Log.w(TAG, "[$modeName] Could not read anchor pose", it) }
            .getOrNull() ?: return null
        val after = runCatching { anchor.trackingState }.getOrNull()
        return pose.takeIf { isVerifiedTrackingPose(cameraState, after) }
    }

    private fun promotePlacementAnchor(candidate: Anchor, verifiedPose: Pose, onPlaced: () -> Unit) {
        val previousAnchor = activeAnchor
        activeAnchor = candidate
        anchorTrackingState = TrackingState.TRACKING
        lastVerifiedWorldPose = verifiedPose
        pendingPlacementAnchor = null
        runCatching { recoveryCandidate?.detach() }
        recoveryCandidate = null
        trackingLostSinceNanos = null
        recoveryGate.reset()
        runCatching { previousAnchor?.takeIf { it !== candidate }?.detach() }
        Log.i(TAG, "[$modeName] Committed placement from a verified tracking-anchor pose")
        onPlaced()
    }

    fun clear() {
        Log.d(TAG, "[$modeName] Clearing anchor and tracking state")
        runCatching { activeAnchor?.detach() }
        runCatching { pendingPlacementAnchor?.detach() }
        runCatching { recoveryCandidate?.detach() }
        activeAnchor = null
        pendingPlacementAnchor = null
        recoveryCandidate = null
        anchorTrackingState = null
        lastVerifiedWorldPose = null
        trackingLostSinceNanos = null
        pendingPlacementRef.set(null)
        recoveryGate.reset()
    }

    fun dispose() = clear()

    fun getHudStatus(): Pair<String, Color> = when {
        activeAnchor != null && anchorTrackingState == TrackingState.TRACKING ->
            "النموذج مثبت • انقر على أرضية أخرى لنقله" to NeonGreen
        activeAnchor != null && anchorTrackingState == TrackingState.PAUSED ->
            "التتبع متوقف مؤقتاً • حرّك الجهاز ببطء" to Color(0xFFFFB300)
        activeAnchor != null && anchorTrackingState == TrackingState.STOPPED ->
            "فُقد التتبع • امسح الأرضية ثم انقر لإعادة التثبيت" to Color(0xFFFF5252)
        activeAnchor != null -> "النموذج مثبت • انقر على أرضية أخرى لنقله" to NeonGreen
        isPlaneDetected -> "تم رصد سطح أفقي متتبّع • انقر لتثبيت النموذج" to NeonGreen
        else -> "وجّه الكاميرا نحو الأرضية وحرّكها ببطء" to NeonCyan
    }
}

@Composable
fun rememberARTrackingManager(
    modeName: String,
    onHapticFeedback: () -> Unit = {}
): ARTrackingManager = remember(modeName) {
    ARTrackingManager(modeName = modeName, onHapticFeedback = onHapticFeedback)
}
