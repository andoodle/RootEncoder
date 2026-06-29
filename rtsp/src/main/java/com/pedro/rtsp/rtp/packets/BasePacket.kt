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

package com.pedro.rtsp.rtp.packets

import com.pedro.common.frame.MediaFrame
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.utils.CryptoProperties
import com.pedro.rtsp.utils.CryptoUtils
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.rtsp.utils.setLong
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Created by pedro on 27/11/18.
 */
abstract class BasePacket(private var clock: Long, private val payloadType: Int) {

  protected var channelIdentifier: Int = 0
  private var seq = 0L
  private var ssrc = 0L
  // var (was val) so the WebRTC/WHIP path can shrink it for forwarding headroom. RTSP keeps MTU-28.
  protected var maxPacketSize = RtpConstants.MTU - 28
  protected var cryptoUtils: CryptoUtils? = null
  private var roc = 0
  protected val TAG = "BasePacket"

  // GPX patch: optional RTP one-byte header extensions (RFC 5285), used ONLY by the WebRTC/WHIP path.
  // Disabled by default, so RTSP/RTMP packets are byte-identical (rtpHeaderSize() == RTP_HEADER_LENGTH).
  // When enabled, every packet carries transport-wide-cc (+ MID) — Medooze (Millicast/Dolby) withholds
  // video forwarding from a publisher that never sends transport-cc, even though ingest looks healthy.
  // transportSeq is a single counter SHARED across all tracks on the transport (audio + video).
  private var extTransportCcId = 0            // 1..14 enables; 0 = off
  private var extMidId = 0                     // 1..14 enables; 0 = off
  private var extMid = ""
  private var extTransportSeq: java.util.concurrent.atomic.AtomicInteger? = null
  private var extBytes = 0                     // total extension-block size in bytes (0 when off)

  fun enableRtpExtensions(
    transportCcId: Int,
    midId: Int,
    mid: String,
    transportSeq: java.util.concurrent.atomic.AtomicInteger
  ) {
    extTransportCcId = transportCcId
    extMidId = midId
    extMid = mid
    extTransportSeq = transportSeq
    var dataLen = 0
    if (transportCcId in 1..14) dataLen += 1 + 2                 // 1-byte hdr + 16-bit seq
    if (midId in 1..14 && mid.isNotEmpty()) dataLen += 1 + mid.length
    extBytes = if (dataLen == 0) 0 else 4 + ((dataLen + 3) / 4) * 4 // 0xBEDE + len(2) + padded data
    // GPX patch: WebRTC publishers cap RTP packets ~1200 B so an SFU has room to add RTX + its own
    // header extensions when forwarding without exceeding path MTU. Our 1472 left no headroom, so the
    // Medooze->viewer leg fragmented + dropped every keyframe packet (NACK storm, 0 frames assembled).
    maxPacketSize = 1200
  }

  // Offset where the RTP payload begins: fixed 12-byte header plus any header-extension block.
  protected fun rtpHeaderSize() = RtpConstants.RTP_HEADER_LENGTH + extBytes

  fun setCryptoProperties(cryptoProperties: CryptoProperties) {
    cryptoUtils = CryptoUtils(cryptoProperties)
  }

  abstract suspend fun createAndSendPacket(
    mediaFrame: MediaFrame,
    callback: suspend (List<RtpFrame>) -> Unit
  )

  open fun reset() {
    seq = 0
    ssrc = 0
  }

  fun setSSRC(ssrc: Long) {
    this.ssrc = ssrc
  }

  protected fun setClock(clock: Long) {
    this.clock = clock
  }

  protected fun getBuffer(size: Int): ByteArray {
    val buffer = ByteArray(size)
    buffer[0] = 0x80.toByte()
    buffer[1] = payloadType.toByte()
    setLongSSRC(buffer, ssrc)
    requestBuffer(buffer)
    if (extBytes > 0) writeExtensions(buffer)
    return buffer
  }

  // GPX patch: write the RFC 5285 one-byte header-extension block at offset 12 and set the RTP X bit.
  // transport-wide-cc carries the next shared 16-bit sequence number; MID carries the m-line id. The
  // SDP offer declares these with matching extmap ids (transport-cc=4, mid=9), so the SFU maps them.
  private fun writeExtensions(buffer: ByteArray) {
    buffer[0] = (buffer[0].toInt() or 0x10).toByte() // X = 1
    val start = RtpConstants.RTP_HEADER_LENGTH
    buffer[start] = 0xBE.toByte()
    buffer[start + 1] = 0xDE.toByte()
    val words = (extBytes - 4) / 4
    buffer[start + 2] = ((words shr 8) and 0xFF).toByte()
    buffer[start + 3] = (words and 0xFF).toByte()
    var p = start + 4
    if (extTransportCcId in 1..14) {
      val s = (extTransportSeq?.getAndIncrement() ?: 0) and 0xFFFF
      buffer[p] = ((extTransportCcId shl 4) or 0x01).toByte() // len-1 = 1 (2 data bytes)
      buffer[p + 1] = ((s shr 8) and 0xFF).toByte()
      buffer[p + 2] = (s and 0xFF).toByte()
      p += 3
    }
    if (extMidId in 1..14 && extMid.isNotEmpty()) {
      buffer[p] = ((extMidId shl 4) or ((extMid.length - 1) and 0x0F)).toByte()
      for (i in extMid.indices) buffer[p + 1 + i] = extMid[i].code.toByte()
      p += 1 + extMid.length
    }
    // remaining bytes up to extBytes stay 0 (RFC 5285 padding)
  }

  protected fun updateTimeStamp(buffer: ByteArray, timestamp: Long): Long {
    val ts = timestamp * clock / 1000000000L
    buffer.setLong(ts, 4, 8)
    return ts
  }

  protected fun updateSeq(buffer: ByteArray) {
    val currentSeq = ++seq
    buffer.setLong(currentSeq, 2, 4)
    roc = (currentSeq / (RtpConstants.MAX_SEQ_NUMBER + 1)).toInt()
  }

  protected fun markPacket(buffer: ByteArray) {
    buffer[1] = buffer[1] or 0x80.toByte()
  }

  protected fun encryptSize() = if (cryptoUtils != null) RtpConstants.HMAC_SIZE else 0

  protected fun encryptPacket(buffer: ByteArray) {
    cryptoUtils?.let {
      // SRTP encrypts only the RTP payload (after the header AND any header extensions); the auth tag
      // covers the whole packet. rtpHeaderSize() == RTP_HEADER_LENGTH unless WHIP extensions are on.
      val payloadEndOffset = buffer.size - encryptSize()
      val payload = buffer.copyOfRange(rtpHeaderSize(), payloadEndOffset)
      it.encrypt(payload, getIvData(it)).copyInto(buffer, rtpHeaderSize())
      val hmac = it.calculateHmac(buffer.copyOfRange(0, payloadEndOffset), roc)
      hmac.copyInto(buffer, payloadEndOffset)
    }
  }

  private fun setLongSSRC(buffer: ByteArray, ssrc: Long) {
    buffer.setLong(ssrc, 8, 12)
  }

  private fun requestBuffer(buffer: ByteArray) {
    buffer[1] = buffer[1] and 0x7F
  }

  private fun getIvData(cryptoUtils: CryptoUtils): ByteArray {
    val index = ((roc shl 16) or (seq.toInt() and 0xFFFF))
    return cryptoUtils.generateIv(ssrc, index.toLong())
  }
}