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

  // Handshake retransmit backoff, in milliseconds, and also the socket read timeout during the
  // handshake, which sets the poll granularity.
  //
  // Sending each handshake once and block-reading the whole latency-derived socketTimeout makes one
  // lost UDP packet cost the entire window, which surfaces as "Poll timed out". Re-knocking fixes
  // that, and the gap grows rather than staying fixed because two failure modes pull opposite ways.
  // A cold lost packet wants a fast re-knock, within about 250 ms. A server holding a prior session
  // after a relaunch releases it during a lull: continuous 250 ms knocking was observed riding an
  // 11 s window with no response, and the knock that latched was the one after a multi-second
  // silent gap. Growing the gap covers the lost packet early and provides the quiet windows later.
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

  // Wall-clock time the last packet was read from the socket. A silent UDP blackhole keeps sendto()
  // succeeding, so the outbound byte counter keeps climbing while the server stops responding and
  // nothing reports a failure. This reads SRT's own control traffic (ACK and KeepAlive arrive
  // sub-second at any latency) rather than ICMP, so a firewall that drops ICMP does not blind it.
  @Volatile
  private var lastInboundMs = 0L
  private var inboundSilenceJob: Job? = null

  // Inbound-silence dead-link timeout. Fixed rather than scaled with latency: an ingest server that
  // drops the publisher at a fixed interval cannot be ridden through by waiting longer, and scaling
  // with latency only delays detection. 5,500 ms sits above an observed 5 s server-side drop, which
  // still rides through shorter blips. Checked on its own 1 s tick rather than inside the
  // multi-second readBuffer loop, so detection does not wait for the next socket read to wake.
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
  var socketType = SocketType.JAVA
  var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

  fun setVideoCodec(videoCodec: VideoCodec) {
    if (!isStreaming) {
      commandsManager.videoCodec = when (videoCodec) {
        VideoCodec.H264, VideoCodec.H265 -> videoCodec
        else -> throw IllegalArgumentException("Unsupported codec: ${videoCodec.name}")
      }
    }
  }

  fun setAudioCodec(audioCodec: AudioCodec) {
    if (!isStreaming) {
      commandsManager.audioCodec = when (audioCodec) {
        AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.HE_AAC -> audioCodec
        else -> throw IllegalArgumentException("Unsupported codec: ${audioCodec.name}")
      }
    }
  }

  fun setLatency(latency: Int) {
    commandsManager.latency = latency
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
        // getFullPath(), not path: it keeps the query string attached, which is what a Millicast SRT
        // ingest expects as the streamid when the publishing token rides in the URL as "?t=".
        val path = urlParser.getQuery("streamid") ?: urlParser.getFullPath()
        commandsManager.latency = urlParser.getQuery("latency")?.toIntOrNull() ?: commandsManager.latency
        // Re-derive the socket read timeout from the URL latency on every connect. A host app that
        // sets socketTimeout once at configure time leaves it stale after a latency change plus a
        // reconnect. latency is microseconds, socketTimeout is milliseconds.
        socketTimeout = (commandsManager.latency / 1000L) + 1000L
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
          // Short read timeout during the handshake so a missed reply re-knocks on the retransmit
          // cadence instead of blocking the whole latency window on one packet.
          socket = SrtSocket(socketType, host, port, HANDSHAKE_RETRANSMIT_MS)
          socket?.connect()
          commandsManager.loadStartTs()

          // Total knock budget equals the latency-derived socketTimeout that the single block-read
          // used to consume; the retransmits happen inside that same window.
          val handshakeDeadlineMs = System.currentTimeMillis() + socketTimeout

          val response = pollHandshake(handshakeDeadlineMs, "induction") {
            commandsManager.writeHandshake(socket)
          } ?: throw SocketTimeoutException("Poll timed out (no induction response in ${socketTimeout}ms)")

          val conclusion = response.copy(
            encryption = commandsManager.getEncryptType(),
            extensionField = ExtensionField.calculateValue(response.extensionField, commandsManager.encryptionEnabled(), path.isNotEmpty()),
            handshakeType = HandshakeType.CONCLUSION,
            handshakeExtension = HandshakeExtension(
              flags = ExtensionContentFlag.TSBPDSND.value or ExtensionContentFlag.TSBPDRCV.value or
                  ExtensionContentFlag.CRYPT.value or ExtensionContentFlag.TLPKTDROP.value or
                  ExtensionContentFlag.PERIODICNAK.value or ExtensionContentFlag.REXMITFLG.value,
              receiverDelay = commandsManager.latency,
              senderDelay = commandsManager.latency,
              path = path,
              encryptInfo = commandsManager.getEncryptInfo()
            ))
          // Accept only CONCLUSION here, so an INDUCTION echo buffered from an earlier retransmit is
          // skipped rather than mistaken for the reply.
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
            // Handshake done: restore the latency-derived read timeout for the streaming read loop.
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
   * Send a handshake through [send] and poll for its reply, retransmitting on a growing gap until a
   * usable reply arrives or [deadlineMs] passes.
   *
   * [acceptType] gates which reply ends the loop; handshakes of other types are skipped, so an
   * INDUCTION echo left in the socket buffer by an earlier retransmit does not end the conclusion
   * phase. An error-type handshake always ends the loop so a server rejection reaches the caller.
   * Pass null to accept the first handshake of any type.
   *
   * Re-sending is driven by elapsed time rather than by each read returning, so a stray
   * non-handshake datagram — which [CommandsManager.readHandshake] throws on — is drained and
   * skipped while the knock stays on its own schedule, instead of aborting the connect.
   *
   * @return the accepted handshake, or null if the deadline passed first
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
        send()
        if (lastSendMs != 0L) gapMs = (gapMs * 2).coerceAtMost(HANDSHAKE_RETRANSMIT_CAP_MS)
        lastSendMs = now
      }
      val handshake = try {
        commandsManager.readHandshake(socket)
      } catch (_: SocketTimeoutException) {
        continue // read window elapsed with no reply; the loop re-knocks once the gap has passed
      } catch (e: IOException) {
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

  /**
   * Report the link dead once no packet has been read for [inboundSilenceTimeoutMs].
   *
   * Runs on its own [inboundSilenceTickMs] tick rather than inside the readBuffer loop, whose block
   * can last multiple seconds, so the report does not wait for the next socket read to wake. This
   * replaces the ICMP probe in [checkServerAlive] for the blackhole case, where sendto() keeps
   * succeeding and the outbound counter keeps climbing while nothing comes back.
   */
  private fun startInboundSilenceWatchdog() {
    inboundSilenceJob?.cancel()
    inboundSilenceJob = scope.launch {
      while (isActive && isStreaming) {
        delay(inboundSilenceTickMs)
        if (lastInboundMs == 0L) continue
        val silentMs = System.currentTimeMillis() - lastInboundMs
        if (silentMs > inboundSilenceTimeoutMs) {
          onMainThread {
            connectChecker.onConnectionFailed("No response from server (inbound silence ${silentMs}ms > ${inboundSilenceTimeoutMs}ms)")
          }
          scope.cancel()
          break
        }
      }
    }
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
    // A packet arrived, so the server is responding. Reset the silence timer.
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
            val ackSequence = srtPacket.typeSpecificInformation
            val lastPacketSequence = srtPacket.lastAcknowledgedPacketSequenceNumber
            commandsManager.updateHandlingQueue(lastPacketSequence)
            if (ackSequence != 0) {
              rtt = srtPacket.rtt
              commandsManager.writeAck2(ackSequence, socket)
            }
          }
          is Nak -> {
            //packet lost reported, we should resend it
            val lostRanges = srtPacket.getNakRanges()
            this.packetsLost += srtPacket.getLostCount()
            commandsManager.reSendPackets(lostRanges, socket)
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
   * not yet established. On a responding link this stays near zero, because SRT sends ACK and
   * KeepAlive control packets sub-second at any latency, and it grows once the server stops
   * responding. An outbound bytes-sent counter cannot show this, since sendto() keeps succeeding
   * into a blackhole.
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