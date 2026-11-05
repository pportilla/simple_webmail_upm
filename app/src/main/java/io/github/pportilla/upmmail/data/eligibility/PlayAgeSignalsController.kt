package io.github.pportilla.upmmail.data.eligibility

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import io.github.pportilla.upmmail.data.safety.PlatformRestriction
import io.github.pportilla.upmmail.data.safety.SafetyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Uses Play's documented access-then-signals flow. Mandatory verification is handled by Play;
 * declining optional age sharing leaves ordinary 13+ setup available. No local guardian flow,
 * birth-date collection, installation identifier storage, or age analytics is added.
 *
 * Keep one instance for the application. refresh() is called from foreground/resume events;
 * the in-flight guard and short cooldown prevent a Play consent activity from creating a
 * resume/prompt loop. An explicit user retry can bypass the cooldown, never an active request.
 */
class PlayAgeSignalsController(
    context: Context,
    private val safety: SafetyRepository,
) {
    private val applicationContext = context.applicationContext
    private val manager by lazy(LazyThreadSafetyMode.NONE) {
        AgeSignalsManagerFactory.create(applicationContext)
    }
    private val mutableChecking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = mutableChecking.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val requests = PlayAgeRequestWindow()

    /** Returns false when another request is active, the activity is closing, or refresh is recent. */
    @MainThread
    fun refresh(
        activity: Activity,
        force: Boolean = false,
        onComplete: (PlatformRestriction) -> Unit = {},
    ): Boolean {
        if (mutableChecking.value || activity.isFinishing || activity.isDestroyed) return false
        val requestGeneration = requests.begin(SystemClock.elapsedRealtime(), force) ?: return false
        safety.setPlatformCheckPending(true)
        mutableChecking.value = true
        fun current() = requests.isCurrent(requestGeneration)
        var timeout: Runnable? = null

        fun finish(restriction: PlatformRestriction = safety.state.value.platformRestriction) {
            if (!current()) return
            val firstCompletion = requests.finish(requestGeneration, SystemClock.elapsedRealtime())
            timeout?.let(handler::removeCallbacks)
            try {
                if (restriction != PlatformRestriction.NONE || restriction != safety.state.value.platformRestriction) {
                    safety.updatePlatformRestriction(restriction)
                }
            } catch (_: Exception) {
                // The repository retains restrictions on persistence failure. Never substitute
                // a successful/empty signal for a storage error or expose raw SDK results.
            } finally {
                if (firstCompletion) {
                    safety.setPlatformCheckPending(false)
                    mutableChecking.value = false
                }
            }
            if (firstCompletion) onComplete(safety.state.value.platformRestriction)
        }

        val timeoutTask = Runnable { finish() }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, REQUEST_TIMEOUT_MS)

        try {
            manager.requestAgeSignalsAccess(
                AgeSignalsAccessRequest.builder().setActivity(activity).build(),
            ).addOnSuccessListener accessResult@{ access ->
                if (!current()) return@accessResult
                if (access.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                    try {
                        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
                            .addOnSuccessListener signalsResult@{ signals ->
                                if (!current()) return@signalsResult
                                finish(restrictionForSharedPlaySignals(
                                    current = safety.state.value.platformRestriction,
                                    ageLower = signals.ageLower(),
                                    ageUpper = signals.ageUpper(),
                                    significantChangeStatus = signals.significantChangeStatus(),
                                ))
                            }
                            .addOnFailureListener { finish() }
                            .addOnCanceledListener { finish() }
                    } catch (_: Exception) {
                        finish()
                    }
                } else {
                    finish(restrictionForPlayAccess(safety.state.value.platformRestriction, access.ageSignalsStatus()))
                }
            }.addOnFailureListener { finish() }
                .addOnCanceledListener { finish() }
        } catch (_: Exception) {
            finish()
        }
        return true
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
