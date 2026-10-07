package com.metrodesk.together

import com.google.protobuf.ByteString
import com.metrodesk.together.proto.Listentogether.Envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Run: bash gradlew :app:test --tests com.metrodesk.together.RoomCodecTest */
class RoomCodecTest {
    private fun envelope(bytes: ByteArray, compressed: Boolean = false) = Envelope.newBuilder()
        .setType("test").setPayload(ByteString.copyFrom(bytes)).setCompressed(compressed).build().toByteArray()

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    @Test fun boundedDecode() {
        val text = "room state".toByteArray()
        listOf(envelope(text), envelope(gzip(text), true)).forEach { encoded ->
            val (type, payload) = decodeRoomEnvelope(encoded)
            assertEquals("test", type)
            assertArrayEquals(text, payload)
        }
        assertTrue(decodeRoomEnvelope(envelope(gzip(ByteArray(MAX_ROOM_PAYLOAD_BYTES)), true)).second.size == MAX_ROOM_PAYLOAD_BYTES)
        listOf(
            ByteArray(MAX_ROOM_PAYLOAD_BYTES + 1),
            envelope(gzip(ByteArray(MAX_ROOM_PAYLOAD_BYTES + 1)), true),
            envelope(byteArrayOf(1, 2, 3), true),
        ).forEach { unsafe -> assertTrue("Oversized or corrupt input must fail", runCatching { decodeRoomEnvelope(unsafe) }.isFailure) }
    }
}
