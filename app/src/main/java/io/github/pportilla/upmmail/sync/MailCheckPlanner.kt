package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.MailCheckIntervals

/** Android's periodic jobs have a 15-minute floor; shorter choices use inexact alarms. */
internal data class MailCheckPlan(val periodicMinutes: Int? = null, val alarmMinutes: Int? = null) {
    companion object {
        fun of(settings: AppSettings, hasAccounts: Boolean, authorized: Boolean): MailCheckPlan {
            if (!hasAccounts || !authorized) return MailCheckPlan()
            return when (val minutes = MailCheckIntervals.backgroundMinutes(settings)) {
                5, 10 -> MailCheckPlan(alarmMinutes = minutes)
                15 -> MailCheckPlan(periodicMinutes = minutes)
                else -> MailCheckPlan()
            }
        }
    }
}

internal enum class MailCheckJob { PERIODIC, ALARM }

/** Only mail-check jobs are exposed: the planner cannot cancel an outbox or mutation job. */
internal interface MailCheckScheduler {
    fun periodicMinutes(): Int?
    fun schedulePeriodic(minutes: Int)
    fun cancelPeriodic()
    fun scheduleAlarm(minutes: Int)
    fun cancelAlarm()
    fun hasPendingAlarmJob(): Boolean
    fun scheduleAlarmJob()
    fun cancelAlarmJob()
}

/**
 * Coalesces alarm deliveries and reconciles changed settings after active work completes.
 * A check can include an in-flight send, so changing modes never cancels a running check.
 * The Android adapter serializes calls; keeping this coordinator pure makes lifecycle races
 * (settings, boot, alarm delivery and job completion) testable without a device clock.
 */
internal class MailCheckPlanner(private val scheduler: MailCheckScheduler) {
    private var plan = MailCheckPlan()
    private var alarmMinutes: Int? = null
    private val running = mutableSetOf<MailCheckJob>()

    fun update(next: MailCheckPlan) {
        plan = next
        reconcile()
    }

    fun onAlarm() {
        if (plan.alarmMinutes != null && MailCheckJob.ALARM !in running && !scheduler.hasPendingAlarmJob()) {
            scheduler.scheduleAlarmJob()
        }
    }

    fun started(job: MailCheckJob) { running += job }

    fun finished(job: MailCheckJob) {
        running -= job
        reconcile()
    }

    fun wanted(job: MailCheckJob): Boolean = when (job) {
        MailCheckJob.PERIODIC -> plan.periodicMinutes != null
        MailCheckJob.ALARM -> plan.alarmMinutes != null
    }

    private fun reconcile() {
        if (alarmMinutes != plan.alarmMinutes) {
            if (plan.alarmMinutes == null) scheduler.cancelAlarm() else scheduler.scheduleAlarm(plan.alarmMinutes!!)
            alarmMinutes = plan.alarmMinutes
        } else if (plan.alarmMinutes == null) {
            // Alarms survive process death. A fresh manual/15-minute process must cancel an
            // alarm left by the previous process, even though its in-memory state is empty.
            scheduler.cancelAlarm()
        }
        if (MailCheckJob.PERIODIC !in running) {
            val minutes = plan.periodicMinutes
            if (minutes == null) scheduler.cancelPeriodic()
            else if (scheduler.periodicMinutes() != minutes) scheduler.schedulePeriodic(minutes)
        }
        if (plan.alarmMinutes == null && MailCheckJob.ALARM !in running) scheduler.cancelAlarmJob()
    }
}
