package com.pedro.whip

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.toUInt32
import com.pedro.common.validMessage
import com.pedro.rtsp.utils.CryptoProperties
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.whip.dtls.DtlsClient
import com.pedro.whip.dtls.DtlsConnection
import com.pedro.whip.dtls.DtlsTransport
import com.pedro.whip.webrtc.CandidateType
import com.pedro.whip.webrtc.CommandsManager
import com.pedro.whip.webrtc.stun.AttributeType
import com.pedro.whip.webrtc.stun.GatheringMode
import com.pedro.whip.webrtc.stun.HeaderType
import com.pedro.whip.webrtc.stun.StunAttribute
import com.pedro.whip.webrtc.stun.StunAttributeValueParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URISyntaxException
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.milliseconds

class WhipClient(private val connectChecker: ConnectChecker) {

    private val TAG = "WhipClient"

    // GPX patch: accept https/whip (standard WHIP is https), not just plaintext http.
    private val validSchemes = arrayOf("http", "https", "whip")

    private var scope = CoroutineScope(Dispatchers.IO)
    private var scopeRetry = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var jobRetry: Job? = null
    private var mutex = Mutex(locked = true)

    @Volatile
    var isStreaming = false
        private set

    //for secure transport
    private var tlsEnabled = false
    private var dtlsConnection: DtlsConnection? = null
    // GPX patch: client-role DTLS handshaker, used when the SFU answers a=setup:passive (Millicast).
    private var dtlsClient: DtlsClient? = null
    // GPX patch: hold the ICE/media UDP socket so disconnect() can close it. Was a local val inside the
    // connect job; on stop the blocking STUN read loop kept the socket alive and STUN kept flowing.
    @Volatile
    private var iceSocket: UdpStreamSocket? = null
    // GPX debug: count media-plane (SRTP/SRTCP) packets received from the server after DTLS. For a
    // send-only WHIP publisher these are the server's RTCP feedback (transport-cc/RR/PLI) — their
    // presence proves the ingest is actually receiving our media, not just holding the ICE session.
    private val mediaPlaneIn = java.util.concurrent.atomic.AtomicLong(0)
    private val commandsManager: CommandsManager = CommandsManager()
    private val whipSender: WhipSender = WhipSender(connectChecker, commandsManager)
    private var url: String? = null
    private var doingRetry = false
    private var numRetry = 0
    private var reTries = 0
    private var checkServerAlive = false
    // GPX patch: bearer token for standard-WHIP auth (e.g. Millicast publishing token). Set via
    // setAuthorization, or read from the URL ?token= / userinfo at connect.
    private var authToken: String? = null
    var socketType = SocketType.KTOR

    val droppedAudioFrames: Long
        get() = whipSender.getDroppedAudioFrames()
    val droppedVideoFrames: Long
        get() = whipSender.getDroppedVideoFrames()

    val cacheSize: Int
        get() = whipSender.getCacheSize()
    val sentAudioFrames: Long
        get() = whipSender.getSentAudioFrames()
    val sentVideoFrames: Long
        get() = whipSender.getSentVideoFrames()
    val bytesSend: Long
        get() = whipSender.getBytesSend()
    var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

    fun setDelay(millis: Long) {
        whipSender.setDelay(millis)
    }

    fun setAuthorization(user: String?, password: String?) {
        // GPX patch: store the bearer token (password, else user). Used as the WHIP Authorization
        // header when the URL carries no ?token=. Was TODO("unimplemented") which crashed any caller.
        authToken = password ?: user
    }

    /**
     * Check periodically if server is alive using Echo protocol.
     */
    fun setCheckServerAlive(enabled: Boolean) {
        checkServerAlive = enabled
    }

    /**
     * Must be called before connect
     */
    fun setOnlyAudio(onlyAudio: Boolean) {
        if (onlyAudio) {
            commandsManager.rtpTracks.trackAudio = 0
            commandsManager.rtpTracks.trackVideo = 1
        } else {
            commandsManager.rtpTracks.trackVideo = 0
            commandsManager.rtpTracks.trackAudio = 1
        }
        commandsManager.audioDisabled = false
        commandsManager.videoDisabled = onlyAudio
    }

    /**
     * Must be called before connect
     */
    fun setOnlyVideo(onlyVideo: Boolean) {
        commandsManager.rtpTracks.trackVideo = 0
        commandsManager.rtpTracks.trackAudio = 1
        commandsManager.videoDisabled = false
        commandsManager.audioDisabled = onlyVideo
    }

    fun setReTries(reTries: Int) {
        numRetry = reTries
        this.reTries = reTries
    }

    fun shouldRetry(reason: String): Boolean {
        val validReason = doingRetry && !reason.contains("Endpoint malformed")
        return validReason && reTries > 0
    }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        Log.i(TAG, "send sps and pps")
        commandsManager.setVideoInfo(sps, pps, vps)
        if (mutex.isLocked) runCatching { mutex.unlock() }
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        commandsManager.setAudioInfo(sampleRate, isStereo)
    }

    fun setVideoCodec(videoCodec: VideoCodec) {
        if (!isStreaming) {
            commandsManager.videoCodec = videoCodec
        }
    }

    fun setAudioCodec(audioCodec: AudioCodec) {
        if (!isStreaming) {
            commandsManager.audioCodec = when (audioCodec) {
                AudioCodec.AAC -> throw IllegalArgumentException("Unsupported codec: ${audioCodec.name}")
                else -> audioCodec
            }
        }
    }

    fun connect(url: String?) {
        connect(url, false)
    }

    fun connect(url: String?, isRetry: Boolean) {
        if (!isRetry) doingRetry = true
        if (!isStreaming || isRetry) {
            isStreaming = true

            job = scope.launch {
                if (url == null) {
                    isStreaming = false
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: http://ip:port/appname/streamname")
                    }
                    return@launch
                }
                this@WhipClient.url = url
                onMainThread { connectChecker.onConnectionStarted(url) }

                val urlParser = try {
                    UrlParser.parse(url, validSchemes)
                } catch (_: URISyntaxException) {
                    isStreaming = false
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: http://ip:port/appname/streamname")
                    }
                    return@launch
                }

                tlsEnabled = urlParser.scheme != "http"
                val host = urlParser.host
                val port = urlParser.port ?: if (tlsEnabled) 443 else 8889
                // GPX patch: standard WHIP — POST to the FULL endpoint path with a separate Bearer token
                // (Millicast: /api/whip/<stream> + token), instead of pedro's appName-only POST with the
                // last path segment used as the token. Token from ?auth= (Millicast combined URL) or setAuthorization.
                val path = urlParser.path.removePrefix("/")
                val token = urlParser.getQuery("auth") ?: authToken
                if (path.isEmpty()) {
                    isStreaming = false
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: scheme://host[:port]/path e.g. https://host/api/whip/<stream>?token=...")
                    }
                    return@launch
                }

                val error = runCatching {
                    commandsManager.setUrl(host, port, path, token, tlsEnabled)
                    if (!commandsManager.audioDisabled) {
                        whipSender.setAudioInfo(commandsManager.sampleRate, commandsManager.isStereo)
                    }
                    if (!commandsManager.videoDisabled) {
                        if (!commandsManager.videoInfoReady()) {
                            Log.i(TAG, "waiting for sps and pps")
                            withTimeoutOrNull(5_000.milliseconds) { mutex.lock() }
                            if (!commandsManager.videoInfoReady()) {
                                onMainThread {
                                    connectChecker.onConnectionFailed("sps or pps is null")
                                }
                                return@launch
                            }
                        }
                        whipSender.setVideoInfo(commandsManager.sps!!, commandsManager.pps, commandsManager.vps)
                    }

                    val localCandidates = commandsManager.gatheringCandidates(socketType, socketTimeout, GatheringMode.LOCAL)
                    Log.i(TAG, "found ${localCandidates.size} candidates")
                    val offerResponse = commandsManager.writeOffer()
                    Log.i(TAG, offerResponse.body)


                    val localFrag = commandsManager.localSdpInfo?.uFrag ?: return@launch
                    val remoteFrag = commandsManager.remoteSdpInfo?.uFrag ?: return@launch
                    val priority = commandsManager.calculatePriority(CandidateType.LOCAL, 65535L, 1)

                    val remoteCandidates = commandsManager.remoteSdpInfo?.candidates?.filter {
                        it.getRealHost() != "127.0.0.1"
                    } ?: return@launch

                    val host = remoteCandidates[0].getRealHost()
                    val port = remoteCandidates[0].getRealPort()
                    val socket = StreamSocket.createUdpSocket(socketType, host, port, socketTimeout, receiveSize = RtpConstants.MTU)
                    socket.connect()
                    iceSocket = socket

                    // GPX patch: log the negotiated DTLS role. We offer setup:passive, so a compliant SFU
                    // answers setup:active and sends the ClientHello (our DTLSServerProtocol.accept path).
                    // If the server keeps setup:passive, both sides wait and the DTLS step below will time
                    // out with a clear "DTLS handshake failed" (no infinite hang).
                    Log.i(TAG, "remote DTLS setup role: ${commandsManager.remoteSdpInfo?.setupRole}")

                    val requestId = commandsManager.generateTransactionId()
                    val userName = StunAttributeValueParser.createUserName(localFrag, remoteFrag)
                    val attributes = listOf(
                        StunAttribute(AttributeType.USERNAME, userName),
                        StunAttribute(AttributeType.ICE_CONTROLLING, commandsManager.tieBreak),
                        StunAttribute(AttributeType.PRIORITY, priority.toUInt32()),
                    )
                    commandsManager.writeStun(HeaderType.REQUEST, requestId, attributes, socket)

                    // GPX patch: ICE binding-check with retransmit. The original sent ONE request then
                    // blocked forever on readStun; against an ice-lite SFU (Millicast) that never acked our
                    // single request the loop spun on the SFU's own checks, never nominated, never reached
                    // DTLS -> connection up but zero media. Retransmit on a short timeout, bounded, then fail.
                    val iceRtoMs = 500L
                    val iceMaxAttempts = 14 // ~7s worst case
                    var candidateResponses = 0
                    var requestSuccessReceived = false
                    var iceAttempts = 0
                    while (candidateResponses < 1 || !requestSuccessReceived) {
                        val command = withTimeoutOrNull(iceRtoMs.milliseconds) { commandsManager.readStun(socket) }
                        if (command == null) {
                            if (++iceAttempts >= iceMaxAttempts) break
                            commandsManager.writeStun(HeaderType.REQUEST, requestId, attributes, socket)
                            continue
                        }
                        if (command.header.id.contentEquals(requestId) && command.header.type == HeaderType.SUCCESS) {
                            requestSuccessReceived = true
                        } else if (command.header.type == HeaderType.REQUEST) {
                            candidateResponses++
                            val xorAddress = StunAttributeValueParser.createXorMappedAddress(command.header.id, host, port, true)
                            val responseAttributes = listOf(
                                StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)
                            )
                            commandsManager.writeStun(HeaderType.SUCCESS, command.header.id, responseAttributes, socket)
                        }
                    }
                    if (!requestSuccessReceived) {
                        runCatching { socket.close() }
                        iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("ICE connectivity check failed (no STUN binding success from server)")
                        }
                        return@launch
                    }

                    val nominateId = commandsManager.generateTransactionId()
                    val nominateAttributes = listOf(
                        StunAttribute(AttributeType.PRIORITY, priority.toUInt32()),
                        StunAttribute(AttributeType.USERNAME, userName),
                        StunAttribute(AttributeType.ICE_CONTROLLING, commandsManager.tieBreak),
                        StunAttribute(AttributeType.USE_CANDIDATE, byteArrayOf()),
                    )
                    commandsManager.writeStun(HeaderType.REQUEST, nominateId, nominateAttributes, socket)

                    // GPX patch: same retransmit treatment for the USE-CANDIDATE nomination.
                    var nominateSuccessReceived = false
                    var nominateAttempts = 0
                    while (!nominateSuccessReceived) {
                        val command = withTimeoutOrNull(iceRtoMs.milliseconds) { commandsManager.readStun(socket) }
                        if (command == null) {
                            if (++nominateAttempts >= iceMaxAttempts) break
                            commandsManager.writeStun(HeaderType.REQUEST, nominateId, nominateAttributes, socket)
                            continue
                        }
                        if (command.header.id.contentEquals(nominateId) && command.header.type == HeaderType.SUCCESS) {
                            nominateSuccessReceived = true
                        } else if (command.header.type == HeaderType.REQUEST) {
                            candidateResponses++
                            val xorAddress = StunAttributeValueParser.createXorMappedAddress(command.header.id, host, port, true)
                            val responseAttributes = listOf(
                                StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)
                            )
                            commandsManager.writeStun(HeaderType.SUCCESS, command.header.id, responseAttributes, socket)
                        }
                    }
                    if (!nominateSuccessReceived) {
                        runCatching { socket.close() }
                        iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("ICE nomination failed (no STUN success for USE-CANDIDATE)")
                        }
                        return@launch
                    }

                    val certificate = commandsManager.certificate ?: return@launch
                    val fingerprint = commandsManager.remoteSdpInfo?.fingerprint ?: return@launch

                    // GPX patch: choose the DTLS role from the answer's a=setup. WHIP ingests (Millicast)
                    // answer "passive" -> they are the DTLS server, so WE are the client and send the
                    // ClientHello (DtlsClient). Only when the remote explicitly answers "active" do we keep
                    // the original server-accept path (DtlsConnection). The SRTP write key index differs by
                    // role: client writes with the client key [0], server with the server key [1].
                    val remoteSetup = commandsManager.remoteSdpInfo?.setupRole
                    val weAreServer = remoteSetup.equals("active", ignoreCase = true)
                    Log.i(TAG, "DTLS role: ${if (weAreServer) "server(accept)" else "client(connect)"} (remote a=setup:$remoteSetup)")

                    val dtlsResult = CompletableDeferred<Result<List<CryptoProperties>>>()
                    val dtlsTransport = DtlsTransport(socket)

                    val dispatchJob = launch {
                        while (isActive) {
                            try {
                                handleMessages(socket, host, port, dtlsTransport)
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                    if (weAreServer) {
                        dtlsConnection = DtlsConnection(certificate, fingerprint)
                        dtlsConnection?.start(dtlsTransport, object : DtlsConnection.Callback {
                            override fun onHandshakeComplete(properties: List<CryptoProperties>) {
                                dtlsResult.complete(Result.success(properties))
                            }
                            override fun onHandshakeFailed(reason: String?) {
                                dtlsResult.complete(Result.failure(Exception(reason ?: "DTLS handshake failed")))
                            }
                        })
                    } else {
                        dtlsClient = DtlsClient(certificate, fingerprint)
                        dtlsClient?.start(dtlsTransport, object : DtlsClient.Callback {
                            override fun onHandshakeComplete(properties: List<CryptoProperties>) {
                                dtlsResult.complete(Result.success(properties))
                            }
                            override fun onHandshakeFailed(reason: String?) {
                                dtlsResult.complete(Result.failure(Exception(reason ?: "DTLS handshake failed")))
                            }
                        })
                    }

                    val result = withTimeoutOrNull(5_000.milliseconds) { dtlsResult.await() }
                        ?: Result.failure(Exception("timeout"))
                    val cryptoProperties = result.getOrNull()
                    if (cryptoProperties == null) {
                        dispatchJob.cancel()
                        dtlsConnection?.close()
                        dtlsConnection = null
                        dtlsClient?.close()
                        dtlsClient = null
                        runCatching { socket.close() }
                        iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("DTLS handshake failed: ${result.exceptionOrNull()?.message}")
                        }
                        return@launch
                    }
                    Log.i(TAG, "dtls connected!!")
                    onMainThread { connectChecker.onConnectionSuccess() }
                    // client writes with client key [0]; server writes with server key [1].
                    whipSender.setCrypto(if (weAreServer) cryptoProperties[1] else cryptoProperties[0])
                    whipSender.setSocketsInfo(socket)
                    whipSender.start()
                }.exceptionOrNull()
                if (error != null) {
                    Log.e(TAG, "connection error", error)
                    onMainThread {
                        connectChecker.onConnectionFailed("Error configure stream, ${error.validMessage()}")
                    }
                    return@launch
                }
            }
        }
    }

    suspend fun handleMessages(
      socket: UdpStreamSocket,
      host: String, port: Int,
      dtlsTransport: DtlsTransport
    ) {
        val bytes = socket.read()
        if (bytes.isEmpty()) return
        val first = bytes[0].toInt() and 0xFF
        when (first) {
            in 20..63 -> dtlsTransport.enqueue(bytes)
            in 128..191 -> {
                // RTP/RTCP – ignored for media (send-only), but counted: feedback here means the
                // server is receiving our stream. GPX debug log, throttled.
                val n = mediaPlaneIn.incrementAndGet()
                if (n <= 5L || n % 50L == 0L) Log.i(TAG, "media-plane in from server: $n")
            }
            else -> {
                try {
                    val command = commandsManager.readStun(bytes)
                    if (command.header.type == HeaderType.REQUEST) {
                        val xorAddress = StunAttributeValueParser.createXorMappedAddress(
                            command.header.id, host, port, true
                        )
                        commandsManager.writeStun(
                            HeaderType.SUCCESS, command.header.id,
                            listOf(StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)),
                            socket
                        )
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            disconnect(true)
        }
    }

    private suspend fun disconnect(clear: Boolean) {
        if (isStreaming) whipSender.stop()
        dtlsConnection?.close()
        dtlsConnection = null
        dtlsClient?.close()
        dtlsClient = null
        // GPX patch: close the ICE/media UDP socket so any in-flight STUN read unblocks and the OS stops
        // ACKing the server's binding checks. Without this the connect job's read loop kept STUN alive
        // after stopStream (server-side session lingered).
        runCatching { iceSocket?.close() }
        iceSocket = null
        val error = runCatching {
            //TODO write delete command
            Log.i(TAG, "write delete success")
        }.exceptionOrNull()
        if (error != null) {
            Log.e(TAG, "disconnect error", error)
        }
        if (clear) {
            commandsManager.clear()
            reTries = numRetry
            doingRetry = false
            isStreaming = false
            onMainThread {
                connectChecker.onDisconnect()
            }
            mutex = Mutex(true)
            jobRetry?.cancelAndJoin()
            jobRetry = null
            scopeRetry.cancel()
            scopeRetry = CoroutineScope(Dispatchers.IO)
        } else {
            commandsManager.clear()
        }
        job?.cancelAndJoin()
        job = null
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO)
    }

    fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandsManager.videoDisabled) {
            val cloned = videoBuffer.clone()
            // GPX patch: raise the H264 in-band SPS level_idc to at least 3.1. This device's Qualcomm
            // encoder reports level 3.0 for 960x540 (2040 macroblocks > L3.0's 1620 limit) -> the strict
            // WebRTC hardware decoder decodes "successfully" but renders BLACK (RTMP/HLS tolerates it).
            // One-byte SPS edit, no re-encode; matches the SDP profile-level-id (also forced >= L3.1).
            patchH264SpsLevel(cloned)
            whipSender.sendMediaFrame(MediaFrame(cloned, info.toMediaFrameInfo(), MediaFrame.Type.VIDEO))
        }
    }

    @Volatile private var spsLevelPatched = false
    // Raise the level_idc of an in-band H264 SPS (NAL type 7) to >= minLevel. Handles Annex-B start-code
    // prefixed NALs and a bare SPS NAL. level_idc is a fixed u(8) right after the constraint byte, so the
    // edit never shifts the rest of the SPS.
    private fun patchH264SpsLevel(buf: ByteBuffer, minLevel: Int = 0x1f) {
        val start = buf.position()
        val end = buf.limit()
        if (end - start >= 4 && (buf.get(start).toInt() and 0x9F) == 0x07) {
            patchSpsLevelAt(buf, start + 3, minLevel)
            return
        }
        var i = start
        while (i + 6 < end) {
            if (buf.get(i).toInt() == 0 && buf.get(i + 1).toInt() == 0 && buf.get(i + 2).toInt() == 1) {
                if ((buf.get(i + 3).toInt() and 0x1F) == 7) {
                    patchSpsLevelAt(buf, i + 6, minLevel)
                    return
                }
                i += 3
            } else i++
        }
    }

    private fun patchSpsLevelAt(buf: ByteBuffer, levelIdx: Int, minLevel: Int) {
        if (levelIdx >= buf.limit()) return
        val cur = buf.get(levelIdx).toInt() and 0xFF
        if (cur < minLevel) {
            buf.put(levelIdx, minLevel.toByte())
            if (!spsLevelPatched) {
                Log.i(TAG, "patched in-band SPS level_idc 0x${cur.toString(16)} -> 0x${minLevel.toString(16)}")
                spsLevelPatched = true
            }
        }
    }

    fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandsManager.audioDisabled) {
            whipSender.sendMediaFrame(MediaFrame(audioBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.AUDIO))
        }
    }

    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    fun hasCongestion(percentUsed: Float = 20f): Boolean {
        return whipSender.hasCongestion(percentUsed)
    }

    @JvmOverloads
    fun reConnect(delay: Long, backupUrl: String? = null) {
        jobRetry = scopeRetry.launch {
            reTries--
            disconnect(false)
            delay(delay.milliseconds)
            val reconnectUrl = backupUrl ?: url
            connect(reconnectUrl, true)
        }
    }

    fun resetSentAudioFrames() {
        whipSender.resetSentAudioFrames()
    }

    fun resetSentVideoFrames() {
        whipSender.resetSentVideoFrames()
    }

    fun resetDroppedAudioFrames() {
        whipSender.resetDroppedAudioFrames()
    }

    fun resetDroppedVideoFrames() {
        whipSender.resetDroppedVideoFrames()
    }

    @Throws(RuntimeException::class)
    fun resizeCache(newSize: Int) {
        whipSender.resizeCache(newSize)
    }

    fun setLogs(enable: Boolean) {
        whipSender.setLogs(enable)
    }

    fun clearCache() {
        whipSender.clearCache()
    }

    fun getItemsInCache(): Int = whipSender.getItemsInCache()

    /**
     * @param factor values from 0.1f to 1f
     * Set an exponential factor to the bitrate calculation to avoid bitrate spikes
     */
    fun setBitrateExponentialFactor(factor: Float) {
        whipSender.setBitrateExponentialFactor(factor)
    }

    /**
     * Get the exponential factor used to calculate the bitrate. Default 1f
     */
    fun getBitrateExponentialFactor() = whipSender.getBitrateExponentialFactor()

    fun resetBytesSend() {
        whipSender.resetBytesSend()
    }


}