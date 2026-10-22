package dev.hyperears.protocol.sennheiser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SennheiserGaiaWireCodecTest {
    @Test
    fun requestsUseTheEightByteGaiaHeader() {
        assertHex("FF 03 00 00 04 95 06 03", SennheiserGaiaWireCodec.batteryQuery)
        assertHex("FF 03 00 00 04 95 1A 05", SennheiserGaiaWireCodec.noiseCancellationQuery)
        assertHex("FF 03 00 00 04 95 1A 03", SennheiserGaiaWireCodec.transparencyLevelQuery)
        assertHex("FF 03 00 01 04 95 1A 04 01", SennheiserGaiaWireCodec.noiseCancellationSet(true))
        assertHex("FF 03 00 01 04 95 1A 04 00", SennheiserGaiaWireCodec.noiseCancellationSet(false))
        assertHex("FF 03 00 01 04 95 1A 02 64", SennheiserGaiaWireCodec.transparencyLevelSet(100))
    }

    @Test
    fun transparencyLevelIsClampedToTheDeviceRange() {
        assertHex("FF 03 00 01 04 95 1A 02 00", SennheiserGaiaWireCodec.transparencyLevelSet(-5))
        assertHex("FF 03 00 01 04 95 1A 02 64", SennheiserGaiaWireCodec.transparencyLevelSet(255))
    }

    @Test
    fun replyAndErrorBitsAreFlagsRatherThanCommandIdentity() {
        val reply = decoder().single("FF 03 00 01 04 95 1B 05 01")
        assertEquals(0x1A05, reply.baseCommand)
        assertTrue(reply.isReply)
        assertFalse(reply.isErrorReply)
        assertFalse(reply.isNotification)
        assertEquals(true, SennheiserGaiaWireCodec.parseNoiseCancellation(reply))

        val error = decoder().single("FF 03 00 01 04 95 1B 85 05")
        assertEquals(0x1A05, error.baseCommand)
        assertTrue(error.isReply)
        assertTrue(error.isErrorReply)
        assertNull(SennheiserGaiaWireCodec.parseNoiseCancellation(error))
    }

    @Test
    fun chargingPushIsMatchedOnTheLiteralCommandId() {
        val push = decoder().single("FF 03 00 01 04 95 06 82 01")
        assertTrue(push.isNotification)
        assertFalse(push.isReply)
        assertEquals(0x0602, push.baseCommand)
        assertEquals(true, SennheiserGaiaWireCodec.parseCharging(push))
        assertEquals(
            false,
            SennheiserGaiaWireCodec.parseCharging(decoder().single("FF 03 00 01 04 95 06 82 00")),
        )
        assertNull(
            SennheiserGaiaWireCodec.parseCharging(decoder().single("FF 03 00 01 04 95 06 83 01")),
        )
    }

    @Test
    fun bothReplyEncodingsResolveToTheSameState() {
        val bitForm = SennheiserGaiaWireCodec.parseNoiseCancellation(
            decoder().single("FF 03 00 01 04 95 1B 05 00"),
        )
        val versionForm = SennheiserGaiaWireCodec.parseNoiseCancellation(
            decoder().single("FF 04 00 01 04 95 1A 05 00"),
        )
        assertEquals(false, bitForm)
        assertEquals(bitForm, versionForm)
    }

    @Test
    fun batteryPercentOutsideTheValidRangeIsRejected() {
        assertEquals(87, SennheiserGaiaWireCodec.parseBattery(decoder().single("FF 03 00 01 04 95 07 03 57")))
        assertNull(SennheiserGaiaWireCodec.parseBattery(decoder().single("FF 03 00 01 04 95 07 03 6E")))
        assertNull(SennheiserGaiaWireCodec.parseBattery(decoder().single("FF 03 00 00 04 95 07 03")))
    }

    @Test
    fun decoderReassemblesFramesSplitAcrossReads() {
        val stream = hex("FF 03 00 01 04 95 07 03 57 FF 03 00 01 04 95 1B 05 01")
        val decoder = SennheiserGaiaWireCodec.Decoder()
        val frames = mutableListOf<SennheiserGaiaWireCodec.Frame>()
        val cuts = listOf(0, 3, 6, 11, stream.size)
        cuts.zipWithNext { from, to ->
            frames += decoder.offer(stream.copyOfRange(from, to))
        }
        assertEquals(2, frames.size)
        assertEquals(0x0603, frames[0].baseCommand)
        assertEquals(0x1A05, frames[1].baseCommand)
    }

    @Test
    fun decoderIgnoresLeadingGarbageAndForeignVendors() {
        val frames = decoder().offer(
            hex("00 FF 01 02 FF 03 00 01 04 96 1A 05 01 FF 03 00 01 04 95 1B 03 64"),
        )
        assertEquals(1, frames.size)
        assertEquals(0x1A03, frames[0].baseCommand)
        assertEquals(100, SennheiserGaiaWireCodec.parseTransparencyLevel(frames[0]))
    }

    @Test
    fun decoderDropsAnImplausibleLengthInsteadOfBufferingForever() {
        val frames = decoder().offer(hex("FF 03 FF FF 04 95 1A 05 01 FF 03 00 01 04 95 1B 05 01"))
        assertEquals(1, frames.size)
        assertEquals(0x1A05, frames[0].baseCommand)
    }

    @Test
    fun noiseControlTableRoundTripsAsIdValuePairs() {
        val payload = hex("01 02 02 00 03 01")
        val table = requireNotNull(SennheiserGaiaWireCodec.parseNoiseControlTable(payload))
        assertEquals(mapOf(1 to 2, 2 to 0, 3 to 1), table)
        assertArrayEquals(
            payload,
            SennheiserGaiaWireCodec.encodeNoiseControlTable(table),
        )
        assertNull(SennheiserGaiaWireCodec.parseNoiseControlTable(hex("01 02")))
        assertNull(SennheiserGaiaWireCodec.encodeNoiseControlTable(mapOf(1 to 2)))
    }

    private fun decoder() = SennheiserGaiaWireCodec.Decoder()

    private fun SennheiserGaiaWireCodec.Decoder.single(hexString: String): SennheiserGaiaWireCodec.Frame {
        val frames = offer(hex(hexString))
        assertEquals(1, frames.size)
        return frames.first()
    }

    private fun assertHex(expected: String, actual: ByteArray) {
        assertArrayEquals(hex(expected), actual)
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(" ").filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
}
