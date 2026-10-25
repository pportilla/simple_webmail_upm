package io.github.pportilla.upmmail.data.eligibility

import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus
import io.github.pportilla.upmmail.data.safety.PlatformRestriction
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayAgeSignalsDecisionTest {
    @Test fun optionalSharingDeclinedDoesNotBlockOrdinaryMail() {
        assertEquals(PlatformRestriction.NONE,
            restrictionForPlayAccess(PlatformRestriction.NONE, AgeSignalsStatus.NOT_SHARED))
    }

    @Test fun absenceOrDecliningSharingCannotEraseAnExplicitRestriction() {
        for (restriction in PlatformRestriction.entries) {
            for (status in listOf(null, AgeSignalsStatus.UNSPECIFIED, AgeSignalsStatus.NOT_SHARED, 999)) {
                assertEquals(restriction, restrictionForPlayAccess(restriction, status))
            }
        }
    }

    @Test fun mandatoryVerificationOnlyCreatesItsSpecificPlayRestriction() {
        assertEquals(PlatformRestriction.VERIFICATION_REQUIRED,
            restrictionForPlayAccess(PlatformRestriction.NONE, AgeSignalsStatus.VERIFICATION_REQUIRED))
        assertEquals(PlatformRestriction.UNDER_13,
            restrictionForPlayAccess(PlatformRestriction.UNDER_13, AgeSignalsStatus.VERIFICATION_REQUIRED))
    }

    @Test fun sharedAccessAloneDoesNotAuthorizeBeforeAgeResultArrives() {
        assertEquals(PlatformRestriction.VERIFICATION_REQUIRED,
            restrictionForPlayAccess(PlatformRestriction.VERIFICATION_REQUIRED, AgeSignalsStatus.SHARED))
    }

    @Test fun bothTeenBandsHaveTheSameUnrestrictedMailAsAdults() {
        for ((lower, upper) in listOf(13 to 15, 16 to 17, 18 to null)) {
            for (oldRestriction in PlatformRestriction.entries) {
                assertEquals(PlatformRestriction.NONE,
                    restrictionForSharedPlaySignals(oldRestriction, lower, upper, null))
            }
        }
    }

    @Test fun knownUnder13IsNotGrantedAccessByApproval() {
        assertEquals(PlatformRestriction.UNDER_13,
            restrictionForSharedPlaySignals(PlatformRestriction.NONE, 0, 12, SignificantChangeStatus.APPROVED))
    }

    @Test fun regionalApprovalHoldIsHonoredForOtherwiseEligibleTeens() {
        assertEquals(PlatformRestriction.APPROVAL_PENDING,
            restrictionForSharedPlaySignals(PlatformRestriction.NONE, 13, 15, SignificantChangeStatus.PENDING))
        assertEquals(PlatformRestriction.APPROVAL_DECLINED,
            restrictionForSharedPlaySignals(PlatformRestriction.NONE, 16, 17, SignificantChangeStatus.DECLINED))
    }

    @Test fun affirmativeApprovalAndEligibilityRestoreOrdinaryMail() {
        assertEquals(PlatformRestriction.NONE,
            restrictionForSharedPlaySignals(PlatformRestriction.APPROVAL_PENDING, 13, 15, SignificantChangeStatus.APPROVED))
        assertEquals(PlatformRestriction.NONE,
            restrictionForSharedPlaySignals(PlatformRestriction.APPROVAL_DECLINED, 16, 17, SignificantChangeStatus.APPROVED))
    }

    @Test fun emptyMalformedOrFutureSignalsNeverInventEligibility() {
        for (restriction in PlatformRestriction.entries) {
            for ((lower, upper) in listOf(null to null, -1 to 12, 13 to 12, 19 to null, 13 to 999, 0 to 15)) {
                assertEquals(restriction,
                    restrictionForSharedPlaySignals(restriction, lower, upper, null))
            }
            assertEquals(restriction,
                restrictionForSharedPlaySignals(restriction, 13, 15, 999))
        }
    }

    @Test fun approvalDoesNotTurnAnUnknownAgeIntoAnAdult() {
        assertEquals(PlatformRestriction.UNDER_13,
            restrictionForSharedPlaySignals(PlatformRestriction.UNDER_13, null, null, SignificantChangeStatus.APPROVED))
    }

    @Test fun explicitApprovalHoldIsNotLostIfAgeIsTemporarilyMissing() {
        assertEquals(PlatformRestriction.APPROVAL_DECLINED,
            restrictionForSharedPlaySignals(PlatformRestriction.NONE, null, null, SignificantChangeStatus.DECLINED))
    }
}
