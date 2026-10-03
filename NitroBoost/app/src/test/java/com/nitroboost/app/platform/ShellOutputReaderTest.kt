package com.nitroboost.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ShellOutputReaderTest {
    @Test
    fun `output is capped but the stream is fully drained`() {
        val input = ByteArrayInputStream(ByteArray(32_000) { 'a'.code.toByte() })
        val captured = ShellOutputReader.readLimited(input, 1_024)
        assertEquals(1_024, captured.toByteArray(Charsets.UTF_8).size)
        assertEquals(-1, input.read())
    }

    @Test
    fun `small output is preserved`() {
        assertEquals(
            "ok\n",
            ShellOutputReader.readLimited(ByteArrayInputStream("ok\n".toByteArray()), 1_024)
        )
    }
}
