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

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.util.streamclient.StreamClientListener
import java.nio.ByteBuffer

/**
 * GPX R33 — replaces both GenericStream and WhipStream as gpxstream-app's StreamBase
 * implementation. Those two subclasses each own a fixed set of protocol clients for the life of
 * the instance; SwitchableStream instead holds one [StreamTransport] behind a [Volatile]
 * delegate reference that [switchTransport] can replace, so a protocol change
 * (RTMP<->WHIP<->SRT) swaps only the network-transport half while every field StreamBase keeps
 * private — both video encoders, the audio encoder, the muxer/recordController, the GL plane,
 * the sources — stays alive by construction. Zero edits to StreamBase.kt.
 *
 * Deviation from the reviewed design: [initialTransport] and [switchTransport]'s factory close
 * over whatever ConnectChecker they need (see gpxstream-app's StreamBaseFactory.transportFor), so
 * this class itself takes and stores no ConnectChecker — GenericStream/WhipStream's constructor
 * shape carried one only because their clients were built inline, in this constructor's own body.
 *
 * Two invariants a caller must know:
 * - Frames landing on a disconnected transport drop, exactly as they do between stopStream and
 *   startStream today — a swap does not buffer or replay live encoder output across itself.
 * - Audio-codec priming is NOT replayed across a swap. Each transport sets its own canonical
 *   codec at construction; see [TransportPrimer]'s KDoc.
 */
class SwitchableStream(
  context: Context,
  videoSource: VideoSource,
  audioSource: AudioSource,
  initialTransport: (StreamClientListener) -> StreamTransport
) : StreamBase(context, videoSource, audioSource) {

  private val listener = object : StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyframe()
    }
  }

  @Volatile
  private var transport: StreamTransport = initialTransport(listener)

  @Volatile
  private var lastVideoCodec: VideoCodec? = null

  @Volatile
  private var lastVideoInfo: VideoInfoCache? = null

  @Volatile
  private var lastAudioInfo: AudioInfoCache? = null

  /**
   * Swap only the network-transport half. Throws if streaming: a swap mid-connection would
   * discard a transport whose disconnect() the caller never drove, leaving its socket resources
   * to finalizers instead of an orderly close. Callers (gpxstream-app's TRANSPORT reconfigure
   * tier) quiesce the publisher first, exactly as the STREAM_ENCODER tier quiesces before
   * [StreamBase.applyVideoStreamConfig] — this mirrors that shape on the transport axis.
   *
   * The new transport is primed with whatever codec/video-info/audio-info the discarded one was
   * last known to carry, via [TransportPrimer], so a caller does not have to re-derive and resend
   * them after every swap.
   */
  fun switchTransport(factory: (StreamClientListener) -> StreamTransport) {
    if (isStreaming) {
      throw IllegalStateException("stopStream before switching the transport")
    }
    val next = factory(listener)
    TransportPrimer.prime(next, lastVideoCodec, lastVideoInfo, lastAudioInfo)
    transport = next
  }

  override fun getStreamClient(): StreamBaseClient = transport.streamClient

  override fun setVideoCodecImp(codec: VideoCodec) {
    lastVideoCodec = codec
    transport.setVideoCodec(codec)
  }

  override fun setAudioCodecImp(codec: AudioCodec) {
    // Deliberately not cached for replay — see the class KDoc and TransportPrimer's KDoc.
    transport.setAudioCodec(codec)
  }

  override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
    lastAudioInfo = AudioInfoCache(sampleRate, isStereo)
    transport.setAudioInfo(sampleRate, isStereo)
  }

  override fun startStreamImp(endPoint: String) {
    transport.connect(endPoint, super.getVideoResolution(), super.getVideoFps())
  }

  override fun stopStreamImp() {
    transport.disconnect()
  }

  override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    // Cached copies are independent of the ones handed to the live transport below, so neither
    // consumer's buffer position affects the other's.
    lastVideoInfo = VideoInfoCache(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
    transport.setVideoInfo(sps, pps, vps)
  }

  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    transport.sendVideo(videoBuffer, info)
  }

  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    transport.sendAudio(audioBuffer, info)
  }
}
