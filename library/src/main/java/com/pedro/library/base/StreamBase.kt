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
 *
 * ---
 *
 * **GPX — this file carries the largest concentration of fork changes, applied as one group.**
 * They reference each other's fields, so they were re-applied together rather than as separate
 * items (see `.claude/gpx-reapply-plan-2.8.0.md`, "Two plan revisions made while applying R1-R11").
 * Present here:
 *
 * - **R4** — a keyframe on `startStream()` and on `startRecord()`.
 * - **R9** — the non-resetting stop that keeps timestamps continuous across a codec change.
 * - **R15** — the [warmSources] seam.
 * - **R18** — the negotiated-format seam ([setStreamVideoFormatListener], [getLastStreamVideoFormat]).
 * - **R19** — the record codec applied coherently on a prepared encoder ([applyVideoRecCodec],
 *   [recordCodecNeedsReprepare], and the `recordCodecPrepared` claim it maintains).
 * - **R28** — the scoped per-encoder re-prepare ([applyVideoStreamConfig],
 *   [applyVideoRecConfig]): rebuild one video encoder while the other encoder and the muxer
 *   keep running (gpxstream-app S8 gate, F2).
 * - **GPX patch** — a `sourcesRunning` flag making `startSources`/`stopSources` idempotent and
 *   transactional, and an `isOnPreview` ordering fix in `startPreview`.
 *
 * Individual regions below carry their own `GPX` marker. Because the group is this dense, treat
 * the marked regions as the index rather than assuming the unmarked remainder is all upstream —
 * `git diff 9a9ca124f -- <this file>` is the authority.
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
   * GPX R18 — the stream encoder's negotiated MediaFormat.
   *
   * Separate from [lastVideoFormat], which means "the format of whichever encoder feeds recording"
   * and is replayed into a newly installed controller by [setRecordController]. Writing the stream
   * encoder's format into that field while the record encoder is the one recording would hand a
   * record controller the wrong SPS/PPS for the bitstream it is muxing.
   *
   * Volatile: written on the MediaCodec callback thread, read from app threads.
   */
  @Volatile
  private var lastStreamVideoFormat: MediaFormat? = null

  /** GPX R18 — see [setStreamVideoFormatListener]. Volatile as for [lastStreamVideoFormat]. */
  @Volatile
  private var streamVideoFormatListener: ((MediaFormat) -> Unit)? = null

  /**
   * GPX R19 — the codec the record encoder was last successfully prepared with, as observed here.
   * Null means
   * not known to be prepared with anything.
   *
   * A fact, never an intention. Only a site that applies a codec together with a profile and level
   * derived for that codec may set it: [prepareVideo]'s record branch, [applyVideoRecCodec] and
   * [applyVideoRecConfig] (R28).
   * Every other site that prepares the record encoder is a replay ([prepareEncoders],
   * [resetVideoEncoder]) that reuses whatever codec and stored pair are on the encoder, so it can
   * move the codec without moving the pair, and may only leave this alone or clear it. A replay that
   * set it would let an encoder running H265 with H264-namespace profile and level be treated as
   * correctly configured.
   *
   * Not sufficient on its own: it can outlive the encoder it describes, because stop() clears the
   * encoder's prepared state without touching this. Every consumer must also check
   * videoEncoderRecord.isPrepared().
   *
   * One replay is not hooked: VideoEncoder's own reloadCodec -> reset(), which runs inside the
   * encoder on the codec-callback thread and is invisible here. It replays the current codec, so if
   * a caller had changed the record codec while the encoder was running, this field would keep
   * naming the old codec after the encoder moved, which is an over-report rather than the safe
   * direction. Changing the record codec on a running encoder is what [applyVideoRecCodec] is for.
   *
   * Volatile: written on the configure thread, read and written on the caller's thread.
   */
  @Volatile
  private var recordCodecPrepared: VideoCodec? = null

  /** GPX R19 — the codec the record encoder currently holds. */
  private fun recordEncoderCodec(): VideoCodec? = videoEncoderRecord.type as? VideoCodec

  /**
   * GPX R19 — a replay just re-prepared the record encoder from its stored fields. Keep
   * [recordCodecPrepared]
   * only if the replayed codec still matches it; otherwise the encoder moved to a codec whose
   * profile and level were never derived for it, so drop the claim and force the next caller to
   * re-prepare properly. Never sets: see [recordCodecPrepared].
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
   * @param recordProfile profile for the record encoder, which may run a different codec than the
   * stream encoder. The MediaCodecInfo.CodecProfileLevel constants are codec-namespaced -- 1 is
   * AVCProfileBaseline for H264 and HEVCProfileMain for H265, 2048 is AVCLevel4 and
   * HEVCHighTierLevel4 -- so one pair cannot be correct for both encoders whenever the two codecs
   * differ. Defaults to [profile], so an existing caller keeps the shared-pair behaviour.
   * @param recordLevel level for the record encoder. See [recordProfile]; defaults to [level].
   * @param recordCodec the codec the recording will be in, applied inside this prepare rather than
   * by a setter before it. Null keeps whatever codec the record encoder already holds. Here the
   * codec and the muxer label cannot separate: the codec is set immediately before the prepare that
   * realises it, and the label is advanced only after that prepare succeeded.
   *
   * When no record dimensions are passed, no record encoder is prepared and recording taps the
   * stream encoder, so only the label is set -- pass the stream codec on that path, not a codec the
   * bitstream will not be in.
   * @param forceRecordVbr when the record encoder is prepared at its own resolution, force it into
   * VBR instead of the device default. Independent of the resolution-difference check, so a caller
   * decides it explicitly rather than receiving it as a side effect.
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
    // A new prepare means a new negotiated format is coming. Drop the old one so
    // getLastStreamVideoFormat() reports null rather than the previous configuration's format.
    lastStreamVideoFormat = null
    differentRecordResolution = false
    if (recordWidth > 0 && recordHeight > 0) {
      if (recordWidth.toDouble() / recordHeight.toDouble() != width.toDouble() / height.toDouble()) {
        throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
      }
      differentRecordResolution = true
    }
    // No record dimensions means recording will tap the stream encoder, so any claim about a record
    // encoder's codec is void from here, however this call ends. A claim left standing would let a
    // caller label a recording with a codec the stream encoder is not producing.
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
        // The codec moves immediately before the prepare that realises it, so the two cannot be
        // observed apart. The muxer label is not advanced here; see below.
        if (recordCodec != null) videoEncoderRecord.type = recordCodec
        val result = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight, fps, recordBitrate, rotation,
          iFrameInterval, FormatVideoEncoder.SURFACE, recordProfile, recordLevel)
        // This site applies a codec together with a profile and level derived for it, so it may set
        // the claim. Failing before here never touched the record encoder, so it must leave the
        // claim untouched.
        recordCodecPrepared = if (result) recordEncoderCodec() else null
        if (!result) return false
        // Label last, the same discipline applyVideoRecCodec uses: the muxer only ever names a codec
        // whose bitstream already exists. A failed prepare above returns with the label still
        // describing the last bitstream that was real.
        if (recordCodec != null) recordController.setVideoCodec(recordCodec)
      }
      val result = videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
        iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
      // With no record encoder, recording taps the stream encoder, so the label is all there is to
      // set, and only once that encoder is prepared.
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
    // Keep the flag and the transport consistent if source startup fails.
    var transportStarted = false
    try {
      startStreamImp(endPoint)
      transportStarted = true
      // GPX patch — unconditional: startSources() is idempotent on its own state, so this no longer
      // has to infer
      // "are the sources up?" from isRecording, a flag that answers a different question and could
      // be false while they were running.
      startSources()
      // GPX R4 — unconditional keyframe so a viewer joining at connect gets a decodable picture
      // rather than waiting for the next one in the GOP.
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
    videoEncoderRecord.forceCodecType(codecTypeVideo)
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
    // Keep recording state consistent if source startup fails. Unconditional for the same reason as
    // startStream: startSources() owns its own idempotency and stops whatever it started before
    // rethrowing, so this catch does not have to.
    try {
      startSources()
      // GPX R4 — unconditional keyframe so the file is decodable and seekable from its first frame.
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
    // GPX patch — isOnPreview flips only once the sources actually started. stopSources() reads it
    // whether to stop the video source, so setting it first meant a failed start left the flag true
    // and the source unstoppable.
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
    if (glInterface.isRunning) glInterface.surfaceTexture.tryClear()
    if (wasRunning) {
      runCatching { source.start(glInterface.surfaceTexture) }.getOrElse {
        runCatching { videoSource.start(glInterface.surfaceTexture) }
        throw it
      }
    }
    videoSource.release()
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
    if (wasRunning) {
      runCatching { source.start(getMicrophoneData) }.getOrElse {
        runCatching { audioSource.start(getMicrophoneData) }
        throw it
      }
    }
    audioSource.release()
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
      // A controller installed mid-session receives no initial formats of its own. Replay the last
      // ones so it can build codec config immediately.
      lastAudioFormat?.let { this.recordController.setAudioFormat(it) }
      lastVideoFormat?.let { this.recordController.setVideoFormat(it) }
      if (isStreaming) {
        requestKeyframe()
      }
    }
  }

  /**
   * GPX R18 — observe the stream encoder's negotiated MediaFormat.
   *
   * [setRecordController] only ever surfaces the format of whichever encoder feeds recording, and
   * when record dimensions are passed that is the record encoder, so the stream encoder's own agreed
   * format previously reached no consumer.
   *
   * Register before prepareVideo: the encoder's callback thread is created during prepare, and
   * registering first is what publishes the listener to it. [listener] is invoked on that callback
   * thread, so keep it short and non-blocking. It is called inside a catch-and-log, so a throw
   * cannot kill the encoder callback, but it also cannot be retried.
   *
   * Pass null to clear. Registering does not replay the last format; a late registrant should read
   * [getLastStreamVideoFormat].
   */
  fun setStreamVideoFormatListener(listener: ((MediaFormat) -> Unit)?) {
    streamVideoFormatListener = listener
  }

  /**
   * GPX R18 — the stream encoder's most recently negotiated MediaFormat, or null if it has not
   * produced one
   * since the last prepareVideo. Cleared on prepare, so a format from a previous configuration is
   * never mistaken for the current one.
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
   * GPX R15 — start the camera and GL half of [startSources] without connecting or starting the
   * encoders, so a
   * caller can warm a released camera before [startStream] and the outbound connect does not begin
   * on a cold camera. It reuses the same guards as [startSources], so the later real [startSources]
   * no-ops on the camera and GL lines and still starts audio and the encoders.
   *
   * Does not set [isOnPreview], so it cannot stop a real preview surface attaching later. Does not
   * wait for the capture session to be able to produce frames, only for the camera device to open;
   * a caller must still wait for frame readiness before connecting.
   *
   * No-op while streaming or on preview.
   */
  fun warmSources() {
    if (isStreaming || isOnPreview) return
    if (!glInterface.isRunning) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
  }

  /**
   * Whether [startSources] has brought the shared source lifecycle up and [stopSources] has not yet
   * taken it down.
   *
   * The three entry points into that one lifecycle were each guarded on a consumer flag --
   * startStream and stopStream on isStreaming, startRecord and stopRecord on isRecording -- with
   * nothing keeping either flag consistent with whether the sources were actually running.
   * startRecord's catch cleared isRecording without stopping them, so the sources could be left
   * running with both flags false; a later startStream then took the !isRecording branch, called
   * startSources() again, and reached codec.start() on an already-started MediaCodec:
   *
   *     IllegalStateException: start() is valid only at Configured state; currently at Running state
   *
   * The consumer flags still answer "is anyone else using the sources?", which is what the stop-side
   * guards need. This one answers "are they up?", which is what the start side needs.
   *
   * Volatile: written and read on whichever app thread drives start and stop.
   */
  @Volatile
  private var sourcesRunning = false

  /**
   * Idempotent and transactional.
   *
   * Idempotent so a caller does not have to infer from a consumer flag whether the sources are
   * already up; a second call does nothing rather than crashing on MediaCodec state. Transactional
   * so a partial failure cannot leave half a lifecycle running, which is what turned one failed
   * attempt into a permanent wedge.
   *
   * [sourcesRunning] is set before the body so the cleanup path can run, and every line of
   * [stopSourcesImp] is individually safe against a component that never started.
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
   * The teardown without the [sourcesRunning] guard. [release] calls this so a release on a
   * never-started stream still runs every line.
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
    // The unguarded teardown: a release on a never-started stream must still run every line.
    sourcesRunning = false
    stopSourcesImp()
    videoSource.release()
    audioSource.release()
    if (glInterface.isRunning) glInterface.surfaceTexture.tryClear()
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
      // reset() is a replay -- stop, no-arg prepare, restart -- so it may only leave the claim alone
      // or clear it, never set it. Same reasoning as prepareEncoders().
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
      // A replay: it re-sends the stored profile and level while reading the current codec, so if
      // anything moved the codec since the last real prepare, the encoder now runs a codec whose
      // profile and level were never derived for it. Leave the claim alone or clear it, never set.
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
      // Surface the stream encoder's negotiated format unconditionally. The record routing below is
      // gated on !differentRecordResolution, so a caller that passes record dimensions previously
      // had no way to see this format at all.
      lastStreamVideoFormat = mediaFormat
      try {
        streamVideoFormatListener?.invoke(mediaFormat)
      } catch (e: Exception) {
        // This runs on the MediaCodec callback thread, and onOutputFormatChanged is the one Callback
        // method BaseEncoder does not wrap, since onInput and onOutputBufferAvailable both catch
        // into reloadCodec. An exception from consumer code would propagate uncaught and end the
        // process mid-stream. Error is deliberately not caught: an unsurvivable JVM state should not
        // be turned into a silently wedged pipeline.
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

  /**
   * GPX R2 — force VBR bitrate mode on the next prepare of either encoder.
   */
  fun setTryForceVBRBitrateMode(forVideoEncoder: Boolean, forVideoEncoderRecord: Boolean) {
    if (forVideoEncoder) videoEncoder.setTryForceVBRBitrateMode(true)
    if (forVideoEncoderRecord) videoEncoderRecord.setTryForceVBRBitrateMode(true)
  }

  /**
   * GPX R18 — a codec-namespaced profile and level pair, kept together because the
   * MediaCodecInfo.CodecProfileLevel constants mean different things per codec, and a pair that
   * travels apart from its codec is how a cross-namespace mismatch happens.
   */
  data class ProfileLevel(val profile: Int, val level: Int)

  /**
   * GPX R19 — true if [applyVideoRecCodec] would have to re-prepare the record encoder.
   *
   * Lets a caller decide whether it needs to derive a profile and level before calling. It is the
   * same predicate applyVideoRecCodec uses internally, exposed once so the two cannot diverge.
   *
   * False has two distinct meanings: already prepared with this codec, so the apply is a label-only
   * no-op, and there is no record encoder at all, where the apply refuses and returns false. A
   * caller using this to ask "is the record encoder already on this codec?" must therefore treat the
   * apply's own false as authoritative.
   */
  fun recordCodecNeedsReprepare(codec: VideoCodec): Boolean {
    if (!differentRecordResolution) return false
    return recordCodecPrepared != codec || !videoEncoderRecord.isPrepared()
  }

  /**
   * GPX R19 — apply the record codec coherently to an already-prepared encoder.
   *
   * Either the record encoder's codec, its profile and level, and the muxer's label all describe
   * [codec] when this returns true, or nothing is claimed and it returns false. Setting the codec
   * and the label separately is how a container ends up declaring a codec its bitstream does not
   * match.
   *
   * @param pair profile and level derived for [codec]. May be null only when the caller believes
   *   this is a no-op; if a re-prepare turns out to be needed, this fails rather than preparing with
   *   a sentinel.
   * @return true if the encoder and the label now agree on [codec]. False means do not start
   *   recording: the label was left describing the last bitstream that actually existed.
   */
  fun applyVideoRecCodec(codec: VideoCodec, pair: ProfileLevel?): Boolean {
    if (isRecording) {
      throw IllegalStateException("stopRecord before changing the record codec")
    }
    // Recording taps the stream encoder when no record encoder is prepared, so the record codec is
    // not this method's to set. Keyed on differentRecordResolution rather than isPrepared(): that
    // flag alone governs whether the record encoder is started, attached and read.
    if (!differentRecordResolution) return false

    if (!recordCodecNeedsReprepare(codec)) {
      recordController.setVideoCodec(codec)
      return true
    }
    if (pair == null) {
      // The caller expected a no-op and derived no pair. Preparing with a sentinel would hand the
      // encoder a profile and level in no particular codec namespace.
      recordCodecPrepared = null
      return false
    }

    val wasRunning = videoEncoderRecord.isRunning
    // Start and attach here exactly when startRecord's own startSources() will not: while streaming
    // it is a no-op, so this is the only thing that can, and that holds even when the encoder is not
    // currently running after a previously failed apply.
    val mustStartHere = wasRunning || isStreaming

    glInterface.removeMediaCodecRecordSurface()
    // GPX R9 — stop(false), not stop(): preserves the timestamp baseline so a recording spanning a
    // codec
    // change keeps monotonic PTS. prepareVideoEncoder() would stop() it anyway; this is what makes
    // that stop non-resetting.
    videoEncoderRecord.stop(false)

    // From here the encoder's codec no longer matches recordCodecPrepared, so every exit, including
    // an exception, must leave the claim correct. restart() reaches MediaCodec.start(), which
    // genuinely throws on this path: it brings up a second hardware video encoder while the stream
    // encoder and camera are live, which is at or past the concurrent-encoder limit on some SoCs.
    //
    // Clearing on the way out is the safe direction. Leaving the old codec's name against an encoder
    // now holding the new one is an over-report: a later change back to the old codec would see a
    // match, take the label-only no-op branch, and write that codec's label over this codec's
    // bitstream, visible only in the written file.
    try {
      videoEncoderRecord.type = codec
      val prepared = videoEncoderRecord.prepareVideoEncoder(pair.profile, pair.level)
      if (!prepared) {
        recordCodecPrepared = null
        return false
      }
      // A new prepare means a new negotiated format is coming; the old one describes the previous
      // codec and would otherwise be replayed into a freshly installed record controller. Cleared on
      // this branch only: clearing it on the no-op branch would starve a fresh controller of any
      // format at all, because no second onOutputFormatChanged is coming.
      lastVideoFormat = null
      if (mustStartHere) {
        videoEncoderRecord.restart()
        glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
      }
      recordCodecPrepared = codec
      // Label last, so it is only advanced once the bitstream behind it exists.
      recordController.setVideoCodec(codec)
      return true
    } catch (t: Throwable) {
      recordCodecPrepared = null
      throw t
    }
  }

  /**
   * GPX R28 — re-prepare the STREAM video encoder with new parameters while the record encoder,
   * the sources and the muxer keep running (gpxstream-app S8 gate, F2; R-STR-14's stream-encoder
   * tier).
   *
   * [prepareVideo] refuses unless stream, record and preview are all stopped, so a stream-side
   * change while a recording rolls would force the recording down. This is the scoped
   * alternative: only the stream encoder is detached, re-prepared and (if the sources are up)
   * restarted; the record encoder, its surface and the muxer are never touched.
   *
   * Preconditions, each thrown as the caller's error:
   * - the stream must be stopped ([isStreaming] false) — the caller's quiesce; re-preparing the
   *   encoder under a live sender would swap the bitstream mid-connection.
   * - a dedicated record encoder must exist ([prepareVideo] with record dimensions): with a
   *   shared encoder the recording taps this encoder, so there is nothing scoped to rebuild —
   *   use the full [prepareVideo] path there.
   * - [width]x[height] must keep the record encoder's aspect ratio — the same invariant
   *   [prepareVideo] establishes between the two encoders.
   *
   * Fps and rotation are deliberately not parameters: both are shared-engine facts (the GL
   * plane and both encoders read them), so a change to either is a full [prepareVideo] rebuild.
   * The shared video source is not re-inited — it keeps capturing at the size [prepareVideo]
   * chose, so a [width]x[height] larger than that capture is upscaled by the GL plane until the
   * next full rebuild.
   *
   * [codec] non-null moves the stream codec: it is applied to the encoder immediately before
   * the prepare that realises it and to the protocol layer ([setVideoCodecImp]) only after that
   * prepare succeeded — the [setVideoCodec] ordering, without its [resetVideoEncoder] call that
   * would also bounce the record encoder mid-recording.
   *
   * @return true when the encoder is prepared (and restarted, surface re-attached, when the
   * sources are up). False means nothing is claimed: the encoder is stopped and the caller must
   * go through [prepareVideo] before the next start.
   */
  @JvmOverloads
  fun applyVideoStreamConfig(
    width: Int, height: Int, bitrate: Int,
    iFrameInterval: Int = 2,
    profile: Int = -1, level: Int = -1,
    codec: VideoCodec? = null,
  ): Boolean {
    if (isStreaming) {
      throw IllegalStateException("stopStream before changing the stream encoder")
    }
    if (!differentRecordResolution) {
      throw IllegalStateException("no dedicated record encoder; use prepareVideo")
    }
    val recordWidth = videoEncoderRecord.width
    val recordHeight = videoEncoderRecord.height
    if (width.toDouble() / height.toDouble() != recordWidth.toDouble() / recordHeight.toDouble()) {
      throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
    }

    val wasRunning = videoEncoder.isRunning
    // Start and attach below exactly when no start path will: while the sources are up (a live
    // recording holds them), nothing else restarts this encoder — the applyVideoRecCodec rule,
    // mirrored to the stream side.
    val mustStartHere = wasRunning || sourcesRunning
    glInterface.removeMediaCodecSurface()
    // GPX R9's discipline: stop(false) preserves the timestamp baseline, so the record encoder's
    // clock and this one stay on the same epoch when both feed one session later.
    videoEncoder.stop(false)
    // A new prepare means a new negotiated format is coming; the old one describes the previous
    // configuration.
    lastStreamVideoFormat = null
    if (codec != null) videoEncoder.type = codec
    val rotation = videoEncoder.rotation
    val prepared = videoEncoder.prepareVideoEncoder(width, height, videoEncoder.fps, bitrate,
      rotation, iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
    if (!prepared) return false
    if (rotation == 90 || rotation == 270) glInterface.setEncoderSize(height, width)
    else glInterface.setEncoderSize(width, height)
    // The protocol layer moves only once the bitstream behind it exists — setVideoCodec's
    // label-last ordering.
    if (codec != null) setVideoCodecImp(codec)
    if (mustStartHere) {
      videoEncoder.restart()
      glInterface.addMediaCodecSurface(videoEncoder.inputSurface)
    }
    return true
  }

  /**
   * GPX R28 — re-prepare the RECORD video encoder with new parameters while the stream encoder
   * and the sources keep running (gpxstream-app S8 gate, F2; the record-encoder tier).
   *
   * The full-parameter generalization of [applyVideoRecCodec]: resolution, bitrate, VBR, codec
   * and the codec-namespaced profile/level move together under the same claim-and-label
   * discipline — either the encoder, [recordCodecPrepared] and the muxer's label all describe
   * the new configuration on true, or nothing is claimed. The recording itself must be stopped
   * first (the caller stop-confirms; a mid-file parameter change is not a muxable event), but
   * the stream, the sources and the stream encoder are never touched.
   *
   * Preconditions: record stopped (thrown), a dedicated record encoder (false, the
   * [applyVideoRecCodec] rule — with a shared encoder the record parameters are not this
   * method's to set), and the stream encoder's aspect ratio kept (thrown). The shared video
   * source is not re-inited; see [applyVideoStreamConfig] on capture-size upscaling.
   *
   * @param recordProfile profile derived for the codec the encoder will hold ([recordCodec], or
   * the current one when null) — the pair travels with its codec, never across namespaces.
   * @return true when the encoder is prepared (and restarted, surface re-attached, when the
   * sources are up). False means nothing is claimed and recording must not start until a
   * successful prepare.
   */
  @JvmOverloads
  fun applyVideoRecConfig(
    recordWidth: Int, recordHeight: Int, recordBitrate: Int,
    forceRecordVbr: Boolean,
    iFrameInterval: Int = 2,
    recordProfile: Int = -1, recordLevel: Int = -1,
    recordCodec: VideoCodec? = null,
  ): Boolean {
    if (isRecording) {
      throw IllegalStateException("stopRecord before changing the record encoder")
    }
    if (!differentRecordResolution) return false
    val streamWidth = videoEncoder.width
    val streamHeight = videoEncoder.height
    if (recordWidth.toDouble() / recordHeight.toDouble() != streamWidth.toDouble() / streamHeight.toDouble()) {
      throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
    }

    val wasRunning = videoEncoderRecord.isRunning
    // The applyVideoRecCodec rule: while streaming, startRecord's startSources() is a no-op, so
    // this is the only site that can restart and re-attach the record encoder.
    val mustStartHere = wasRunning || isStreaming
    glInterface.removeMediaCodecRecordSurface()
    // GPX R9 — stop(false) preserves the timestamp baseline across the re-prepare.
    videoEncoderRecord.stop(false)
    // From here the encoder no longer matches recordCodecPrepared; every exit must leave the
    // claim correct (the applyVideoRecCodec reasoning — clearing is the safe direction).
    try {
      videoEncoderRecord.setTryForceVBRBitrateMode(forceRecordVbr)
      if (recordCodec != null) videoEncoderRecord.type = recordCodec
      val rotation = videoEncoderRecord.rotation
      val prepared = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight,
        videoEncoderRecord.fps, recordBitrate, rotation, iFrameInterval,
        FormatVideoEncoder.SURFACE, recordProfile, recordLevel)
      if (!prepared) {
        recordCodecPrepared = null
        return false
      }
      // A new prepare means a new negotiated format is coming; the old one describes the
      // previous configuration and would otherwise be replayed into a fresh record controller.
      lastVideoFormat = null
      if (rotation == 90 || rotation == 270) glInterface.setEncoderRecordSize(recordHeight, recordWidth)
      else glInterface.setEncoderRecordSize(recordWidth, recordHeight)
      if (mustStartHere) {
        videoEncoderRecord.restart()
        glInterface.addMediaCodecRecordSurface(videoEncoderRecord.inputSurface)
      }
      // This site applies the codec together with a profile and level derived for it, so it may
      // set the claim (see recordCodecPrepared's KDoc).
      recordCodecPrepared = recordEncoderCodec()
      // Label last, so the muxer only ever names a codec whose bitstream already exists.
      if (recordCodec != null) recordController.setVideoCodec(recordCodec)
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
    videoEncoder.type = codec
    // The record codec is controlled separately: see prepareVideo's recordCodec parameter and
    // [applyVideoRecCodec].
    //
    // Guarded on whether the encoder is running, not on isStreaming. startRecord starts the stream
    // encoder too, through startSources, so record-without-stream runs it with isStreaming false,
    // and a guard on isStreaming would move the codec on a live encoder with no reset. The codec is
    // read at runtime, not only at the next prepare, because VideoEncoder.sendSPSandPPS branches on
    // it, so the SPS/PPS handed to the record controller would be parsed under the new codec's rules
    // against the old codec's bitstream.
    if (videoEncoder.isRunning) {
      Log.i("StreamBase", "setVideoCodec: encoder running, resetting video encoder for codec=${codec.name}")
      if (!resetVideoEncoder()) {
        throw IllegalStateException("Failed to reset video encoder after codec change")
      }
    }
    // After the reset: a failed reset would otherwise throw with the publisher already advanced to a
    // codec the encoder is not producing. Still before requestKeyframe, so the SPS/PPS that keyframe
    // drives out reach a publisher already on the new codec.
    setVideoCodecImp(codec)
    if (videoEncoder.isRunning) requestKeyframe()
  }

  /**
   * Change AudioCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example G711 is not supported in SRT
   */
  fun setAudioCodec(codec: AudioCodec) {
    audioEncoder.type = codec
    // The audio twin of setVideoCodec. Two of the three writes took effect immediately -- the
    // publisher's sender and the muxer's label -- while the encoder's codec took effect only at the
    // next prepareAudioEncoder, with no guard. Called while the audio encoder was running, that left
    // the container labelled one codec over another codec's bitstream.
    //
    // The codec is also read at runtime by the running pipeline, in BaseEncoder's G711 branches in
    // setCallback, initCodec and getDataFromEncoder, so a deferred change is not merely late: it
    // makes live code branch under the new codec's rules against a codec object built for the old.
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