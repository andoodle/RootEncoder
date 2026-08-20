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

package com.pedro.library.transport

import android.media.MediaCodec
import android.util.Size
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.library.generic.ClientType
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.RtspStreamClient
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.library.util.streamclient.UdpStreamClient
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.srt.srt.SrtClient
import com.pedro.udp.UdpClient
import java.nio.ByteBuffer

/**
 * GPX R33 — mechanical port of GenericStream's client bodies into a [StreamTransport]:
 * the same four clients, the same connectedType state machine, the same unsupported-protocol
 * failure text (GPX R16b).
 *
 * [listener] is wired to every client at construction, same as GenericStream's own
 * `streamClientListener` — so after a swap the fresh transport still drives keyframe requests on
 * retry/reconnect through [SwitchableStream]'s one stable listener instance.
 *
 * The protocol's canonical audio codec (AAC) is set on all four clients here, deterministically,
 * at construction — a transport never depends on a caller applying it separately.
 */
class GenericTransport(
  private val connectChecker: ConnectChecker,
  listener: StreamClientListener
) : StreamTransport {

  private val rtmpClient = RtmpClient(connectChecker)
  private val rtspClient = RtspClient(connectChecker)
  private val srtClient = SrtClient(connectChecker)
  private val udpClient = UdpClient(connectChecker)
  private var connectedType = ClientType.NONE

  override val streamClient: GenericStreamClient = GenericStreamClient(
    RtmpStreamClient(rtmpClient, listener),
    RtspStreamClient(rtspClient, listener),
    SrtStreamClient(srtClient, listener),
    UdpStreamClient(udpClient, listener)
  )

  init {
    rtmpClient.setAudioCodec(AudioCodec.AAC)
    rtspClient.setAudioCodec(AudioCodec.AAC)
    srtClient.setAudioCodec(AudioCodec.AAC)
    udpClient.setAudioCodec(AudioCodec.AAC)
  }

  override fun setVideoCodec(codec: VideoCodec) {
    if (codec != VideoCodec.H264 && codec != VideoCodec.H265) {
      throw IllegalArgumentException("Unsupported codec: ${codec.name}. Generic only support video ${VideoCodec.H264.name} and ${VideoCodec.H265.name}")
    }
    rtmpClient.setVideoCodec(codec)
    rtspClient.setVideoCodec(codec)
    srtClient.setVideoCodec(codec)
    udpClient.setVideoCodec(codec)
  }

  override fun setAudioCodec(codec: AudioCodec) {
    if (codec != AudioCodec.AAC) {
      throw IllegalArgumentException("Unsupported codec: ${codec.name}. Generic only support audio ${AudioCodec.AAC.name}")
    }
    rtmpClient.setAudioCodec(codec)
    rtspClient.setAudioCodec(codec)
    srtClient.setAudioCodec(codec)
    udpClient.setAudioCodec(codec)
  }

  override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    rtmpClient.setVideoInfo(sps, pps, vps)
    rtspClient.setVideoInfo(sps, pps, vps)
    srtClient.setVideoInfo(sps, pps, vps)
    udpClient.setVideoInfo(sps, pps, vps)
  }

  override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    rtmpClient.setAudioInfo(sampleRate, isStereo)
    rtspClient.setAudioInfo(sampleRate, isStereo)
    srtClient.setAudioInfo(sampleRate, isStereo)
    udpClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun connect(endPoint: String, resolution: Size, fps: Int) {
    streamClient.connecting(endPoint)
    if (endPoint.startsWith("rtmp", ignoreCase = true)) {
      connectedType = ClientType.RTMP
      rtmpClient.setVideoResolution(resolution.width, resolution.height)
      rtmpClient.setFps(fps)
      rtmpClient.connect(endPoint)
    } else if (endPoint.startsWith("rtsp", ignoreCase = true)) {
      connectedType = ClientType.RTSP
      rtspClient.connect(endPoint)
    } else if (endPoint.startsWith("srt", ignoreCase = true)) {
      connectedType = ClientType.SRT
      srtClient.connect(endPoint)
    } else if (endPoint.startsWith("udp", ignoreCase = true)) {
      connectedType = ClientType.UDP
      udpClient.connect(endPoint)
    } else {
      onMainThreadHandler {
        // GPX R16b — include the endpoint: without it a misconfigured URL reports the same text
        // was, and the scheme that failed is exactly what the caller needs to see.
        connectChecker.onConnectionFailed("Unsupported protocol. Only support rtmp, rtsp and srt not $endPoint")
      }
    }
  }

  override fun disconnect() {
    when (connectedType) {
      ClientType.RTMP -> rtmpClient.disconnect()
      ClientType.RTSP -> rtspClient.disconnect()
      ClientType.SRT -> srtClient.disconnect()
      ClientType.UDP -> udpClient.disconnect()
      else -> {}
    }
    connectedType = ClientType.NONE
  }

  override fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    when (connectedType) {
      ClientType.RTMP -> rtmpClient.sendVideo(videoBuffer, info)
      ClientType.RTSP -> rtspClient.sendVideo(videoBuffer, info)
      ClientType.SRT -> srtClient.sendVideo(videoBuffer, info)
      ClientType.UDP -> udpClient.sendVideo(videoBuffer, info)
      else -> {}
    }
  }

  override fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    when (connectedType) {
      ClientType.RTMP -> rtmpClient.sendAudio(audioBuffer, info)
      ClientType.RTSP -> rtspClient.sendAudio(audioBuffer, info)
      ClientType.SRT -> srtClient.sendAudio(audioBuffer, info)
      ClientType.UDP -> udpClient.sendAudio(audioBuffer, info)
      else -> {}
    }
  }
}
