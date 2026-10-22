package dev.hyperears.integration

import dev.hyperears.protocol.sennheiser.SennheiserGaiaWireCodec

class SennheiserMomentum4Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Sennheiser MOMENTUM 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val formFactor: HeadsetFormFactor = HeadsetFormFactor.HEADPHONES
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> =
        listOf(ControlAppCatalog.sennheiserSmartControl)


    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SennheiserGaiaWireCodec.RFCOMM_SERVICE,
            id = "sennheiser-gaia-sdp",
            secure = false,
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = SennheiserGaiaWireCodec.RFCOMM_FALLBACK_SERVICE,
            id = "sennheiser-standard-spp",
            secure = false,
        ),
        RfcommEndpointSpec.Channel(
            number = GAIA_FALLBACK_CHANNEL,
            secure = false,
            id = "sennheiser-gaia-channel",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val normalized = normalizeDeviceName(identity.deviceName.orEmpty())
        if (MOMENTUM_4_NAME.matches(normalized)) return true
        return identity.serviceUuids.any { it.equals(GAIA_SERVICE_UUID, ignoreCase = true) } &&
            normalized.contains("momentum") &&
            normalized.contains("4")
    }

    override fun createProtocolSession(): ProtocolSession = SennheiserGaiaProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "sennheiser-momentum-4"

        const val GAIA_SERVICE_UUID = SennheiserGaiaWireCodec.RFCOMM_SERVICE

        const val GAIA_FALLBACK_CHANNEL = 1

        internal val SUPPORTED_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )

        private val MOMENTUM_4_NAME = Regex("^momentum4[a-z0-9]{0,4}$")
    }
}


private class SennheiserGaiaProtocolSession : ProtocolSession {
    private val decoder = SennheiserGaiaWireCodec.Decoder()

    private var batteryPercent: Int? = null
    private var charging = false
    private var noiseCancellationEnabled: Boolean? = null
    private var transparencyLevel: Int? = null
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        SennheiserGaiaWireCodec.batteryQuery,
        SennheiserGaiaWireCodec.noiseCancellationQuery,
        SennheiserGaiaWireCodec.transparencyLevelQuery,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> initialReadCommands()
        is StandardControlRequest.SetNoiseMode -> request.mode.toWireSequence()
        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> initialReadCommands()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> listOf(SennheiserGaiaWireCodec.batteryQuery)
            NoiseModeFeatureState.FEATURE_ID -> noiseQueries()
            else -> emptyList()
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode -> noiseQueries()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap(::decode)

    override fun reset() {
        decoder.reset()
        batteryPercent = null
        charging = false
        noiseCancellationEnabled = null
        transparencyLevel = null
        handshakePublished = false
    }

    private fun decode(frame: SennheiserGaiaWireCodec.Frame): List<ProtocolEvent> {
        val events = mutableListOf<ProtocolEvent>()

        SennheiserGaiaWireCodec.parseCharging(frame)?.let { attached ->
            charging = attached
            publishBattery(events)
            return events
        }

        SennheiserGaiaWireCodec.parseBattery(frame)?.let { percent ->
            batteryPercent = percent
            publishBattery(events)
            events += ProtocolEvent.CapabilitiesIdentified(battery = true)
            publishHandshake(events)
            return events
        }

        SennheiserGaiaWireCodec.parseNoiseCancellation(frame)?.let { enabled ->
            noiseCancellationEnabled = enabled
            publishNoiseModes(events)
            return events
        }

        SennheiserGaiaWireCodec.parseTransparencyLevel(frame)?.let { level ->
            transparencyLevel = level
            publishNoiseModes(events)
            return events
        }

        return listOf(
            ProtocolEvent.UnknownFrame(
                version = 0,
                vendor = SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
                command = frame.baseCommand,
                payloadSize = frame.payload.size,
            ),
        )
    }

    private fun publishBattery(events: MutableList<ProtocolEvent>) {
        val percent = batteryPercent ?: return
        val reading = BatteryReading(percent, charging)
        events += ProtocolEvent.FeatureStateChanged(
            BatteryFeatureState(
                EarbudBattery(left = reading, right = reading, overall = reading),
            ),
        )
    }

    private fun publishNoiseModes(events: MutableList<ProtocolEvent>) {
        events += ProtocolEvent.CapabilitiesIdentified(
            battery = false,
            noiseModes = SennheiserMomentum4Adapter.SUPPORTED_NOISE_MODES,
        )
        resolveNoiseMode()?.let { mode ->
            events += ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode))
        }
        publishHandshake(events)
    }

    private fun resolveNoiseMode(): NoiseMode? {
        val active = noiseCancellationEnabled ?: return null
        if (!active) return NoiseMode.OFF
        val level = transparencyLevel ?: return NoiseMode.ANC
        return if (level >= TRANSPARENCY_LEVEL_THRESHOLD) {
            NoiseMode.TRANSPARENCY
        } else {
            NoiseMode.ANC
        }
    }

    private fun publishHandshake(events: MutableList<ProtocolEvent>) {
        if (handshakePublished) return
        handshakePublished = true
        events += ProtocolEvent.HandshakeAccepted
    }

    private fun NoiseMode.toWireSequence(): List<ByteArray> = when (this) {
        NoiseMode.ANC -> listOf(
            SennheiserGaiaWireCodec.noiseCancellationSet(true),
            SennheiserGaiaWireCodec.transparencyLevelSet(FULL_CANCELLATION_LEVEL),
        )

        NoiseMode.OFF -> listOf(SennheiserGaiaWireCodec.noiseCancellationSet(false))

        NoiseMode.TRANSPARENCY -> listOf(
            SennheiserGaiaWireCodec.noiseCancellationSet(true),
            SennheiserGaiaWireCodec.transparencyLevelSet(FULL_TRANSPARENCY_LEVEL),
        )

        NoiseMode.WIND -> emptyList()
    }

    private fun noiseQueries(): List<ByteArray> = listOf(
        SennheiserGaiaWireCodec.noiseCancellationQuery,
        SennheiserGaiaWireCodec.transparencyLevelQuery,
    )

    private companion object {
        const val FULL_CANCELLATION_LEVEL = 0
        const val FULL_TRANSPARENCY_LEVEL = 100
        const val TRANSPARENCY_LEVEL_THRESHOLD = 50
    }
}
