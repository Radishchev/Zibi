package com.zibi.app

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionBrokerTest {
    @Test
    fun concurrentPermissionRequestsAreServedInOrder() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val granted = mutableSetOf<String>()
            val launches = AtomicInteger(0)
            val broker = PermissionBroker(
                null,
                permissionChecker = { granted.contains(it) },
            )
            broker.attach(
                owner = "main-activity",
                launch = {
                    launches.incrementAndGet()
                    granted += it
                    broker.onResult(it.associateWith { true })
                },
                launchExactAlarmSettings = {},
            )

            val first = async { broker.ensure("android.permission.POST_NOTIFICATIONS") }
            val second = async { broker.ensure("android.permission.POST_NOTIFICATIONS") }

            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(1, launches.get())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detachingActivityCancelsPendingPermissionRequest() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val broker = PermissionBroker(null, permissionChecker = { false })
            broker.attach(
                owner = "main-activity",
                launch = {},
                launchExactAlarmSettings = {},
            )
            val request = async { broker.ensure("android.permission.POST_NOTIFICATIONS") }

            broker.detach(owner = "main-activity")

            assertFalse(request.await())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleOwnerCannotDetachCurrentLauncher() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val broker = PermissionBroker(null, permissionChecker = { false })
            broker.attach(
                owner = "stale-activity",
                launch = { fail("stale launcher should not be called") },
                launchExactAlarmSettings = {},
            )
            broker.attach(
                owner = "current-activity",
                launch = { },
                launchExactAlarmSettings = {},
            )

            broker.detach(owner = "stale-activity")

            val result = broker.ensure("android.permission.POST_NOTIFICATIONS")
            assertFalse(result)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun currentOwnerDetachCancelsPendingRequest() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val broker = PermissionBroker(null, permissionChecker = { false })
            broker.attach(
                owner = "current-activity",
                launch = {},
                launchExactAlarmSettings = {},
            )
            val request = async { broker.ensure("android.permission.POST_NOTIFICATIONS") }

            broker.detach(owner = "current-activity")

            assertFalse(request.await())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
