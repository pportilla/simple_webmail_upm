package io.github.pportilla.upmmail.data.safety

/** Side effects of an access transition; local reports and completed checks are not revocations. */
internal data class SafetyTransitionEffects(
    val clearNotifications: Boolean,
    val closeConnections: Boolean,
    val refreshForeground: Boolean,
)

internal fun safetyTransitionEffects(previous: SafetyState?, current: SafetyState): SafetyTransitionEffects {
    val newlyBlockedSender = (current.blockedSenders - previous?.blockedSenders.orEmpty()).isNotEmpty()
    val accessRevoked = !current.mailboxAccessAllowed && (previous == null || previous.mailboxAccessAllowed)
    val checkStarted = current.mailboxAccessAllowed && current.platformCheckPending && previous?.platformCheckPending != true
    val accessResumed = previous != null && current.mailboxAccessAllowed && !current.platformCheckPending &&
        (!previous.mailboxAccessAllowed || previous.platformCheckPending)
    return SafetyTransitionEffects(
        clearNotifications = accessRevoked || newlyBlockedSender,
        closeConnections = accessRevoked || newlyBlockedSender || checkStarted,
        refreshForeground = accessResumed,
    )
}
