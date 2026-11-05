package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.MailCheckIntervals
import io.github.pportilla.upmmail.ui.settings.MailCheck
import io.github.pportilla.upmmail.ui.settings.SettingsOptions
import org.junit.Assert.*
import org.junit.Test

class MailCheckPlannerTest {
    private class Scheduler : MailCheckScheduler {
        var periodic: Int? = null
        var alarm: Int? = null
        var pendingAlarmJob = false
        var alarmInstalls = 0
        var alarmJobEnqueues = 0
        var periodicCancels = 0
        var alarmJobCancels = 0
        override fun periodicMinutes() = periodic
        override fun schedulePeriodic(minutes: Int) { periodic = minutes }
        override fun cancelPeriodic() { periodic = null; periodicCancels++ }
        override fun scheduleAlarm(minutes: Int) { alarm = minutes; alarmInstalls++ }
        override fun cancelAlarm() { alarm = null }
        override fun hasPendingAlarmJob() = pendingAlarmJob
        override fun scheduleAlarmJob() { pendingAlarmJob = true; alarmJobEnqueues++ }
        override fun cancelAlarmJob() { pendingAlarmJob = false; alarmJobCancels++ }
    }

    private fun plan(minutes: Int, push: Boolean = false, accounts: Boolean = true, allowed: Boolean = true) =
        MailCheckPlan.of(AppSettings(syncIntervalMinutes = minutes, pushEnabled = push), accounts, allowed)

    @Test fun `only requested automatic choices and manual mode are offered`() {
        assertEquals(listOf(MailCheck.Push, MailCheck.Every(5), MailCheck.Every(10), MailCheck.Every(15), MailCheck.Every(0)), SettingsOptions.mailChecks)
        for (old in listOf(30, 60, 1, 17, Int.MAX_VALUE)) {
            assertEquals(15, MailCheckIntervals.normalize(old))
            assertEquals(MailCheck.Every(15), MailCheck.of(AppSettings(syncIntervalMinutes = old)))
        }
        assertEquals(0, MailCheckIntervals.normalize(-1))
        for (valid in listOf(0, 5, 10, 15)) assertEquals(valid, MailCheckIntervals.normalize(valid))
    }

    @Test fun `short requests use their actual interval without a fifteen minute clamp`() {
        val scheduler = Scheduler()
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(5)); assertEquals(5, scheduler.alarm); assertNull(scheduler.periodic)
        planner.update(plan(10)); assertEquals(10, scheduler.alarm); assertNull(scheduler.periodic)
        planner.update(plan(15)); assertEquals(15, scheduler.periodic); assertNull(scheduler.alarm)
        planner.update(plan(5, push = true)); assertEquals(15, scheduler.periodic); assertNull(scheduler.alarm)
    }

    @Test fun `retired intervals migrate to a supported periodic check`() {
        val scheduler = Scheduler().apply { periodic = 60 }
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(60))
        assertEquals(15, scheduler.periodic)
        assertNull(scheduler.alarm)
    }

    @Test fun `manual mode no accounts and revoked access remove scheduled checks`() {
        for (disabled in listOf(plan(0), plan(5, accounts = false), plan(5, allowed = false))) {
            val scheduler = Scheduler()
            val planner = MailCheckPlanner(scheduler)
            planner.update(plan(5)); planner.onAlarm()
            assertTrue(scheduler.pendingAlarmJob)
            planner.update(disabled)
            assertNull(scheduler.alarm); assertNull(scheduler.periodic); assertFalse(scheduler.pendingAlarmJob)
            planner.onAlarm()
            assertEquals(1, scheduler.alarmJobEnqueues)
        }
    }

    @Test fun `duplicate delayed alarms coalesce into one pending or running job`() {
        val scheduler = Scheduler()
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(5))
        repeat(5) { planner.onAlarm() }
        assertEquals(1, scheduler.alarmJobEnqueues)
        scheduler.pendingAlarmJob = false
        planner.started(MailCheckJob.ALARM)
        repeat(5) { planner.onAlarm() }
        assertEquals(1, scheduler.alarmJobEnqueues)
        planner.finished(MailCheckJob.ALARM)
        planner.onAlarm()
        assertEquals(2, scheduler.alarmJobEnqueues)
    }

    @Test fun `switching away from periodic mode does not interrupt an active send`() {
        val scheduler = Scheduler()
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(15)); planner.started(MailCheckJob.PERIODIC)
        planner.update(plan(5))
        assertEquals(15, scheduler.periodic)
        assertEquals(0, scheduler.periodicCancels)
        assertFalse(planner.wanted(MailCheckJob.PERIODIC))
        planner.finished(MailCheckJob.PERIODIC)
        assertNull(scheduler.periodic); assertEquals(5, scheduler.alarm)
    }

    @Test fun `active alarm job finishes before manual mode removes it`() {
        val scheduler = Scheduler()
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(5)); planner.onAlarm(); planner.started(MailCheckJob.ALARM)
        val cancels = scheduler.alarmJobCancels
        planner.update(plan(0))
        assertEquals(cancels, scheduler.alarmJobCancels)
        assertFalse(planner.wanted(MailCheckJob.ALARM))
        planner.finished(MailCheckJob.ALARM)
        assertFalse(scheduler.pendingAlarmJob)
        assertTrue(scheduler.alarmJobCancels > cancels)
    }

    @Test fun `process or boot restore reestablishes an alarm without losing queued offline work`() {
        val scheduler = Scheduler().apply { pendingAlarmJob = true }
        val planner = MailCheckPlanner(scheduler)
        planner.update(plan(10)); planner.update(plan(10)); planner.onAlarm()
        assertEquals(10, scheduler.alarm)
        assertEquals(1, scheduler.alarmInstalls)
        assertTrue(scheduler.pendingAlarmJob)
        assertEquals(0, scheduler.alarmJobEnqueues)
    }

    @Test fun `fresh manual process cancels alarm retained from earlier process`() {
        val scheduler = Scheduler().apply { alarm = 5; pendingAlarmJob = true }
        MailCheckPlanner(scheduler).update(plan(0))
        assertNull(scheduler.alarm)
        assertFalse(scheduler.pendingAlarmJob)
    }

    @Test fun `visible polling follows selected cadence and manual has no repeating timer`() {
        for (minutes in listOf(5, 10, 15)) {
            assertEquals(minutes * 60_000L, MailCheckIntervals.foregroundDelayMillis(AppSettings(syncIntervalMinutes = minutes)))
        }
        assertNull(MailCheckIntervals.foregroundDelayMillis(AppSettings(syncIntervalMinutes = 0)))
        assertEquals(120_000L, MailCheckIntervals.foregroundDelayMillis(AppSettings(pushEnabled = true)))
    }
}
