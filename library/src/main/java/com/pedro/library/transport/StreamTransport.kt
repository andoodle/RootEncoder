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
import com.pedro.common.VideoCodec
import com.pedro.library.util.streamclient.StreamBaseClient
import java.nio.ByteBuffer

/**
 * GPX R33 — the network-transport half of a stream, factored out of StreamBase's protocol
 * subclasses (GenericStream, WhipStream) so [SwitchableStream] can swap it independently of the
 * encoders, muxer, GL plane and sources StreamBase keeps private. An implementation is a
 * mechanical port of what GenericStream/WhipStream already did with their clients directly —
 * nothing here changes protocol behavior.
 */
interface StreamTransport {

  /** The surface the app drives directly: setAuthorization, setOnlyVideo, retry, stats. */
  val streamClient: StreamBaseClient

  fun setVideoCodec(codec: VideoCodec)
  fun setAudioCodec(codec: AudioCodec)
  fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
  fun setAudioInfo(sampleRate: Int, isStereo: Boolean)

  /**
   * [resolution] and [fps] exist only for the RTMP branch: GenericStream.startStreamImp reads
   * StreamBase's protected getVideoResolution()/getVideoFps() before connecting, which a
   * transport held outside StreamBase cannot reach on its own. [SwitchableStream], which IS a
   * StreamBase, passes them through. Transports that do not need them (WhipTransport) ignore
   * them.
   */
  fun connect(endPoint: String, resolution: Size, fps: Int)
  fun disconnect()

  fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
  fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
}
