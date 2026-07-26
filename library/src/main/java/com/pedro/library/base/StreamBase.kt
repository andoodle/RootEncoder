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
   * A fact, never an intention. Nothing may set it except a site that has just prepared the record
   * encoder — since it names what the encoder *is*, which is all this field means. (Until
   * gpxnative-ai#282 there was a pre-prepare `setVideoRecCodec` that advanced
   * `videoEncoderRecord.type` without preparing anything and deliberately left this alone; it is
   * gone, and [prepareVideo]'s `recordCodec` parameter took its place.)
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
   * One replay is NOT hooked: VideoEncoder's own reloadCodec -> reset(), which runs inside the
   * encoder on the codec-callback thread and is invisible here. It replays the current `type`, so if
   * a caller had mutated the MIME while the encoder was running, this field would keep naming the
   * OLD codec after the encoder moved — an over-report, not the safe direction. That combination is
   * unreachable through the intended discipline (since gpxnative-ai#282 the only way to set the
   * record MIME at all is [prepareVideo]'s `recordCodec`, and prepareVideo requires stream, record
   * and preview stopped), so it is a fork-misuse hazard rather than a live one; changing the record
   * MIME on a running encoder is exactly what [applyVideoRecCodec] exists to do safely.
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
   * codec than the stream encoder (its MIME comes from this call's [recordCodec]). The
   * MediaCodecInfo.CodecProfileLevel constants are codec-namespaced — 1 is AVCProfileBaseline for
   * H264 but HEVCProfileMain for H265, and 2048 is AVCLevel4 but HEVCHighTierLevel4 — so a single
   * pair cannot be correct for both encoders whenever the two codecs differ, which for the GPX app
   * (stream H264 + record H265) is the default rather than an edge case. Defaults to [profile] so
   * every existing caller keeps today's shared-pair behaviour exactly.
   * @param recordLevel GPX fork patch: level for the RECORD encoder. See [recordProfile]; defaults
   * to [level] for the same reason.
   * @param recordCodec GPX fork patch (gpxnative-ai#282): the codec the RECORDING will be in,
   * applied INSIDE this prepare rather than by a setter before it. Null keeps whatever codec the
   * record encoder already holds. This replaces the old pre-prepare `setVideoRecCodec`, which
   * advanced the muxer's label immediately and the encoder's MIME only at the next rebuild — so a
   * call made at the wrong moment silently half-applied, and every call site read as correct
   * (gpxnative-ai#267). Here the two cannot separate: the MIME is set immediately before the
   * prepare that realises it, and the label is advanced only AFTER that prepare has succeeded.
   *
   * When no record dimensions are passed the fork prepares no record encoder and recording taps the
   * STREAM encoder, so only the label is set — pass the stream codec on that path, not a VOD codec
   * the bitstream will not be in.
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
    recordProfile: Int = profile, recordLevel: Int = level,
    recordCodec: VideoCodec? = null
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
        // GPX fork patch (gpxnative-ai#282): the MIME moves immediately before the prepare that
        // realises it, so the two can never be observed apart. The label is NOT advanced here —
        // see below.
        if (recordCodec != null) videoEncoderRecord.type = mimeOf(recordCodec)
        val result = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight, fps, recordBitrate, rotation,
          iFrameInterval, FormatVideoEncoder.SURFACE, recordProfile, recordLevel)
        // GPX fork patch: this site applies a codec together with a profile/level derived for it, so
        // it may set the claim. Failing BEFORE here (videoSource.init false, or the aspect throw)
        // never touched the record encoder and so must leave it untouched.
        recordCodecPrepared = if (result) recordEncoderCodec() else null
        if (!result) return false
        // GPX fork patch (gpxnative-ai#282): label LAST, the same discipline applyVideoRecCodec
        // uses — the muxer only ever names a codec whose bitstream already exists. A failed
        // prepare above returns with the label still describing the last bitstream that was real.
        if (recordCodec != null) recordController.setVideoCodec(recordCodec)
      }
      val result = videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
        iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
      // GPX fork patch (gpxnative-ai#282): with no record encoder, recording taps the STREAM
      // encoder, so the label is all there is to set — and only once that encoder is prepared.
      if (result && !differentRecordResolution && recordCodec != null) {
        recordController.setVideoCodec(recordCodec)
      }
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
      // gpxnative-ai#312: unconditional — startSources() is idempotent on its own state now, so
      // this no longer has to infer "are the sources up?" from isRecording, a flag that answers a
      // different question and could be false while they were running.
      startSources()
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
    // Keep recording state transactional if source startup fails. gpxnative-ai#312: unconditional
    // for the same reason as startStream — startSources() owns its own idempotency, and it now
    // stops whatever it started before rethrowing, so this catch no longer has to.
    try {
      startSources()
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

  /**
   * GPX fork patch (gpxnative-ai#312): whether [startSources] has brought the shared source
   * lifecycle up and [stopSources] has not yet taken it down.
   *
   * The three entry points into that one lifecycle used to be guarded on a *consumer* flag each —
   * `startStream`/`stopStream` on `isStreaming`, `startRecord`/`stopRecord` on `isRecording` —
   * with nothing keeping either flag consistent with whether the sources were actually running.
   * `startRecord`'s catch cleared `isRecording` without stopping them, so the sources could be left
   * running with BOTH flags false; a later `startStream` then took the `!isRecording` branch,
   * called `startSources()` again, and reached `codec.start()` on an already-started MediaCodec:
   *
   *     IllegalStateException: start() is valid only at Configured state; currently at Running state
   *
   * One flag doing two jobs. The consumer flags still answer "is anyone else using the sources?"
   * — which is what the stop-side guards genuinely need — and this one answers "are they up?",
   * which is what the start side needs and what nothing tracked before.
   *
   * Volatile: written on whichever app thread drives start/stop, read from the same set.
   */
  @Volatile
  private var sourcesRunning = false

  /**
   * GPX fork patch (gpxnative-ai#312): idempotent, and transactional.
   *
   * Idempotent so a caller no longer has to guess from a consumer flag whether the sources are
   * already up — a second call is a no-op rather than a MediaCodec state crash. Transactional so a
   * partial failure cannot leave half a lifecycle running: previously both `startStream`'s catch
   * (which only tore down the transport) and `startRecord`'s catch (which only stopped the record
   * controller) rethrew with the encoders left Running, which is what turned one failed attempt
   * into a permanent wedge.
   *
   * [sourcesRunning] is set BEFORE the body deliberately: the cleanup path has to be able to run,
   * and every line of [stopSources] is individually safe against a component that never started.
   */
  private fun startSources() {
    if (sourcesRunning) return
    sourcesRunning = true
    try {
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
    } catch (e: RuntimeException) {
      try {
        stopSourcesImp()
      } catch (cleanup: RuntimeException) {
        e.addSuppressed(cleanup)
      } finally {
        sourcesRunning = false
      }
      throw e
    }
  }

  private fun stopSources() {
    if (!sourcesRunning) return
    sourcesRunning = false
    stopSourcesImp()
  }

  /**
   * GPX fork patch (gpxnative-ai#312): the teardown itself, without the [sourcesRunning] guard.
   *
   * [release] calls this rather than [stopSources] so a release on a never-started stream still
   * runs the full teardown, exactly as it did before the guard existed.
   */
  private fun stopSourcesImp() {
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
    // gpxnative-ai#312: the unguarded teardown — a release on a never-started stream must still
    // run every line, exactly as it did before sourcesRunning existed.
    sourcesRunning = false
    stopSourcesImp()
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
      // type, so if anything moved the MIME since the last real prepare, the encoder now
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
     * GPX fork patch: true if [applyVideoRecCodec] would have to re-prepare the record encoder.
     *
     * Lets a caller decide whether it needs to derive a profile/level before calling. Same predicate
     * applyVideoRecCodec uses internally, exposed as one method so the two decisions cannot diverge.
     *
     * NOTE the two distinct meanings of false: "already prepared with this codec, so the apply is a
     * label-only no-op", and "there is no record encoder at all", where the apply will refuse and
     * return false. A caller using this to answer "is the record encoder already on this codec?"
     * must therefore treat the apply's own false as authoritative rather than assuming success.
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
     * pre-prepare setVideoRecCodec (retired in gpxnative-ai#282) did half of this silently on a
     * prepared encoder, which is how a `.ts` could
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

        // From here the encoder's MIME no longer matches recordCodecPrepared, so EVERY exit —
        // including an exception — must leave the claim correct. restart() reaches
        // MediaCodec.start(), which genuinely throws on this path: it brings up a second hardware
        // video encoder while the stream encoder and camera are live, and that is at or past the
        // concurrent-encoder limit on some SoCs.
        //
        // Clearing on the way out is the safe direction. Leaving the old codec's name against an
        // encoder now holding the new one is an OVER-report: a later change back to the old codec
        // would see a match, take the label-only no-op branch, and record that codec's label over
        // this codec's bitstream — silently reinstating #267 through the very API added to prevent
        // it, visible only in a written .ts file.
        try {
            videoEncoderRecord.type = mimeOf(codec)
            val prepared = videoEncoderRecord.prepareVideoEncoder(pair.profile, pair.level)
            if (!prepared) {
                recordCodecPrepared = null
                return false
            }
            // A new prepare means a new negotiated format is coming; the old one describes the
            // previous codec and would otherwise be replayed into a freshly installed record
            // controller. Cleared on this branch ONLY — clearing on the no-op branch would starve a
            // rollover's fresh controller of any format at all, since no second
            // onOutputFormatChanged is coming.
            lastVideoFormat = null
            if (mustStartHere) {
                videoEncoderRecord.restart()
                glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
            }
            recordCodecPrepared = codec
            // Label LAST, so it is only ever advanced once the bitstream behind it exists.
            recordController.setVideoCodec(codec)
            return true
        } catch (t: Throwable) {
            recordCodecPrepared = null
            throw t
        }
    }


  /**
   * Change VideoCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example AV1 is not supported in SRT
   */
  fun setVideoCodec(codec: VideoCodec) {
    val type = when (codec) {
      VideoCodec.H264 -> CodecUtil.H264_MIME
      VideoCodec.H265 -> CodecUtil.H265_MIME
      VideoCodec.AV1 -> CodecUtil.AV1_MIME
    }
    videoEncoder.type = type
    // Recording codec is controlled separately — see prepareVideo's recordCodec parameter and
    // [applyVideoRecCodec].
    //
    // GPX fork patch (gpxnative-ai#286 F-3): guarded on whether the ENCODER is running, not on
    // isStreaming. startRecord starts the stream encoder too (startSources -> videoEncoder.start),
    // so record-without-stream runs it with isStreaming false — and the old guard therefore moved
    // the MIME on a live encoder with no reset. `type` is read at runtime, not only at the next
    // prepare (VideoEncoder.sendSPSandPPS branches on it), so the SPS/PPS handed to the record
    // controller would be parsed under the new codec's rules against the old codec's bitstream.
    // Same one-flag-two-jobs family as gpxnative-ai#312.
    if (videoEncoder.isRunning) {
      Log.i("StreamBase", "setVideoCodec: encoder running, resetting video encoder for codec=${codec.name}")
      val resetOk = resetVideoEncoder()
      if (!resetOk) {
        throw IllegalStateException("Failed to reset video encoder after codec change")
      }
    }
    // Moved AFTER the reset: a failed reset used to throw with the publisher already advanced to a
    // codec the encoder was not producing. Still before requestKeyframe, so the SPS/PPS the
    // keyframe drives out reach a publisher already on the new codec.
    setVideoCodecImp(codec)
    if (videoEncoder.isRunning) requestKeyframe()
  }

  /**
   * Change AudioCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example G711 is not supported in SRT
   */
  fun setAudioCodec(codec: AudioCodec) {
    val type = when (codec) {
      AudioCodec.G711 -> CodecUtil.G711_MIME
      AudioCodec.AAC -> CodecUtil.AAC_MIME
      AudioCodec.OPUS -> CodecUtil.OPUS_MIME
    }
    audioEncoder.type = type
    // GPX fork patch (gpxnative-ai#286 F-2): this was the unfixed audio twin of the old
    // setVideoRecCodec — two writes that took effect immediately (the publisher's sender and the
    // muxer's label) plus one that took effect only at the next prepareAudioEncoder (the MIME),
    // with no guard at all. Called while the audio encoder was running it left the container
    // labelled one codec over the other codec's bitstream, which is gpxnative-ai#267 for audio.
    //
    // `type` is also read at RUNTIME by the running pipeline (BaseEncoder's G711 branches in
    // setCallback / initCodec / getDataFromEncoder), so a deferred MIME is not merely late — it
    // makes live code branch under the new codec's rules against a codec object built for the old.
    //
    // Resetting mirrors setVideoCodec: the encoder actually moves, so encoder, label and publisher
    // all describe the same codec when this returns, or it throws and nothing is claimed.
    if (audioEncoder.isRunning) {
      Log.i("StreamBase", "setAudioCodec: encoder running, resetting audio encoder for codec=${codec.name}")
      if (!resetAudioEncoder()) {
        throw IllegalStateException("Failed to reset audio encoder after codec change")
      }
    }
    setAudioCodecImp(codec)
    recordController.setAudioCodec(codec)
  }

  protected abstract fun setVideoCodecImp(codec: VideoCodec)
  protected abstract fun setAudioCodecImp(codec: AudioCodec)
}
