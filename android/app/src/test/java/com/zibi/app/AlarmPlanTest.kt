package com.zibi.app

import com.zibi.app.tools.AlarmPlan
import com.zibi.app.tools.MAX_ALARMS
import com.zibi.app.tools.planAlarms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private fun ok(plan: AlarmPlan): List<String> = (plan as AlarmPlan.Times).times.map { it.toString() }
private fun err(plan: AlarmPlan): String = (plan as AlarmPlan.Invalid).message

class AlarmPlanTest {
    @Test
    fun theExactRequestThatWasRefusedBefore() {
        // "alarms at 1 minute distance starting from 5:45 all the way to 6:20" = 36 alarms. There is no cap of 20.
        val times = ok(planAlarms(null, "05:45", "06:20", 1, null, false))
        assertEquals(36, times.size)
        assertEquals("05:45", times.first())
        assertEquals("06:20", times.last())
    }

    @Test
    fun countEndingAtTheWakeTime() {
        assertEquals(
            listOf("06:22", "06:24", "06:26", "06:28", "06:30"),
            ok(planAlarms(null, null, "06:30", 2, 5, false)),
        )
    }

    @Test
    fun explicitTimesAreUsedAsGiven() {
        assertEquals(listOf("05:50", "06:07", "06:30"), ok(planAlarms(listOf("5:50", "06:07", "06:30", "6:07"), null, null, null, null, false)))
        assertTrue(err(planAlarms(listOf("05:50", "soon"), null, null, null, null, false)).contains("soon"))
    }

    @Test
    fun rangesCanCrossMidnight() {
        assertEquals(
            listOf("23:50", "23:55", "00:00", "00:05", "00:10"),
            ok(planAlarms(null, "23:50", "00:10", 5, null, false)),
        )
        assertEquals(listOf("23:50", "23:55", "00:00", "00:05"), ok(planAlarms(null, null, "00:05", 5, 4, false)))
    }

    @Test
    fun randomSpacingGivesDistinctSortedTimesInsideTheRange() {
        val times = ok(planAlarms(null, "05:45", "06:20", null, 20, true, Random(1)))
        assertEquals(20, times.size)
        assertEquals(times.sorted(), times)
        assertEquals(20, times.toSet().size)
        assertTrue(times.all { it >= "05:45" && it <= "06:20" })
        // and it is genuinely not evenly spaced
        assertTrue(times.zipWithNext().map { (a, b) -> b.substring(3).toInt() - a.substring(3).toInt() }.toSet().size > 1)
    }

    @Test
    fun randomCannotAskForMoreAlarmsThanThereAreMinutes() {
        assertTrue(err(planAlarms(null, "06:00", "06:04", null, 9, true)).contains("only 5"))
    }

    @Test
    fun onlyTheSanityLimitApplies() {
        assertEquals(MAX_ALARMS, ok(planAlarms(null, "05:00", "06:39", 1, null, false)).size)
        assertTrue(err(planAlarms(null, "05:00", "07:00", 1, null, false)).contains("100"))
        assertTrue(err(planAlarms(null, null, "08:00", 1, 500, false)).contains("100"))
    }

    @Test
    fun badInputGetsAHelpfulMessage() {
        assertTrue(err(planAlarms(null, "05:00", "06:00", 0, null, false)).contains("at least 1"))
        assertTrue(err(planAlarms(null, null, null, null, null, false)).contains("list of times"))
        assertTrue(err(planAlarms(null, "5am", "06:00", 5, null, false)).contains("from_time"))
    }
}
