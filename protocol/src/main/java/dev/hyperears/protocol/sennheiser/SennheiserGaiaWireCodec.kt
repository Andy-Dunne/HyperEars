package dev.hyperears.protocol.sennheiser


object SennheiserGaiaWireCodec {
    const val VENDOR_SENNHEISER = 0x0495

    const val RFCOMM_SERVICE = "a2129ff3-081b-4c45-8afe-469d9c4842ec"

    const val RFCOMM_FALLBACK_SERVICE = "00001101-0000-1000-8000-00805f9b34fb"

    private const val MAGIC = 0xFF
    private const val VERSION_REQUEST = 0x03
    private const val VERSION_REPLY = 0x04
    private const val HEADER_SIZE = 8

    private const val REPLY_BIT = 0x0100
    private const val ERROR_OR_PUSH_BIT = 0x0080
    private const val COMMAND_MASK = 0xFE7F

    private const val MAX_PAYLOAD_SIZE = 512

    object Command {
        const val NOISE_CANCELLATION_SET = 0x1A04
        const val NOISE_CANCELLATION_GET = 0x1A05
        const val TRANSPARENCY_LEVEL_SET = 0x1A02
        const val TRANSPARENCY_LEVEL_GET = 0x1A03
        const val NOISE_CONTROL_TABLE_SET = 0x1A00
        const val NOISE_CONTROL_TABLE_GET = 0x1A01
        const val BATTERY_GET = 0x0603
        const val FIRMWARE_GET = 0x1201

        const val CHARGING_PUSH = 0x0682
    }

    class Frame(
        val version: Int,
        val command: Int,
        val payload: ByteArray,
    ) {
        val baseCommand: Int get() = command and COMMAND_MASK

        val isReply: Boolean get() = version == VERSION_REPLY || command and REPLY_BIT != 0

        val isErrorReply: Boolean get() = isReply && command and ERROR_OR_PUSH_BIT != 0

        val isNotification: Boolean get() = !isReply && command and ERROR_OR_PUSH_BIT != 0
    }

    fun encode(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(command in 0..0xFFFF) { "GAIA command id must be a 16-bit value" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "GAIA payload exceeds the frame limit" }
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = MAGIC.toByte()
        frame[1] = VERSION_REQUEST.toByte()
        frame[2] = ((payload.size ushr 8) and 0xFF).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        frame[4] = ((VENDOR_SENNHEISER ushr 8) and 0xFF).toByte()
        frame[5] = (VENDOR_SENNHEISER and 0xFF).toByte()
        frame[6] = ((command ushr 8) and 0xFF).toByte()
        frame[7] = (command and 0xFF).toByte()
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }


    val batteryQuery: ByteArray get() = encode(Command.BATTERY_GET)

    val noiseCancellationQuery: ByteArray get() = encode(Command.NOISE_CANCELLATION_GET)

    val transparencyLevelQuery: ByteArray get() = encode(Command.TRANSPARENCY_LEVEL_GET)

    val noiseControlTableQuery: ByteArray get() = encode(Command.NOISE_CONTROL_TABLE_GET)

    fun noiseCancellationSet(enabled: Boolean): ByteArray = encode(
        Command.NOISE_CANCELLATION_SET,
        byteArrayOf(if (enabled) 1 else 0),
    )

    fun transparencyLevelSet(level: Int): ByteArray = encode(
        Command.TRANSPARENCY_LEVEL_SET,
        byteArrayOf(level.coerceIn(0, 100).toByte()),
    )

    fun noiseControlTableSet(table: ByteArray): ByteArray = encode(
        Command.NOISE_CONTROL_TABLE_SET,
        table,
    )


    fun parseBattery(frame: Frame): Int? {
        if (frame.baseCommand != Command.BATTERY_GET || !frame.isReply) return null
        if (frame.isErrorReply) return null
        return frame.payload.firstOrNull()
            ?.let { it.toInt() and 0xFF }
            ?.takeIf { it in 0..100 }
    }

    fun parseNoiseCancellation(frame: Frame): Boolean? {
        if (frame.baseCommand != Command.NOISE_CANCELLATION_GET || !frame.isReply) return null
        if (frame.isErrorReply) return null
        return frame.payload.firstOrNull()?.let { (it.toInt() and 0xFF) != 0 }
    }

    fun parseTransparencyLevel(frame: Frame): Int? {
        if (frame.baseCommand != Command.TRANSPARENCY_LEVEL_GET || !frame.isReply) return null
        if (frame.isErrorReply) return null
        return frame.payload.firstOrNull()
            ?.let { it.toInt() and 0xFF }
            ?.takeIf { it in 0..100 }
    }

    fun parseCharging(frame: Frame): Boolean? {
        if (frame.command != Command.CHARGING_PUSH || frame.isReply) return null
        return frame.payload.firstOrNull()?.let { (it.toInt() and 0xFF) != 0 }
    }

   
    fun parseNoiseControlTable(payload: ByteArray): Map<Int, Int>? {
        if (payload.size < NOISE_CONTROL_TABLE_SIZE) return null
        return (0 until NOISE_CONTROL_TABLE_SIZE / 2)
            .associate { index ->
                val pair = index * 2
                (payload[pair].toInt() and 0xFF) to (payload[pair + 1].toInt() and 0xFF)
            }
    }

    fun encodeNoiseControlTable(table: Map<Int, Int>): ByteArray? {
        if (table.size != NOISE_CONTROL_TABLE_SIZE / 2) return null
        val payload = ByteArray(NOISE_CONTROL_TABLE_SIZE)
        table.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            payload[index * 2] = entry.key.toByte()
            payload[index * 2 + 1] = entry.value.toByte()
        }
        return payload
    }

    const val NOISE_CONTROL_TABLE_SIZE = 6

    object NoiseField {
        const val ANTI_WIND = 1
        const val ADAPTIVE = 3
    }

  
    class Decoder {
        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Frame> {
            if (bytes.isEmpty()) return emptyList()
            buffer = buffer + bytes
            val frames = mutableListOf<Frame>()
            var offset = 0
            while (buffer.size - offset >= HEADER_SIZE) {
                if (!isFrameStart(offset)) {
                    offset++
                    continue
                }
                val payloadSize = uint16(offset + 2)
                if (payloadSize > MAX_PAYLOAD_SIZE) {
                    offset++
                    continue
                }
                if (uint16(offset + 4) != VENDOR_SENNHEISER) {
                    offset++
                    continue
                }
                val total = HEADER_SIZE + payloadSize
                if (buffer.size - offset < total) break
                frames += Frame(
                    version = buffer[offset + 1].toInt() and 0xFF,
                    command = uint16(offset + 6),
                    payload = buffer.copyOfRange(offset + HEADER_SIZE, offset + total),
                )
                offset += total
            }
            buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
        }

        private fun isFrameStart(offset: Int): Boolean {
            if (buffer[offset].toInt() and 0xFF != MAGIC) return false
            val version = buffer[offset + 1].toInt() and 0xFF
            return version == VERSION_REQUEST || version == VERSION_REPLY
        }

        private fun uint16(offset: Int): Int =
            ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }
}
