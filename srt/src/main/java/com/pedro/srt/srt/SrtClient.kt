/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.srt.srt

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.ConnectionFailed
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.validMessage
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.ControlPacket
import com.pedro.srt.srt.packets.DataPacket
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.srt.packets.control.Ack
import com.pedro.srt.srt.packets.control.Ack2
import com.pedro.srt.srt.packets.control.CongestionWarning
import com.pedro.srt.srt.packets.control.DropReq
import com.pedro.srt.srt.packets.control.KeepAlive
import com.pedro.srt.srt.packets.control.Nak
import com.pedro.srt.srt.packets.control.PeerError
import com.pedro.srt.srt.packets.control.Shutdown
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import com.pedro.srt.srt.packets.control.handshake.ExtensionField
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.control.handshake.HandshakeType
import com.pedro.srt.srt.packets.control.handshake.extension.ExtensionContentFlag
import com.pedro.srt.srt.packets.control.handshake.extension.HandshakeExtension
import com.pedro.srt.utils.SrtSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.nio.ByteBuffer

/**
 * Created by pedro on 20/8/23.
 */
class SrtClient(private val connectChecker: ConnectChecker) {

  private val TAG = "SrtClient"

  // Handshake retransmit backoff (ms). The port historically sent each handshake ONCE and block-read
  // the full latency-derived socketTimeout, so a single lost UDP packet cost the entire window and
  // surfaced as "Poll timed out". We re-knock instead — but with EXPONENTIAL BACKOFF, not a fixed
  // cadence, because the two failure modes pull opposite ways:
  //   * cold lost-packet: wants a FAST re-knock (resend the dropped induction within ~250ms);
  //   * millicast stale-session hold: the server holds the prior session after a relaunch and only
  //     RELEASES during a lull — on-device, continuous 250ms knocking rode a full 11 s window with
  //     ZERO response, and the attempt that finally latched was the one preceded by a multi-second
  //     SILENT gap. So late in an attempt we want long quiet windows for the hold to release, then a
  //     prompt re-knock to catch it.
  // Backoff (250 -> 500 -> 1000 -> cap) serves both: fast cold-packet coverage early, lengthening
  // silence later. HANDSHAKE_RETRANSMIT_MS is also the socket read timeout (the poll granularity).
  private val HANDSHAKE_RETRANSMIT_MS = 250L
  private val HANDSHAKE_RETRANSMIT_CAP_MS = 2_000L

  private val validSchemes = arrayOf("srt")

  private val commandsManager = CommandsManager()
  private val srtSender = SrtSender(connectChecker, commandsManager)
  private var socket: SrtSocket? = null
  private var scope = CoroutineScope(Dispatchers.IO)
  private var job: Job? = null
  private var scopeRetry = CoroutineScope(Dispatchers.IO)
  private var jobRetry: Job? = null

  private var checkServerAlive = false
  // Inbound-liveness tracking. A silent UDP blackhole keeps sendto() succeeding (bytesSend climbs)
  // while the server stops ACKing, so onConnectionFailed never fires and the stream ghost-freezes.
  // Stamp the last time ANY packet was read from the socket; if that gap exceeds the timeout the link
  // is dead regardless of the local "connected" flag. Firewall-proof — keys off SRT's own control
  // traffic (ACK/KeepAlive arrive sub-second at any latency), never ICMP. Set to 0 to disable.
  @Volatile
  private var lastInboundMs = 0L
  private var inboundSilenceJob: Job? = null
  // Inbound-silence dead-link timeout. FIXED, not latency-scaled: the ingest server (millicast) drops
  // the publisher at a fixed ~5 s regardless of SRT latency, so ride-through past that is impossible —
  // scaling the timeout with latency only left the stream dead longer before we noticed. 5.5 s sits just
  // above the server's ~5 s drop (0.5 s margin): fast recovery at any latency, while still riding through
  // the brief sub-5 s blips the server holds. Checked on a dedicated 1 s tick (not the multi-second
  // readBuffer loop) so detection fires promptly regardless of the socket read timeout.
  private val inboundSilenceTimeoutMs = 5_500L
  private val inboundSilenceTickMs = 1_000L
  @Volatile
  var isStreaming = false
    private set
  private var url: String? = null
  private var doingRetry = false
  private var numRetry = 0
  private var reTries = 0

  val droppedAudioFrames: Long
    get() = srtSender.getDroppedAudioFrames()
  val droppedVideoFrames: Long
    get() = srtSender.getDroppedVideoFrames()

  val cacheSize: Int
    get() = srtSender.getCacheSize()
  val sentAudioFrames: Long
    get() = srtSender.getSentAudioFrames()
  val sentVideoFrames: Long
    get() = srtSender.getSentVideoFrames()
  val bytesSend: Long
    get() = srtSender.getBytesSend()
  var rtt = 0 //in micro
    private set
  var packetsLost = 0
    private set
  private var latency = 120_000 //in micro
  var socketType = SocketType.JAVA
  var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

  fun setVideoCodec(videoCodec: VideoCodec) {
    if (!isStreaming) {
      commandsManager.videoCodec = when (videoCodec) {
        VideoCodec.AV1 -> throw IllegalArgumentException("Unsupported codec: ${videoCodec.name}")
        else -> videoCodec
      }
    }
  }

  fun setAudioCodec(audioCodec: AudioCodec) {
    if (!isStreaming) {
      commandsManager.audioCodec = when (audioCodec) {
        AudioCodec.G711 -> throw IllegalArgumentException("Unsupported codec: ${audioCodec.name}")
        else -> audioCodec
      }
    }
  }

  fun setLatency(latency: Int) {
    this.latency = latency
  }

  fun setDelay(millis: Long) {
    srtSender.setDelay(millis)
  }

  /**
   * Set passphrase for encrypt. Use empty value to disable it.
   */
  fun setPassphrase(passphrase: String, type: EncryptionType) {
    if (!isStreaming) {
      if (passphrase.length !in 10..79) {
        throw IllegalArgumentException("passphrase must between 10 and 79 length")
      }
      commandsManager.setPassphrase(passphrase, type)
    }
  }

  /**
   * Must be called before connect
   */
  fun setOnlyAudio(onlyAudio: Boolean) {
    commandsManager.audioDisabled = false
    commandsManager.videoDisabled = onlyAudio
  }

  /**
   * Must be called before connect
   */
  fun setOnlyVideo(onlyVideo: Boolean) {
    commandsManager.videoDisabled = false
    commandsManager.audioDisabled = onlyVideo
  }

  /**
   * Check periodically if server is alive using Echo protocol.
   */
  fun setCheckServerAlive(enabled: Boolean) {
    checkServerAlive = enabled
  }

  fun setReTries(reTries: Int) {
    numRetry = reTries
    this.reTries = reTries
  }

  fun shouldRetry(reason: String): Boolean {
    val validReason = doingRetry && !reason.contains("Endpoint malformed")
    return validReason && reTries > 0
  }

  @JvmOverloads
  fun connect(url: String?, isRetry: Boolean = false) {
    if (!isRetry) doingRetry = true
    if (!isStreaming || isRetry) {
      isStreaming = true

      job = scope.launch {
        if (url == null) {
          isStreaming = false
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }
        this@SrtClient.url = url
        onMainThread {
          connectChecker.onConnectionStarted(url)
        }

        val urlParser = try {
          UrlParser.parse(url, validSchemes)
        } catch (_: URISyntaxException) {
          isStreaming = false
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }

        val host = urlParser.host
        val port = urlParser.port ?: 8888
        val path = urlParser.getQuery("streamid") ?: urlParser.getFullPath()
        latency = urlParser.getQuery("latency")?.toIntOrNull() ?: latency
        // Keep the socket read timeout coupled to the negotiated latency on EVERY (re)connect, derived
        // from the authoritative URL latency. The host app also sets this via setSocketTimeout, but that
        // runs once at configure time and goes stale across a latency change + reconnect; deriving it
        // here guarantees it always tracks the current latency. latency is micros, timeout is ms.
        socketTimeout = (latency / 1000L) + 1000L
        val passphrase = urlParser.getQuery("passphrase") ?: ""
        if (passphrase.isNotEmpty() && passphrase.length in 10..79) {
          val encryptionType = when (urlParser.getQuery("pbkeylen")?.toIntOrNull()) {
            192 -> EncryptionType.AES192
            256 -> EncryptionType.AES256
            else -> EncryptionType.AES128
          }
          commandsManager.setPassphrase(passphrase, encryptionType)
        }
        if (path.isEmpty()) {
          isStreaming = false
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }
        commandsManager.host = host

        val error = runCatching {
          // Open the handshake socket with a SHORT read timeout so a missed reply re-knocks on the
          // HANDSHAKE_RETRANSMIT_MS cadence instead of blocking the whole latency window on one packet.
          socket = SrtSocket(socketType, host, port, HANDSHAKE_RETRANSMIT_MS)
          socket?.connect()
          commandsManager.loadStartTs()

          // Total knock budget = the latency-derived socketTimeout the single block-read used to
          // consume. We now re-send within this window instead of waiting it all out on one packet.
          val handshakeDeadlineMs = System.currentTimeMillis() + socketTimeout

          // INDUCTION — re-knock until the server replies (or the budget runs out).
          val response = pollHandshake(handshakeDeadlineMs, "induction") {
            commandsManager.writeHandshake(socket)
          } ?: throw SocketTimeoutException("Poll timed out (no induction response in ${socketTimeout}ms)")

          val conclusion = response.copy(
            encryption = commandsManager.getEncryptType(),
            extensionField = ExtensionField.calculateValue(response.extensionField, commandsManager.encryptionEnabled()),
            handshakeType = HandshakeType.CONCLUSION,
            handshakeExtension = HandshakeExtension(
              flags = ExtensionContentFlag.TSBPDSND.value or ExtensionContentFlag.TSBPDRCV.value or
                  ExtensionContentFlag.CRYPT.value or ExtensionContentFlag.TLPKTDROP.value or
                  ExtensionContentFlag.PERIODICNAK.value or ExtensionContentFlag.REXMITFLG.value,
              receiverDelay = latency / 1000,
              senderDelay = latency / 1000,
              path = path,
              encryptInfo = commandsManager.getEncryptInfo()
            ))
          // CONCLUSION — re-knock until the matching CONCLUSION (or an error reject) arrives; stale
          // INDUCTION echoes buffered from earlier retransmits are skipped by pollHandshake.
          val responseConclusion = pollHandshake(handshakeDeadlineMs, "conclusion", HandshakeType.CONCLUSION) {
            commandsManager.writeHandshake(socket, conclusion)
          } ?: throw SocketTimeoutException("Poll timed out (no conclusion response in ${socketTimeout}ms)")
          if (responseConclusion.isErrorType()) {
            onMainThread {
              connectChecker.onConnectionFailed("Error configure stream, ${responseConclusion.handshakeType.name}")
            }
            return@launch
          } else {
            commandsManager.socketId = responseConclusion.srtSocketId
            commandsManager.MTU = responseConclusion.MTU
            commandsManager.sequenceNumber = responseConclusion.initialPacketSequence
            // Handshake established — restore the latency-derived read timeout so the streaming
            // read-loop poll cadence is unchanged from the validated recovery behavior.
            socket?.setReadTimeout(socketTimeout)
            onMainThread {
              connectChecker.onConnectionSuccess()
            }
            srtSender.socket = socket
            srtSender.start()
            lastInboundMs = System.currentTimeMillis()
            startInboundSilenceWatchdog()
            handleServerPackets()
          }
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

  /**
   * Send a handshake via [send] and poll for its reply, retransmitting every [HANDSHAKE_RETRANSMIT_MS]
   * (the socket's short read timeout) until a usable reply arrives or [deadlineMs] passes; returns
   * null on timeout. Fixes the single-shot handshake weakness: a dropped induction/conclusion packet
   * is re-sent within ~250ms, and a millicast stale-session hold latches the instant the server
   * releases it — instead of every miss costing the full latency window ("Poll timed out").
   *
   * [acceptType] gates which reply ends the loop: handshakes of other types (e.g. stale INDUCTION
   * echoes buffered from earlier retransmits during the conclusion phase) are skipped. Error-type
   * handshakes always end the loop so a server rejection surfaces. Pass null to accept the first
   * handshake of any type (induction phase).
   *
   * Re-send is time-based with exponential backoff (250 -> 500 -> 1000 -> [HANDSHAKE_RETRANSMIT_CAP_MS])
   * rather than once-per-read, so that a stray non-handshake datagram — a DataPacket from a millicast
   * stale session that hasn't fully released yet, which [CommandsManager.readHandshake] throws on — is
   * drained and skipped instead of aborting the whole connect, while the knock continues on its
   * backoff schedule. Riding the full window (instead of failing fast on one stray packet) lets a
   * single attempt catch a late server release.
   */
  private suspend fun pollHandshake(
    deadlineMs: Long,
    phase: String,
    acceptType: HandshakeType? = null,
    send: suspend () -> Unit,
  ): Handshake? {
    var lastSendMs = 0L
    var gapMs = HANDSHAKE_RETRANSMIT_MS
    while (scope.isActive && System.currentTimeMillis() < deadlineMs) {
      val now = System.currentTimeMillis()
      if (lastSendMs == 0L || now - lastSendMs >= gapMs) {
        send() // first knock, or re-knock once the (growing) backoff gap elapses
        if (lastSendMs != 0L) gapMs = (gapMs * 2).coerceAtMost(HANDSHAKE_RETRANSMIT_CAP_MS)
        lastSendMs = now
      }
      val handshake = try {
        commandsManager.readHandshake(socket)
      } catch (_: SocketTimeoutException) {
        continue // short read window elapsed with no reply — loop re-knocks on the backoff above
      } catch (e: IOException) {
        // A non-handshake datagram (stale-session DataPacket) landed mid-handshake; skip it and
        // keep polling rather than failing the connect. Re-raise anything that isn't that case.
        if (e.message?.contains("unexpected response type") != true) throw e
        Log.i(TAG, "skip non-handshake packet during $phase: ${e.message}")
        continue
      }
      if (acceptType == null || handshake.isErrorType() || handshake.handshakeType == acceptType) {
        return handshake
      }
      Log.i(TAG, "skip stale $phase handshake: ${handshake.handshakeType.name}")
    }
    return null
  }

  fun disconnect() {
    CoroutineScope(Dispatchers.IO).launch {
      disconnect(true)
    }
  }

  private suspend fun disconnect(clear: Boolean) {
    if (isStreaming) srtSender.stop(clear)
    runCatching {
      withTimeoutOrNull(100) {
        commandsManager.writeShutdown(socket)
      }
    }
    socket?.close()
    if (clear) {
      reTries = numRetry
      doingRetry = false
      isStreaming = false
      onMainThread {
        connectChecker.onDisconnect()
      }
      jobRetry?.cancelAndJoin()
      jobRetry = null
      scopeRetry.cancel()
      scopeRetry = CoroutineScope(Dispatchers.IO)
    }
    commandsManager.reset()
    rtt = 0
    packetsLost = 0
    job?.cancelAndJoin()
    job = null
    scope.cancel()
    scope = CoroutineScope(Dispatchers.IO)
  }

  fun reConnect(delay: Long) {
    reConnect(delay, null)
  }

  fun reConnect(delay: Long, backupUrl: String?) {
    jobRetry = scopeRetry.launch {
      reTries--
      disconnect(false)
      delay(delay)
      val reconnectUrl = backupUrl ?: url
      connect(reconnectUrl, true)
    }
  }

  @Throws(IOException::class)
  private suspend fun handleServerPackets() {
    while (scope.isActive && isStreaming) {
      val error = runCatching {
        if (isAlive()) {
          //ignore packet after connect if tunneled to avoid spam idle
          handleMessages()
        } else {
          onMainThread {
            connectChecker.onConnectionFailed("No response from server")
          }
          scope.cancel()
        }
      }.exceptionOrNull()
      if (error != null && ConnectionFailed.parse(error.validMessage()) != ConnectionFailed.TIMEOUT) {
        scope.cancel()
      }
    }
  }

  /**
   * Dedicated inbound-silence watchdog. Runs on a fixed [inboundSilenceTickMs] tick — independent of
   * the multi-second readBuffer block in [handleServerPackets] — so a dead link is caught within ~a
   * second of the silence window elapsing instead of waiting for the next socket read to wake. A silent
   * UDP blackhole keeps sendto() succeeding (bytesSend climbs) while the server stops ACKing; this is
   * the firewall-proof replacement for checkServerAlive's ICMP probe. Threshold scales with latency so
   * a high-latency config still rides through long outages via SRT's own buffer before giving up.
   */
  private fun startInboundSilenceWatchdog() {
    inboundSilenceJob?.cancel()
    inboundSilenceJob = scope.launch {
      while (isActive && isStreaming) {
        delay(inboundSilenceTickMs)
        if (lastInboundMs == 0L) continue
        val silentMs = System.currentTimeMillis() - lastInboundMs
        val silenceTimeoutMs = inboundSilenceTimeoutMs
        if (silentMs > silenceTimeoutMs) {
          onMainThread {
            connectChecker.onConnectionFailed("No response from server (inbound silence ${silentMs}ms > ${silenceTimeoutMs}ms)")
          }
          scope.cancel()
          break
        }
      }
    }
  }

  /*
  Send a heartbeat to know if server is alive using Echo Protocol.
  Your firewall could block it.
 */
  private fun isAlive(): Boolean {
    val connected = socket?.isConnected() ?: false
    if (!checkServerAlive) {
      return connected
    }
    val reachable = socket?.isReachable() ?: false
    return if (connected && !reachable) false else connected
  }

  @Throws(IOException::class)
  private suspend fun handleMessages() {
    val responseBufferConclusion = socket?.readBuffer() ?: throw IOException("read buffer failed, socket disconnected")
    // A packet was successfully read — the server is alive on the data plane. Reset the silence timer.
    lastInboundMs = System.currentTimeMillis()
    when(val srtPacket = SrtPacket.getSrtPacket(responseBufferConclusion)) {
      is DataPacket -> {
        //ignore
      }
      is ControlPacket -> {
        when (srtPacket) {
          is Handshake -> {
            //never should happens, handshake is already done
          }
          is KeepAlive -> {
            commandsManager.writeKeepAlive(socket)
          }
          is Ack -> {
            rtt = srtPacket.rtt
            val ackSequence = srtPacket.typeSpecificInformation
            val lastPacketSequence = srtPacket.lastAcknowledgedPacketSequenceNumber
            commandsManager.updateHandlingQueue(lastPacketSequence)
            commandsManager.writeAck2(ackSequence, socket)
          }
          is Nak -> {
            //packet lost reported, we should resend it
            val packetsLost = srtPacket.getNakPacketsLostList()
            this.packetsLost += packetsLost.size
            commandsManager.reSendPackets(packetsLost, socket)
          }
          is CongestionWarning -> {

          }
          is Shutdown -> {
            onMainThread {
              connectChecker.onConnectionFailed("Shutdown received from server")
            }
          }
          is Ack2 -> {
            //never should happens
          }
          is DropReq -> {

          }
          is PeerError -> {
            val reason = srtPacket.errorCode
            onMainThread {
              connectChecker.onConnectionFailed("PeerError: $reason")
            }
          }
        }
      }
    }
  }

  fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    srtSender.setAudioInfo(sampleRate, isStereo)
  }

  fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    Log.i(TAG, "send sps and pps")
    srtSender.setVideoInfo(sps, pps, vps)
  }

  fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.videoDisabled) {
      srtSender.sendMediaFrame(MediaFrame(videoBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.VIDEO))
    }
  }

  fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.audioDisabled) {
      srtSender.sendMediaFrame(MediaFrame(audioBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.AUDIO))
    }
  }

  @Throws(IllegalArgumentException::class)
  fun hasCongestion(): Boolean {
    return hasCongestion(20f)
  }

  @Throws(IllegalArgumentException::class)
  fun hasCongestion(percentUsed: Float): Boolean {
    return srtSender.hasCongestion(percentUsed)
  }

  fun resetSentAudioFrames() {
    srtSender.resetSentAudioFrames()
  }

  fun resetSentVideoFrames() {
    srtSender.resetSentVideoFrames()
  }

  fun resetDroppedAudioFrames() {
    srtSender.resetDroppedAudioFrames()
  }

  fun resetDroppedVideoFrames() {
    srtSender.resetDroppedVideoFrames()
  }

  fun resetBytesSend() {
    srtSender.resetBytesSend()
  }

  @Throws(RuntimeException::class)
  fun resizeCache(newSize: Int) {
    srtSender.resizeCache(newSize)
  }

  fun setLogs(enable: Boolean) {
    srtSender.setLogs(enable)
  }

  fun clearCache() {
    srtSender.clearCache()
  }

  fun getItemsInCache(): Int = srtSender.getItemsInCache()

  /**
   * Milliseconds since the last inbound packet was read from the socket, or -1 when not streaming or
   * not yet established. On a healthy link this stays near zero (SRT sends ACK/KeepAlive control
   * packets sub-second at any latency) and grows once the server stops responding — the firewall-proof
   * "is the server actually receiving" signal that an outbound bytes-sent counter cannot provide.
   */
  fun getInboundSilenceMs(): Long =
    if (!isStreaming || lastInboundMs == 0L) -1L else System.currentTimeMillis() - lastInboundMs

  /**
   * @param factor values from 0.1f to 1f
   * Set an exponential factor to the bitrate calculation to avoid bitrate spikes
   */
  fun setBitrateExponentialFactor(factor: Float) {
    srtSender.setBitrateExponentialFactor(factor)
  }

  /**
   * Get the exponential factor used to calculate the bitrate. Default 1f
   */
  fun getBitrateExponentialFactor() = srtSender.getBitrateExponentialFactor()

  /**
   * Set a custom Mpeg2TsService with specified parameters
   * Must be called before connect
   *
   * @param customService the custom Mpeg2TsService with desired parameters
   */
  fun setMpeg2TsService(customService: Mpeg2TsService) {
    if (!isStreaming) {
      srtSender.setMpeg2TsService(customService)
    } else {
      Log.w(TAG, "Can't set custom Mpeg2TsService while streaming")
    }
  }
}