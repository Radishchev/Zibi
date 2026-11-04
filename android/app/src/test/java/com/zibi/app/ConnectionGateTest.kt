package com.zibi.app

import com.zibi.app.net.ConnState
import com.zibi.app.net.shouldOpenConnection
import com.zibi.app.net.shouldStartConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGateTest {
    @Test
    fun onlyCurrentRequestedConnectionMayOpen() {
        assertTrue(shouldOpenConnection(true, 4, 4))
        assertFalse(shouldOpenConnection(false, 4, 4))
        assertFalse(shouldOpenConnection(true, 4, 5))
    }

    @Test
    fun aPendingConnectionCountsAsAlreadyStarted() {
        assertFalse(shouldStartConnection(true, ConnState.Connecting))
        assertFalse(shouldStartConnection(true, ConnState.Disconnected))
        assertFalse(shouldStartConnection(true, ConnState.Failed("offline")))
        assertTrue(shouldStartConnection(false, ConnState.Disconnected))
    }
}
