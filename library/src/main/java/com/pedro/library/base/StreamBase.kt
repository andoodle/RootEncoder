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

package com.pedro.library.base

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.TimeUtils
import com.pedro.common.VideoCodec
import com.pedro.common.tryClear
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.Frame
import com.pedro.encoder.TimestampMode
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.AndroidMuxerRecordController
import com.pedro.library.util.FpsListener
import com.pedro.library.util.PreviewCallback
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.view.preview.MultiPreviewConfig
import java.nio.ByteBuffer
import kotlin.math.max


/**
 * Created by pedro on 21/2/22.
 *
 * Allow:
 * - video source camera1, camera2 or screen.
 * - audio source microphone or internal.
 * - Rotation on realtime.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
abstract class StreamBase(
    context: Context,
    vSource: VideoSource,
    aSource: AudioSource
) {

  private val getMicrophoneData = object: GetMicrophoneData {
    override fun inputPCMData(frame: Frame) {
      audioEncoder.inputPCMData(frame)
    }
  }
  //video and audio encoders
  private val videoEncoder by lazy { VideoEncoder(getVideoData) }
  private val videoEncoderRecord by lazy { VideoEncoder(getVideoDataRecord) }
  private val audioEncoder by lazy { AudioEncoder(getAacData) }
  //video render
  private val glInterface = GlStreamInterface(context)
  //video/audio record
  private var recordController: RecordController = AndroidMuxerRecordController()
  private val fpsListener = FpsListener()
  private var lastVideoFormat: MediaFormat? = null
  private var lastAudioFormat: MediaFormat? = null

  /**
   * GPX fork patch: the STREAM encoder's negotiated MediaFormat.
   *
   * Deliberately separate from [lastVideoFormat], which means "the format of whichever encoder feeds
   * recording" and is replayed into a newly installed controller by [setRecordController]. Writing
   * the stream encoder's format into that field when the record encoder is the one recording would
   * hand a record controller the wrong SPS/PPS for the bitstream it is muxing.
   *
   * Volatile: written on the MediaCodec async-callback HandlerThread, read from app threads.
   */
  @Volatile
  private var lastStreamVideoFormat: MediaFormat? = null

  /** GPX fork patch: see [setStreamVideoFormatListener]. Volatile for the same reason as above. */
  @Volatile
  private var streamVideoFormatListener: ((MediaFormat) -> Unit)? = null

  /**
   * GPX fork patch: the codec the RECORD encoder was last successfully prepared with, as observed
   * here. Null means "not known to be prepared with anything".
   *
   * A fact, never an intention. [setVideoRecCodec] advances `videoEncoderRecord.type` without
   * preparing anything, and deliberately does NOT touch this — because it does not change what the
   * encoder *is*, which is all this field means.
   *
   * Only a site that applies a codec TOGETHER WITH a profile/level derived for that codec may set
   * it: [prepareVideo]'s record branch and [applyVideoRecCodec]. Every other site that prepares the
   * record encoder is a REPLAY ([prepareEncoders], [resetVideoEncoder]) — it reuses whatever type
   * and stored pair are on the encoder, so it can move the codec without moving the pair, and may
   * only leave-or-clear. A replay that silently set this would let an encoder running H265 with
   * H264-namespace profile/level be treated as correctly configured, reinstating exactly the
   * cross-namespace bug 2.7.5-gpx23 was cut to fix.
   *
   * Not sufficient on its own: it can outlive the encoder it describes, since stop() clears
   * `prepared` without touching this. Every consumer must also check
   * `videoEncoderRecord.isPrepared()`.
   *
   * Volatile: written on the configure thread, read and written on the app's recording-driver
   * thread.
   */
  @Volatile
  private var recordCodecPrepared: VideoCodec? = null

  /** GPX fork patch: the MIME the record encoder currently holds, as a codec. */
  private fun recordEncoderCodec(): VideoCodec? = when (videoEncoderRecord.type) {
    CodecUtil.H264_MIME -> VideoCodec.H264
    CodecUtil.H265_MIME -> VideoCodec.H265
    CodecUtil.AV1_MIME -> VideoCodec.AV1
    else -> null
  }

  /**
   * GPX fork patch: a replay just re-prepared the record encoder from its stored fields. Keep
   * [recordCodecPrepared] only if the replayed MIME still matches it; otherwise the encoder moved to
   * a codec whose profile/level were never derived for it, so drop the claim and force the next
   * caller to re-prepare properly. Never sets — see [recordCodecPrepared].
   */
  private fun reconcileRecordCodecAfterReplay(replaySucceeded: Boolean) {
    if (!replaySucceeded || recordEncoderCodec() != recordCodecPrepared) {
      recordCodecPrepared = null
    }
  }
  var isStreaming = false
    private set
  var isOnPreview = false
    private set
  val isRecording: Boolean
    get() = recordController.isRunning()
  var videoSource: VideoSource = vSource
    private set
  var audioSource: AudioSource = aSource
    private set
  private var differentRecordResolution = false
  private val previewCallback = PreviewCallback(
    onCreated = { surface, width, height -> if (!isOnPreview) startPreview(surface, width, height) },
    onChanged = { width, height -> getGlInterface().setPreviewResolution(width, height) },
    onDestroyed = { if (isOnPreview) stopPreview(true) }
  )

  /**
   * Necessary only one time before start preview, stream or record.
   * If you want change values stop preview, stream and record is necessary.
   *
   * @param profile codec value from MediaCodecInfo.CodecProfileLevel class
   * @param level codec value from MediaCodecInfo.CodecProfileLevel class
   * @param recordProfile GPX fork patch: profile for the RECORD encoder, which may run a different
   * codec than the stream encoder (its MIME is set independently via setVideoRecCodec). The
   * MediaCodecInfo.CodecProfileLevel constants are codec-namespaced — 1 is AVCProfileBaseline for
   * H264 but HEVCProfileMain for H265, and 2048 is AVCLevel4 but HEVCHighTierLevel4 — so a single
   * pair cannot be correct for both encoders whenever the two codecs differ, which for the GPX app
   * (stream H264 + record H265) is the default rather than an edge case. Defaults to [profile] so
   * every existing caller keeps today's shared-pair behaviour exactly.
   * @param recordLevel GPX fork patch: level for the RECORD encoder. See [recordProfile]; defaults
   * to [level] for the same reason.
   * @param forceRecordVbr GPX fork patch: when the record encoder is prepared at its own resolution
   * (recordWidth/recordHeight set), force it into VBR bitrate mode instead of the device's default
   * (CBR when supported). Independent of the resolution-difference check itself — callers decide
   * this explicitly rather than getting it as a side effect of using a different record resolution.
   *
   * @throws IllegalArgumentException if current video parameters are not supported by the VideoSource
   * @throws IllegalArgumentException if you use differentRecordResolution but the aspect ratio is not the same than stream resolution
   * @return True if success, False if failed
   */
  @Throws(IllegalArgumentException::class)
  @JvmOverloads
  fun prepareVideo(
    width: Int, height: Int, bitrate: Int, fps: Int = 30, iFrameInterval: Int = 2,
    rotation: Int = 0, profile: Int = -1, level: Int = -1,
    recordWidth: Int = 0, recordHeight: Int = 0, recordBitrate: Int = bitrate,
    forceRecordVbr: Boolean = false,
    recordProfile: Int = profile, recordLevel: Int = level
  ): Boolean {
    if (isStreaming || isRecording || isOnPreview) {
      throw IllegalStateException("Stream, record and preview must be stopped before prepareVideo")
    }
    // GPX fork patch: a new prepare means a new negotiated format is coming. Drop the old one so
    // getLastStreamVideoFormat() reports null rather than the previous session's format.
    lastStreamVideoFormat = null
    differentRecordResolution = false
    if (recordWidth > 0 && recordHeight > 0) {
      if (recordWidth.toDouble() / recordHeight.toDouble() != width.toDouble() / height.toDouble()) {
        throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
      }
      differentRecordResolution = true
    }
    // GPX fork patch: a call carrying no record dimensions means recording will tap the STREAM
    // encoder, so any claim about a record encoder's codec is void from here on — cleared regardless
    // of how this call ends. (A claim left standing here would let a caller label a recording with
    // the VOD codec while the stream encoder produces the bitstream.)
    if (!differentRecordResolution) recordCodecPrepared = null
    val videoResult = videoSource.init(max(width, recordWidth), max(height, recordHeight), fps, rotation)
    if (videoResult) {
      if (differentRecordResolution) {
        //using different record resolution
        if (rotation == 90 || rotation == 270) glInterface.setEncoderRecordSize(recordHeight, recordWidth)
        else glInterface.setEncoderRecordSize(recordWidth, recordHeight)
      }
      if (rotation == 90 || rotation == 270) glInterface.setEncoderSize(height, width)
      else glInterface.setEncoderSize(width, height)
      val isPortrait = rotation == 90 || rotation == 270
      glInterface.setIsPortrait(isPortrait)
      glInterface.setCameraOrientation(if (rotation == 0) 270 else rotation - 90)
      glInterface.setOrientationConfig(videoSource.getOrientationConfig())
      if (differentRecordResolution) {
        videoEncoderRecord.setTryForceVBRBitrateMode(forceRecordVbr)
        val result = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight, fps, recordBitrate, rotation,
          iFrameInterval, FormatVideoEncoder.SURFACE, recordProfile, recordLevel)
        // GPX fork patch: this site applies a codec together with a profile/level derived for it, so
        // it may set the claim. Failing BEFORE here (videoSource.init false, or the aspect throw)
        // never touched the record encoder and so must leave it untouched.
        recordCodecPrepared = if (result) recordEncoderCodec() else null
        if (!result) return false
      }
      val result = videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
        iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
      forceFpsLimit(true)
      return result
    }
    return false
  }

    fun prepareVideo(
        width: Int, height: Int, bitrate: Int, fps: Int = 30, iFrameInterval: Int = 2,
        rotation: Int = 0, profile: Int = -1, level: Int = -1,
        recordWidth: Int = 0, recordHeight: Int = 0, recordBitrate: Int = bitrate,
        recordCodec: VideoCodec = VideoCodec.H264, forceRecordVbr: Boolean = false,
        recordProfile: Int = profile, recordLevel: Int = level
    ): Boolean {
        if (isStreaming || isRecording || isOnPreview) {
            throw IllegalStateException("Stream, record and preview must be stopped before prepareVideo")
        }
        // GPX fork patch: see the other overload — clear the stale negotiated stream format.
        lastStreamVideoFormat = null
        differentRecordResolution = false
        if (recordWidth > 0 && recordHeight > 0) {
            if (recordWidth.toDouble() / recordHeight.toDouble() != width.toDouble() / height.toDouble()) {
                throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
            }
            differentRecordResolution = true
        }
        // GPX fork patch: see the other overload.
        if (!differentRecordResolution) recordCodecPrepared = null
        val videoResult = videoSource.init(max(width, recordWidth), max(height, recordHeight), fps, rotation)
        if (videoResult) {
            if (differentRecordResolution) {
                //using different record resolution
                if (rotation == 90 || rotation == 270) glInterface.setEncoderRecordSize(recordHeight, recordWidth)
                else glInterface.setEncoderRecordSize(recordWidth, recordHeight)
            }
            if (rotation == 90 || rotation == 270) glInterface.setEncoderSize(height, width)
            else glInterface.setEncoderSize(width, height)
            val isPortrait = rotation == 90 || rotation == 270
            glInterface.setIsPortrait(isPortrait)
            glInterface.setCameraOrientation(if (rotation == 0) 270 else rotation - 90)
            glInterface.setOrientationConfig(videoSource.getOrientationConfig())
            if (differentRecordResolution) {
                videoEncoderRecord.setTryForceVBRBitrateMode(forceRecordVbr)
                val result = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight, fps, recordBitrate, rotation,
                    iFrameInterval, FormatVideoEncoder.SURFACE, recordProfile, recordLevel)
                // GPX fork patch: see the other overload.
                recordCodecPrepared = if (result) recordEncoderCodec() else null
                if (!result) return false
            }
            val result = videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
                iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
            forceFpsLimit(true)
            return result
        }
        return false
    }

  /**
   * Necessary only one time before start stream or record.
   * If you want change values stop stream and record is necessary.
   *
   * @throws IllegalArgumentException if current video parameters are not supported by the AudioSource
   * @return True if success, False if failed
   */
  @Throws(IllegalArgumentException::class)
  @JvmOverloads
  fun prepareAudio(sampleRate: Int, isStereo: Boolean, bitrate: Int, echoCanceler: Boolean = false,
    noiseSuppressor: Boolean = false): Boolean {
    if (isStreaming || isRecording) {
      throw IllegalStateException("Stream and record must be stopped before prepareAudio")
    }
    val audioResult = audioSource.init(sampleRate, isStereo, echoCanceler, noiseSuppressor)
    if (audioResult) {
      onAudioInfoImp(sampleRate, isStereo)
      return audioEncoder.prepareAudioEncoder(bitrate, sampleRate, isStereo)
    }
    return false
  }

  /**
   * Start stream.
   *
   * Must be called after prepareVideo and prepareAudio
   */
  fun startStream(endPoint: String) {
    if (isStreaming) throw IllegalStateException("Stream already started, stopStream before startStream again")
    isStreaming = true
    // Keep state and transport transactional if source startup fails.
    var transportStarted = false
    try {
      startStreamImp(endPoint)
      transportStarted = true
      if (!isRecording) startSources()
      requestKeyframe()
    } catch (e: RuntimeException) {
      isStreaming = false
      if (transportStarted) {
        try {
          stopStreamImp()
        } catch (cleanup: RuntimeException) {
          e.addSuppressed(cleanup)
        }
      }
      throw e
    }
  }

  /**
   * Force VideoEncoder to produce a keyframe. Ignored if not recording or streaming.
   * This could be ignored depend of the Codec implementation in each device.
   */
  fun requestKeyframe() {
    if (videoEncoder.isRunning) {
      videoEncoder.requestKeyframe()
    }
    if (videoEncoderRecord.isRunning) {
      videoEncoderRecord.requestKeyframe()
    }
  }

  /**
   * Set video bitrate in bits per second while streaming.
   *
   * @param bitrate in bits per second.
   */
  fun setVideoBitrateOnFly(bitrate: Int) {
    videoEncoder.setVideoBitrateOnFly(bitrate)
  }

  /**
   * Keep encoder timestamps continuous (monotonic) across stop/start cycles such as SRT reconnect
   * recovery. Without it, every [startStream] rebases the encoder PTS to zero, which an HLS packager
   * downstream (e.g. Cloudflare Stream / Vbrick) sees as a backward timestamp jump and turns into a
   * visible discontinuity (segment loop / stall / refresh failure) for viewers. Default disabled.
   *
   * Must be called after prepareVideo/prepareAudio. Idempotent.
   */
  fun setContinuousTimestamp(enabled: Boolean) {
    videoEncoder.forceContinuousTs(enabled)
    videoEncoderRecord.forceContinuousTs(enabled)
    audioEncoder.forceContinuousTs(enabled)
  }

  /**
   * Force stream to work with fps selected in prepareVideo method. Must be called before prepareVideo.
   * Must be called after prepareVideo
   *
   * @param enabled true to enabled, false to disable, enabled by default.
   */
  fun forceFpsLimit(enabled: Boolean) {
    val fps = if (enabled) videoEncoder.fps else 0
    videoEncoder.setForceFps(fps)
    videoEncoderRecord.setForceFps(fps)
    glInterface.forceFpsLimit(fps)
  }

  /**
   * @param codecTypeVideo force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   * @param codecTypeAudio force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   */
  fun forceCodecType(codecTypeVideo: CodecUtil.CodecType, codecTypeAudio: CodecUtil.CodecType) {
    videoEncoder.forceCodecType(codecTypeVideo)
//    videoEncoderRecord.forceCodecType(codecTypeVideo)
    audioEncoder.forceCodecType(codecTypeAudio)
  }

  /**
   * Stop stream.
   *
   * @return True if encoders prepared successfully with previous parameters. False other way
   * If return is false you will need call prepareVideo and prepareAudio manually again before startStream or StartRecord
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  fun stopStream(): Boolean {
    isStreaming = false
    stopStreamImp()
    if (!isRecording) {
      stopSources()
      return prepareEncoders()
    }
    return true
  }

  /**
   * Start record.
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  @JvmOverloads
  fun startRecord(path: String, tracks: RecordController.RecordTracks? = null, listener: RecordController.Listener) {
    if (isRecording) throw IllegalStateException("Record already started, stopRecord before startRecord again")
    val usedTracks = tracks ?: if (videoSource is NoVideoSource) RecordController.RecordTracks.AUDIO
        else if (audioSource is NoAudioSource) RecordController.RecordTracks.VIDEO
        else RecordController.RecordTracks.ALL
    recordController.setRequestKeyFrame {
      videoEncoder.requestKeyframe()
      videoEncoderRecord.requestKeyframe()
    }
    recordController.startRecord(path, listener, usedTracks)
    // Keep recording state transactional if source startup fails.
    try {
      if (!isStreaming) startSources()
      requestKeyframe()
    } catch (e: RuntimeException) {
      try {
        recordController.stopRecord()
      } catch (cleanup: RuntimeException) {
        e.addSuppressed(cleanup)
      }
      throw e
    }
  }

  /**
   * @return True if encoders prepared successfully with previous parameters. False other way
   * If return is false you will need call prepareVideo and prepareAudio manually again before startStream or StartRecord
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  fun stopRecord(): Boolean {
    recordController.stopRecord()
    if (!isStreaming) {
      stopSources()
      return prepareEncoders()
    }
    return true
  }

  /**
   * Pause record. Ignored if you are not recording.
   */
  fun pauseRecord() {
    recordController.pauseRecord()
  }

  /**
   * Resume record. Ignored if you are not recording and in pause mode.
   */
  fun resumeRecord() {
    recordController.resumeRecord()
  }

  /**
   * Start preview in the selected TextureView.
   * Must be called after prepareVideo.
   */
  @JvmOverloads
  fun startPreview(textureView: TextureView, autoHandle: Boolean = false) {
    if (autoHandle) {
      previewCallback.setTextureView(textureView)
      if (textureView.isAvailable && !isOnPreview) startPreview(textureView)
    } else {
      startPreview(Surface(textureView.surfaceTexture), textureView.width, textureView.height)
    }
  }

  /**
   * Start preview in the selected SurfaceView.
   * Must be called after prepareVideo.
   */
  @JvmOverloads
  fun startPreview(surfaceView: SurfaceView, autoHandle: Boolean = false) {
    if (autoHandle) {
      previewCallback.setSurfaceView(surfaceView)
      if (surfaceView.holder.surface.isValid && !isOnPreview) startPreview(surfaceView)
    } else {
      startPreview(surfaceView.holder.surface, surfaceView.width, surfaceView.height)
    }
  }

  /**
   * Start preview in the selected SurfaceTexture.
   * Must be called after prepareVideo.
   */
  fun startPreview(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
    startPreview(Surface(surfaceTexture), width, height)
  }

  /**
   * Start preview in the selected Surface.
   * Must be called after prepareVideo.
   */
  fun startPreview(surface: Surface, width: Int, height: Int) {
    if (!surface.isValid) throw IllegalArgumentException("Make sure the Surface is valid")
    if (isOnPreview) throw IllegalStateException("Preview already started, stopPreview before startPreview again")
    // isOnPreview must not flip true until sources actually started.
    if (!glInterface.isRunning) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
    isOnPreview = true
    glInterface.attachPreview(surface)
    glInterface.setPreviewResolution(width, height)
  }

  /**
   * Stop preview.
   * Must be called after prepareVideo.
   */
  @JvmOverloads
  fun stopPreview(removeCallbacks: Boolean = false) {
    isOnPreview = false
    if (!isStreaming && !isRecording) videoSource.stop()
    glInterface.deAttachPreview()
    if (!isStreaming && !isRecording) glInterface.stop()
    if (removeCallbacks) previewCallback.removeCallbacks()
  }

  fun addPreviewSurface(surface: Surface, config: MultiPreviewConfig) {
    if (!surface.isValid) throw IllegalArgumentException("Make sure the Surface is valid")
    if (!isOnPreview) throw IllegalStateException("Preview must be started before adding surfaces")
    glInterface.addMultiPreviewSurface(surface, config)
  }

  fun removeMultiPreviewSurface(surface: Surface) {
    glInterface.removeMultiPreviewSurface(surface)
  }

  fun removeAllMultiPreviewSurfaces() {
    glInterface.removeAllMultiPreviewSurfaces()
  }

  fun updateMultiPreviewConfig(surface: Surface, config: MultiPreviewConfig): Boolean {
    return glInterface.updateMultiPreviewConfig(surface, config)
  }

  fun hasMultiPreviewSurface(surface: Surface): Boolean {
    return glInterface.hasMultiPreviewSurface(surface)
  }

  fun getMultiPreviewSurfaceCount(): Int = glInterface.getMultiPreviewSurfaceCount()

  /**
   * Change video source to Camera1 or Camera2.
   * Must be called after prepareVideo.
   *
   * @throws IllegalArgumentException if current video parameters are not supported by the VideoSource
   */
  @Throws(IllegalArgumentException::class)
  fun changeVideoSource(source: VideoSource) {
    val wasRunning = videoSource.isRunning()
    val wasCreated = videoSource.created
    if (wasCreated) {
      var width = videoEncoder.width
      var height = videoEncoder.height
      if (differentRecordResolution) {
        width = max(width, videoEncoderRecord.width)
        height = max(height, videoEncoderRecord.height)
      }
      source.init(width, height, videoEncoder.fps, videoEncoder.rotation)
    }
    videoSource.stop()
    videoSource.release()
    glInterface.surfaceTexture.tryClear()
    if (wasRunning) source.start(glInterface.surfaceTexture)
    glInterface.setOrientationConfig(source.getOrientationConfig())
    videoSource = source
  }

  /**
   * Change audio source.
   * Must be called after prepareAudio.
   *
   * @throws IllegalArgumentException if current video parameters are not supported by the AudioSource
   */
  @Throws(IllegalArgumentException::class)
  fun changeAudioSource(source: AudioSource) {
    val wasRunning = audioSource.isRunning()
    val wasCreated = audioSource.created
    if (wasCreated) source.init(audioSource.sampleRate, audioSource.isStereo, audioSource.echoCanceler, audioSource.noiseSuppressor)
    audioSource.stop()
    audioSource.release()
    if (wasRunning) source.start(getMicrophoneData)
    audioSource = source
  }

  /**
   * Set the mode to calculate timestamp. By default CLOCK.
   * Must be called before startRecord/startStream or it will be ignored.
   */
  fun setTimestampMode(timestampModeVideo: TimestampMode, timestampModeAudio: TimestampMode) {
    videoEncoder.setTimestampMode(timestampModeVideo)
    videoEncoderRecord.setTimestampMode(timestampModeVideo)
    audioEncoder.setTimestampMode(timestampModeAudio)
  }

  /**
   * Set a callback to know errors related with Video/Audio encoders
   * @param encoderErrorCallback callback to use, null to remove
   */
  fun setEncoderErrorCallback(encoderErrorCallback: CodecErrorCallback?) {
    videoEncoder.setEncoderErrorCallback(encoderErrorCallback)
    videoEncoderRecord.setEncoderErrorCallback(encoderErrorCallback)
    audioEncoder.setEncoderErrorCallback(encoderErrorCallback)
  }

  fun forceBt709Color(enabled: Boolean) {
    videoEncoder.forceBt709Color(enabled)
  }

  /**
   * @param callback get fps while record or stream
   */
  fun setFpsListener(callback: FpsListener.Callback?) {
    fpsListener.setCallback(callback)
  }

  /**
   * Change stream orientation depend of activity orientation.
   * This method affect to preview and stream.
   * Must be called after prepareVideo.
   */
  fun setOrientation(orientation: Int) {
    glInterface.setCameraOrientation(orientation)
  }

  /**
   * Get glInterface used to render video.
   * This is useful to send filters to stream.
   * Must be called after prepareVideo.
   */
  fun getGlInterface(): GlStreamInterface = glInterface

  /**
   * Replace the current BaseRecordController.
   * This method allow record in other format or even create your custom implementation and record in a new format.
   */
  fun setRecordController(recordController: RecordController) {
    if (!isRecording) {
      recordController.updateInfo(this.recordController.getVideoCodec(), this.recordController.getAudioCodec())
      this.recordController = recordController
      // If streaming is already running, the new record controller won't receive initial formats.
      // Replay the latest formats so it can build codec config (SPS/PPS) immediately.
      lastAudioFormat?.let { this.recordController.setAudioFormat(it) }
      lastVideoFormat?.let { this.recordController.setVideoFormat(it) }
      if (isStreaming) {
        requestKeyframe()
      }
    }
  }

  /**
   * GPX fork patch: observe the STREAM encoder's negotiated MediaFormat.
   *
   * Until this existed there was no way to see it. [setRecordController] only ever surfaces the
   * format of whichever encoder feeds recording, and when record dimensions are passed — which the
   * GPX app always does — that is the RECORD encoder. The stream encoder's own agreed format was
   * dropped in onVideoFormat and never reached a consumer.
   *
   * Register BEFORE prepareVideo. The encoder's callback thread is created during prepare, so
   * registering first is what publishes the listener to it. [listener] is invoked ON that callback
   * thread; keep it short and non-blocking. It is called inside a catch-and-log, so a throw cannot
   * kill the encoder callback — but it also cannot be retried, so do not rely on it for anything
   * load-bearing.
   *
   * Pass null to clear. Registering does NOT replay the last format; a late registrant should read
   * [getLastStreamVideoFormat] instead.
   */
  fun setStreamVideoFormatListener(listener: ((MediaFormat) -> Unit)?) {
    streamVideoFormatListener = listener
  }

  /**
   * GPX fork patch: the STREAM encoder's most recently negotiated MediaFormat, or null if it has not
   * produced one since the last prepareVideo. Cleared on prepare so a stale format from a previous
   * configuration is never mistaken for the current one.
   */
  fun getLastStreamVideoFormat(): MediaFormat? = lastStreamVideoFormat

  /**
   * return surface texture that can be used to render and encode custom data. Return null if video not prepared.
   * start and stop rendering must be managed by the user.
   */
  fun getSurfaceTexture(): SurfaceTexture {
    if (videoSource !is NoVideoSource) {
      throw IllegalStateException("getSurfaceTexture only available with VideoManager.Source.DISABLED")
    }
    return glInterface.surfaceTexture
  }

  protected fun getVideoResolution() = Size(videoEncoder.width, videoEncoder.height)

  protected fun getVideoFps() = videoEncoder.fps

  /**
   * GPX fork patch: start the camera/GL half of [startSources] WITHOUT connecting or starting the
   * encoders. Lets a caller warm a released camera ahead of [startStream] so the outbound connect
   * doesn't begin on a cold camera. Safe subset: reuses the exact same idempotency guards as
   * [startSources], so the later real [startSources] call (from [startStream]/[startRecord]) simply
   * no-ops on the camera/GL lines and proceeds to start audio/encoders as normal — nothing is skipped.
   *
   * Does NOT set [isOnPreview] (unlike [startPreview]), so it cannot block a real preview surface
   * from attaching later. Does NOT block on the capture session being ready to produce frames —
   * only that the camera device itself has opened; callers must still await frame-readiness
   * separately (e.g. via a camera-opened signal) before connecting.
   *
   * No-op if already streaming or already on preview (nothing to warm, or already warm).
   */
  fun warmSources() {
    if (isStreaming || isOnPreview) return
    if (!glInterface.isRunning) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
  }

  private fun startSources() {
    if (!glInterface.isRunning) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
    audioSource.start(getMicrophoneData)
    val startTs = TimeUtils.getCurrentTimeMicro()
    videoEncoder.start(startTs)
    if (differentRecordResolution) videoEncoderRecord.start(startTs)
    audioEncoder.start(startTs)
    glInterface.addMediaCodecSurface(videoEncoder.inputSurface)
    if (differentRecordResolution) glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
  }

  private fun stopSources() {
    if (!isOnPreview) videoSource.stop()
    audioSource.stop()
    glInterface.removeMediaCodecSurface()
    glInterface.removeMediaCodecRecordSurface()
    if (!isOnPreview) glInterface.stop()
    videoEncoder.stop()
    videoEncoderRecord.stop()
    audioEncoder.stop()
    if (!isRecording) recordController.resetFormats()
  }

  /**
   * Stop stream, record and preview and then release all resources.
   * You must call it after finish all the work.
   */
  fun release() {
    if (isStreaming) stopStream()
    if (isRecording) stopRecord()
    if (isOnPreview) stopPreview()
    stopSources()
    videoSource.release()
    audioSource.release()
    glInterface.surfaceTexture.tryClear()
  }

  /**
   * Reset VideoEncoder. Only recommended if a VideoEncoder class error is received in the EncoderErrorCallback
   *
   * @return true if success, false if failed
   */
  fun resetVideoEncoder(): Boolean {
    if (differentRecordResolution) {
      glInterface.removeMediaCodecRecordSurface()
      val result = videoEncoderRecord.reset()
      // GPX fork patch: reset() is a REPLAY (stop + no-arg prepare + restart) — same reasoning as
      // prepareEncoders(). Leave-or-clear only, never set.
      reconcileRecordCodecAfterReplay(result)
      if (!result) return false
      glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
    }
    glInterface.removeMediaCodecSurface()
    val result = videoEncoder.reset()
    if (!result) return false
    glInterface.addMediaCodecSurface(videoEncoder.inputSurface)
    return true
  }

  /**
   * Reset AudioEncoder. Only recommended if an AudioEncoder class error is received in the EncoderErrorCallback
   *
   * @return true if success, false if failed
   */
  fun resetAudioEncoder(): Boolean = audioEncoder.reset()

  private fun prepareEncoders(): Boolean {
    if (differentRecordResolution) {
      val result = videoEncoderRecord.prepareVideoEncoder()
      // GPX fork patch: a REPLAY — it re-sends the stored profile/level while reading the current
      // type, so if setVideoRecCodec moved the MIME since the last real prepare, the encoder now
      // runs a codec whose profile/level were never derived for it. Leave-or-clear only.
      reconcileRecordCodecAfterReplay(result)
      if (!result) return false
    }
    return videoEncoder.prepareVideoEncoder() && audioEncoder.prepareAudioEncoder()
  }

  private val getAacData: GetAudioData = object : GetAudioData {
    override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      getAudioDataImp(audioBuffer, info)
      recordController.recordAudio(audioBuffer, info)
    }

    override fun onAudioFormat(mediaFormat: MediaFormat) {
      lastAudioFormat = mediaFormat
      recordController.setAudioFormat(mediaFormat)
    }
  }

  private val getVideoData: GetVideoData = object : GetVideoData {
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
      onVideoInfoImp(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
    }

    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      fpsListener.calculateFps()
      if (!differentRecordResolution) recordController.recordVideo(videoBuffer, info)
      getVideoDataImp(videoBuffer, info)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {
      // GPX fork patch: surface the STREAM encoder's negotiated format unconditionally. The record
      // routing below is gated on !differentRecordResolution — which the GPX app's live path never
      // is, since it always passes record dimensions — so without this the stream encoder's agreed
      // format reached no consumer at all.
      lastStreamVideoFormat = mediaFormat
      try {
        streamVideoFormatListener?.invoke(mediaFormat)
      } catch (e: Exception) {
        // This runs on the MediaCodec callback HandlerThread, and onOutputFormatChanged is the one
        // Callback method BaseEncoder does NOT wrap (onInput/onOutputBufferAvailable both catch into
        // reloadCodec). An exception from consumer code would propagate uncaught and kill the
        // process mid-stream. Same rule as VideoEncoder.logNegotiatedFormat: a diagnostic seam must
        // never be able to break an encoder callback. Not caught: Error — an unsurvivable JVM state
        // should not be papered over into a silently wedged pipeline.
        Log.e("StreamBase", "streamVideoFormatListener threw; ignoring", e)
      }
      if (!differentRecordResolution) {
        lastVideoFormat = mediaFormat
        recordController.setVideoFormat(mediaFormat)
      }
    }
  }

  private val getVideoDataRecord: GetVideoData = object : GetVideoData {
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    }

    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      recordController.recordVideo(videoBuffer, info)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {
      lastVideoFormat = mediaFormat
      recordController.setVideoFormat(mediaFormat)
    }
  }

  protected abstract fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean)
  protected abstract fun startStreamImp(endPoint: String)
  protected abstract fun stopStreamImp()
  protected abstract fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
  protected abstract fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
  protected abstract fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo)

  abstract fun getStreamClient(): StreamBaseClient

    fun setTryForceVBRBitrateMode(forVideoEncoder: Boolean, forVideoEncoderRecord: Boolean) {
        if (forVideoEncoder) videoEncoder.setTryForceVBRBitrateMode(true)
        if (forVideoEncoderRecord) videoEncoderRecord.setTryForceVBRBitrateMode(true)
    }

    /**
     * GPX fork patch: a codec-namespaced profile/level pair, kept together because the
     * MediaCodecInfo.CodecProfileLevel constants mean different things per codec and a pair that
     * travels apart from its codec is how the cross-namespace bug happens.
     */
    data class ProfileLevel(val profile: Int, val level: Int)

    private fun mimeOf(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> CodecUtil.H264_MIME
        VideoCodec.H265 -> CodecUtil.H265_MIME
        VideoCodec.AV1 -> CodecUtil.AV1_MIME
    }

    /**
     * GPX fork patch: declare the record codec BEFORE prepareVideo — pre-prepare intent only.
     *
     * Sets the muxer label and the record encoder's MIME so the prepareVideo that follows builds the
     * encoder on that codec with a matching profile/level. It never re-prepares, which is what makes
     * it correct even when the record encoder is already prepared from a previous session (stopStream
     * and stopRecord both re-prepare it via prepareEncoders()).
     *
     * Deliberately does NOT touch recordCodecPrepared: it changes intent, not what the encoder is.
     * If the following prepareVideo fails, the claim therefore still truthfully names the OLD codec,
     * and the next applyVideoRecCodec sees a mismatch instead of a stale match.
     *
     * To change the record codec on an ALREADY-PREPARED encoder, use [applyVideoRecCodec] — this
     * method would leave the muxer labelled with a codec the encoder is not producing.
     */
    fun setVideoRecCodec(codec: VideoCodec) {
        recordController.setVideoCodec(codec)
        videoEncoderRecord.type = mimeOf(codec)
    }

    /**
     * GPX fork patch: true if [applyVideoRecCodec] would have to re-prepare the record encoder.
     *
     * Lets a caller decide whether it needs to take its own locks, or derive a profile/level, before
     * calling. Same predicate applyVideoRecCodec uses internally, exposed as one method so the two
     * decisions cannot diverge.
     */
    fun recordCodecNeedsReprepare(codec: VideoCodec): Boolean {
        if (!differentRecordResolution) return false
        return recordCodecPrepared != codec || !videoEncoderRecord.isPrepared()
    }

    /**
     * GPX fork patch: apply the record codec COHERENTLY to an already-prepared encoder.
     *
     * Either the record encoder's MIME, its profile/level, and the muxer's label all describe
     * [codec] when this returns true, or nothing is claimed and it returns false. The old
     * setVideoRecCodec did half of this silently on a prepared encoder, which is how a `.ts` could
     * end up declaring a codec its bitstream did not match (gpxnative-ai#267).
     *
     * @param pair profile/level derived for [codec]. May be null ONLY when the caller believes this
     *   is a no-op; if it turns out a re-prepare is needed, this fails closed rather than preparing
     *   with a sentinel.
     * @return true if the encoder and the label now agree on [codec]. False means DO NOT start
     *   recording — the label was left describing the last bitstream that actually existed.
     */
    fun applyVideoRecCodec(codec: VideoCodec, pair: ProfileLevel?): Boolean {
        if (isRecording) {
            throw IllegalStateException("stopRecord before changing the record codec")
        }
        // Recording taps the STREAM encoder when no record encoder is prepared, so the record codec
        // is not this method's to set. Keyed on differentRecordResolution, not isPrepared(): that
        // flag alone governs whether the record encoder is started, attached and read.
        if (!differentRecordResolution) return false

        if (!recordCodecNeedsReprepare(codec)) {
            recordController.setVideoCodec(codec)
            return true
        }
        if (pair == null) {
            // The caller expected a no-op and did not derive a pair (prepared can flip on the codec
            // callback thread, which takes no lock). Preparing with a sentinel would hand the encoder
            // a profile/level in no particular namespace.
            recordCodecPrepared = null
            return false
        }

        val wasRunning = videoEncoderRecord.isRunning
        // Start and attach here exactly when startRecord's own `if (!isStreaming) startSources()`
        // will not: while streaming it is skipped, so this is the only thing that can, and that is
        // true even when the encoder is not currently running (a previously-failed apply).
        val mustStartHere = wasRunning || isStreaming

        glInterface.removeMediaCodecRecordSurface()
        // stop(false), not stop(): preserves the timestamp baseline so a recording spanning a codec
        // change keeps monotonic PTS for the downstream clip pipeline. prepareVideoEncoder() would
        // stop() it anyway; this is what makes that stop non-resetting.
        videoEncoderRecord.stop(false)
        videoEncoderRecord.type = mimeOf(codec)
        val prepared = videoEncoderRecord.prepareVideoEncoder(pair.profile, pair.level)
        if (!prepared) {
            recordCodecPrepared = null
            return false
        }
        // A new prepare means a new negotiated format is coming; the old one describes the previous
        // codec and would otherwise be replayed into a freshly installed record controller. Cleared
        // on this branch ONLY — clearing on the no-op branch would starve a rollover's fresh
        // controller of any format at all, since no second onOutputFormatChanged is coming.
        lastVideoFormat = null
        if (mustStartHere) {
            videoEncoderRecord.restart()
            glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
        }
        recordCodecPrepared = codec
        // Label LAST, so it is only ever advanced once the bitstream behind it exists.
        recordController.setVideoCodec(codec)
        return true
    }


  /**
   * Change VideoCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example AV1 is not supported in SRT
   */
  fun setVideoCodec(codec: VideoCodec) {
    setVideoCodecImp(codec)
    val type = when (codec) {
      VideoCodec.H264 -> CodecUtil.H264_MIME
      VideoCodec.H265 -> CodecUtil.H265_MIME
      VideoCodec.AV1 -> CodecUtil.AV1_MIME
    }
    videoEncoder.type = type
    // Recording codec is controlled separately (e.g., setVideoRecCodec)
    if (isStreaming) {
      Log.i("StreamBase", "setVideoCodec: streaming active, resetting video encoder for codec=${codec.name}")
      val resetOk = resetVideoEncoder()
      if (!resetOk) {
        throw IllegalStateException("Failed to reset video encoder after codec change")
      }
      requestKeyframe()
    }
  }

  /**
   * Change AudioCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example G711 is not supported in SRT
   */
  fun setAudioCodec(codec: AudioCodec) {
    setAudioCodecImp(codec)
    recordController.setAudioCodec(codec)
    val type = when (codec) {
      AudioCodec.G711 -> CodecUtil.G711_MIME
      AudioCodec.AAC -> CodecUtil.AAC_MIME
      AudioCodec.OPUS -> CodecUtil.OPUS_MIME
    }
    audioEncoder.type = type
  }

  protected abstract fun setVideoCodecImp(codec: VideoCodec)
  protected abstract fun setAudioCodecImp(codec: AudioCodec)
}
