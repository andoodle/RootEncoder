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
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.library.util.streamclient.WhipStreamClient
import com.pedro.whip.WhipClient
import java.nio.ByteBuffer

/**
 * GPX R33 — mechanical port of WhipStream's body into a [StreamTransport]. One stable
 * [WhipStreamClient] is created here at construction — unlike WhipStream.getStreamClient(),
 * which builds a fresh wrapper on every call — because [SwitchableStream] hands its
 * `getStreamClient()` caller a reference that must keep working for the lifetime of this
 * transport instance, not just for one call.
 *
 * The protocol's canonical audio codec (OPUS) is set on the client here, deterministically, at
 * construction, mirroring WhipStream's own constructor-time `setAudioCodec(OPUS)` call.
 *
 * [connect] ignores [resolution] and [fps] — WHIP negotiates them through SDP, not a
 * resolution/fps push (see [StreamTransport.connect]'s KDoc).
 */
class WhipTransport(
  connectChecker: ConnectChecker,
  listener: StreamClientListener
) : StreamTransport {

  private val whipClient = WhipClient(connectChecker)

  override val streamClient: WhipStreamClient = WhipStreamClient(whipClient, listener)

  init {
    whipClient.setAudioCodec(AudioCodec.OPUS)
  }

  override fun setVideoCodec(codec: VideoCodec) {
    whipClient.setVideoCodec(codec)
  }

  override fun setAudioCodec(codec: AudioCodec) {
    whipClient.setAudioCodec(codec)
  }

  override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    whipClient.setVideoInfo(sps, pps, vps)
  }

  override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    whipClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun connect(endPoint: String, resolution: Size, fps: Int) {
    whipClient.connect(endPoint)
  }

  override fun disconnect() {
    whipClient.disconnect()
  }

  override fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    whipClient.sendVideo(videoBuffer, info)
  }

  override fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    whipClient.sendAudio(audioBuffer, info)
  }
}
