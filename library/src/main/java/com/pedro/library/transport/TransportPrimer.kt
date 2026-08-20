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

import com.pedro.common.VideoCodec
import java.nio.ByteBuffer

/** GPX R33 — a [SwitchableStream] video-info cache entry, independent of any transport's buffer. */
internal data class VideoInfoCache(val sps: ByteBuffer, val pps: ByteBuffer?, val vps: ByteBuffer?)

/** GPX R33 — a [SwitchableStream] audio-info cache entry. */
internal data class AudioInfoCache(val sampleRate: Int, val isStereo: Boolean)

/**
 * GPX R33 — replays a [SwitchableStream]'s cached codec/video-info/audio-info onto a freshly
 * constructed [StreamTransport] after [SwitchableStream.switchTransport]. Extracted as a pure
 * function (no StreamBase/Android dependency) so the replay order and the fresh-duplicate
 * invariant below are unit-testable on the plain JVM against a fake [StreamTransport].
 *
 * Two invariants:
 * - Replay order is codec, then video info, then audio info — the same order a cold start
 *   establishes them (setVideoCodec/setAudioCodec before the encoder's first onVideoInfo /
 *   onAudioInfo callback).
 * - Every call duplicates [videoInfo]'s buffers fresh. A second prime from the same cache (a
 *   second swap before either encoder produced new info) must get an independently positioned,
 *   fully-remaining buffer — not one a previous prime already drained by handing it straight to
 *   a position-consuming client.
 *
 * Deliberately does NOT replay an audio codec: each [StreamTransport] sets its own canonical
 * audio codec at construction (GenericTransport -> AAC, WhipTransport -> OPUS), and priming one
 * transport's codec choice onto another would fight that.
 */
internal object TransportPrimer {

  fun prime(
    transport: StreamTransport,
    videoCodec: VideoCodec?,
    videoInfo: VideoInfoCache?,
    audioInfo: AudioInfoCache?
  ) {
    videoCodec?.let { transport.setVideoCodec(it) }
    videoInfo?.let {
      transport.setVideoInfo(it.sps.duplicate(), it.pps?.duplicate(), it.vps?.duplicate())
    }
    audioInfo?.let { transport.setAudioInfo(it.sampleRate, it.isStereo) }
  }
}
