package com.ynk.virtualdisplay.rpc

import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class DaemonRpcTest {

    private fun serializeGenericResponse(sequence: Long, statusCode: Int, displayId: Int, message: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(100)
        dos.writeLong(sequence)
        dos.writeInt(statusCode)
        dos.writeInt(displayId)
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        dos.writeInt(msgBytes.size)
        dos.write(msgBytes)
        return baos.toByteArray()
    }

    private fun serializeActiveDisplaysResponse(sequence: Long, displayIds: IntArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(101)
        dos.writeLong(sequence)
        dos.writeInt(displayIds.size)
        for (id in displayIds) {
            dos.writeInt(id)
        }
        return baos.toByteArray()
    }

    @Test
    fun testRpcRequestResponseSuccess() = runBlocking {
        val outStream = ByteArrayOutputStream()

        // 构造假输入流：在发送请求后，模拟服务端立即写回响应
        // 我们的 sequence 预期从 1L 开始
        val expectedSeq = 1L
        val responseBytes = serializeGenericResponse(expectedSeq, 0, 10, "OK")
        val inStream = ByteArrayInputStream(responseBytes)

        val transport = DaemonTransport()
        transport.controlIn = DataInputStream(inStream)
        transport.controlOut = DataOutputStream(outStream)

        val rpc = DaemonRpc(transport)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        rpc.startMessageLoop(scope)

        // 发送 CreateVirtualDisplay
        val api = DaemonControlApiImpl(rpc)
        val result = api.createDisplay("TestVD", 1920, 1080, 320, 0)

        assertTrue(result.isSuccess)
        assertEquals(10, result.getOrNull())

        // 校验客户端向服务端发送的请求字节是否符合格式
        val sentBytes = outStream.toByteArray()
        val buffer = ByteBuffer.wrap(sentBytes)
        assertEquals(ControlMessage.TYPE_CREATE_VIRTUAL_DISPLAY.toByte(), buffer.get())
        assertEquals(expectedSeq, buffer.getLong())

        rpc.stopMessageLoop()
    }

    @Test
    fun testRpcTimeout() = runBlocking {
        val outStream = ByteArrayOutputStream()
        // 构造一个不发送任何响应的空输入流
        val inStream = ByteArrayInputStream(ByteArray(0))

        val transport = DaemonTransport()
        transport.controlIn = DataInputStream(inStream)
        transport.controlOut = DataOutputStream(outStream)

        val rpc = DaemonRpc(transport)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        rpc.startMessageLoop(scope)

        val api = DaemonControlApiImpl(rpc)
        // 使用非常短的超时进行测试，期待抛出连接或超时 IOException 失败的 Result
        val result = rpc.sendAndAwait(ControlMessage.ExitDaemon, timeoutMs = 100L)

        assertNull(result)
        rpc.stopMessageLoop()
    }

    @Test
    fun testRpcConcurrentRequests() = runBlocking {
        val outStream = ByteArrayOutputStream()

        // resp1 corresponds to seq=1 (CreateVirtualDisplay -> GenericResponse)
        // resp2 corresponds to seq=2 (GetActiveDisplayIds -> ActiveDisplaysResponse)
        val resp1 = serializeGenericResponse(1L, 0, 101, "OK1")
        val resp2 = serializeActiveDisplaysResponse(2L, intArrayOf(101, 102))

        val inStream = ByteArrayInputStream(resp1 + resp2)

        val transport = DaemonTransport()
        transport.controlIn = DataInputStream(inStream)
        transport.controlOut = DataOutputStream(outStream)

        val rpc = DaemonRpc(transport)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        rpc.startMessageLoop(scope)

        val api = DaemonControlApiImpl(rpc)

        // Use UNDISPATCHED launch so each async body runs synchronously on the
        // current thread UP TO its first suspension point (withContext(IO)
        // inside sendAndAwait). This guarantees sequence allocations happen
        // in code order (createDisplay → seq=1, getActiveDisplayIds → seq=2),
        // which matches the concatenated response-byte order. The actual
        // wire writes AND response-matching still happen concurrently on
        // Dispatchers.IO so we still validate "two in-flight requests at the
        // same time are routed to the correct deferred by sequence".
        //
        // The previous implementation used plain async (Dispatchers.Default
        // default dispatcher), which could schedule getActiveDisplayIds first,
        // grabbing seq=1, then the GenericResponse (seq=1) was delivered to
        // the ActiveDisplaysResponse-expecting deferred → flaky failure.
        val deferred1 = async(start = CoroutineStart.UNDISPATCHED) {
            val res = api.createDisplay("VD1", 100, 100, 100, 0)
            assertTrue("createDisplay must succeed: $res", res.isSuccess)
            assertEquals(101, res.getOrNull())
        }

        val deferred2 = async(start = CoroutineStart.UNDISPATCHED) {
            val res = api.getActiveDisplayIds()
            assertTrue("getActiveDisplayIds must succeed: $res", res.isSuccess)
            assertArrayEquals(intArrayOf(101, 102), res.getOrNull())
        }

        deferred1.await()
        deferred2.await()

        rpc.stopMessageLoop()
    }

    @Test
    fun testUnsolicitedMessageDispatched() = runBlocking {
        val outStream = ByteArrayOutputStream()
        // unsolicited message sequence = 0L (not matched to any pending request)
        val unsolicited = serializeGenericResponse(0L, 0, -1, "BROADCAST")

        val inStream = ByteArrayInputStream(unsolicited)

        val transport = DaemonTransport()
        transport.controlIn = DataInputStream(inStream)
        transport.controlOut = DataOutputStream(outStream)

        val rpc = DaemonRpc(transport)
        val scope = CoroutineScope(Dispatchers.Default + Job())

        // Use UNDISPATCHED subscription so deviceMessages.first() registers a
        // collector BEFORE we yield and let the message-loop coroutine read
        // the input stream. deviceMessages is a MutableSharedFlow with
        // replay=0 / extraBufferCapacity=64: if we emit before subscribing
        // the element is dropped into the extra buffer but never replayed to
        // late collectors → first() hangs forever until a 45s test timeout
        // skips the test.
        val receiveJob = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.deviceMessages.first()
        }
        // Yield so any subscriber-registration side effects complete before
        // starting the reader coroutine.
        yield()

        rpc.startMessageLoop(scope)

        val received = receiveJob.await()
        assertTrue(received is DeviceMessage.GenericResponse)
        assertEquals(0L, (received as DeviceMessage.GenericResponse).sequence)
        assertEquals("BROADCAST", received.message)

        rpc.stopMessageLoop()
    }
}
