/*
 * Copyright (C) 2026 GPX Stream.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.pedro.library.util

/**
 * Timing for one encoded frame from the stream video encoder.
 *
 * [presentationTimeUs] is the timestamp carried by the encoded frame. For surface input,
 * [sourceElapsedRealtimeNs] is the unre-based camera/GL timestamp in Android's elapsed-realtime
 * clock domain. [encodedElapsedRealtimeNs] is sampled when MediaCodec delivers the output buffer,
 * so their difference measures camera/GL/codec pipeline latency without using wall clock time.
 */
data class StreamVideoFrameTiming(
  val presentationTimeUs: Long,
  val sourceElapsedRealtimeNs: Long?,
  val encodedElapsedRealtimeNs: Long,
  val flags: Int,
)

fun interface StreamVideoFrameTimingListener {
  /** Runs on the MediaCodec callback thread; implementations must remain non-blocking. */
  fun onFrame(timing: StreamVideoFrameTiming)
}
