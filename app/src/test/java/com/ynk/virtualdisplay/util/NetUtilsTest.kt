package com.ynk.virtualdisplay.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NetUtilsTest {

    @Test
    fun testResolveConnectHost() {
        assertEquals("127.0.0.1", NetUtils.resolveConnectHost("0.0.0.0"))
        assertEquals("192.168.1.100", NetUtils.resolveConnectHost("192.168.1.100"))
        assertEquals("127.0.0.1", NetUtils.resolveConnectHost("127.0.0.1"))
        assertEquals("localhost", NetUtils.resolveConnectHost("localhost"))
    }
}
