package io.github.pportilla.upmmail.data.eligibility

import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus
import io.github.pportilla.upmmail.data.safety.PlatformRestriction

/** Missing or declined optional sharing never creates an age restriction. */
internal fun restrictionForPlayAccess(
    current: PlatformRestriction,
    accessStatus: Int?,
): PlatformRestriction = when (accessStatus) {
    AgeSignalsStatus.VERIFICATION_REQUIRED ->
        if (current == PlatformRestriction.UNDER_13) current else PlatformRestriction.VERIFICATION_REQUIRED
    else -> current
}

/**
 * The returned ages are used only for this decision, never persisted or sent to our mail servers.
 * Teens receive the same email functionality as adults. A pending/declined significant change
 * is a separate Play approval signal returned only in the jurisdictions where it applies.
 */
internal fun restrictionForSharedPlaySignals(
    current: PlatformRestriction,
    ageLower: Int?,
    ageUpper: Int?,
    significantChangeStatus: Int?,
): PlatformRestriction {
    val validRange = ageLower != null && ageLower in 0..18 &&
        (ageUpper == null || ageUpper in ageLower..18)
    if (validRange && ageUpper != null && ageUpper < 13) return PlatformRestriction.UNDER_13
    when (significantChangeStatus) {
        SignificantChangeStatus.PENDING -> return PlatformRestriction.APPROVAL_PENDING
        SignificantChangeStatus.DECLINED -> return PlatformRestriction.APPROVAL_DECLINED
    }
    val noApprovalHold = significantChangeStatus == null ||
        significantChangeStatus == SignificantChangeStatus.UNSPECIFIED ||
        significantChangeStatus == SignificantChangeStatus.APPROVED
    return if (validRange && ageLower!! >= 13 && noApprovalHold) PlatformRestriction.NONE else current
}
