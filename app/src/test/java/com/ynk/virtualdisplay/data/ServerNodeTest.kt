package com.ynk.virtualdisplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerNodeTest {

    @Test
    fun `uniqueKey replaces dots in host`() {
        val node = ServerNode("n", "192.168.1.100", 27183)
        assertEquals("192_168_1_100_27183", node.uniqueKey())
    }

    @Test
    fun `serialization round trip preserves all fields`() {
        val node = ServerNode("电视", "10.0.0.2", 5000, "secret")
        assertEquals(node, ServerNode.fromSerializedString(node.toSerializedString()))
    }

    @Test
    fun `deserialization of too few fields returns null`() {
        assertNull(ServerNode.fromSerializedString("only|two"))
    }

    @Test
    fun `deserialization without password defaults to empty`() {
        val node = ServerNode.fromSerializedString("n|host|1234")
        assertEquals("", node?.password)
    }

    @Test
    fun `deserialization with invalid port falls back to default`() {
        val node = ServerNode.fromSerializedString("n|host|not-a-port")
        assertEquals(27183, node?.port)
    }
}
