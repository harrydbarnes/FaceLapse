package com.facelapse.app.domain

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

class AnimatedGifEncoderTest {

    class FakeOutputStream : OutputStream() {
        var isClosed = false
        override fun write(b: Int) {
            if (isClosed) throw IOException("Stream is closed")
        }
        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun `finish does not close the output stream by default`() {
        val encoder = AnimatedGifEncoder()
        val os = FakeOutputStream()

        // AnimatedGifEncoder needs a stream to start
        encoder.start(os)

        // Finish should flush and typically finalize the GIF, but checks if it should close stream
        encoder.finish()

        assertFalse("Stream should not be closed by AnimatedGifEncoder, leaving responsibility to caller", os.isClosed)
    }
}
