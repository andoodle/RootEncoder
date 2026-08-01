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
    recordWidth: Int = 0, recordHeight: Int = 0, recordBitrate: Int = bitrate
  ): Boolean {
    if (isStreaming || isRecording || isOnPreview) {
      throw IllegalStateException("Stream, record and preview must be stopped before prepareVideo")
    }
    differentRecordResolution = false
    if (recordWidth > 0 && recordHeight > 0) {
      if (recordWidth.toDouble() / recordHeight.toDouble() != width.toDouble() / height.toDouble()) {
        throw IllegalArgumentException("The aspect ratio of record and stream resolution must be the same")
      }
      differentRecordResolution = true
    }
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
        val result = videoEncoderRecord.prepareVideoEncoder(recordWidth, recordHeight, fps, recordBitrate, rotation,
          iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
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
    // Keep the flag and the transport consistent if source startup fails.
    var transportStarted = false
    try {
      startStreamImp(endPoint)
      transportStarted = true
      // Unconditional: startSources() is idempotent on its own state, so this no longer has to infer
      // "are the sources up?" from isRecording, a flag that answers a different question and could
      // be false while they were running.
      startSources()
      // Unconditional keyframe so a viewer joining at connect gets a decodable picture immediately
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
      // Unconditional keyframe so the file is decodable and seekable from its first frame.
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
    // isOnPreview flips only once the sources actually started. stopSources() reads it to decide
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
    videoSource.release()
    if (glInterface.isRunning) glInterface.surfaceTexture.tryClear()
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
    }
  }

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
   * Start the camera and GL half of [startSources] without connecting or starting the encoders, so a
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
      if (!differentRecordResolution) {
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
   * Change VideoCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example AV1 is not supported in SRT
   */
  fun setVideoCodec(codec: VideoCodec) {
    setVideoCodecImp(codec)
    recordController.setVideoCodec(codec)
    videoEncoder.type = codec
    videoEncoderRecord.type = codec
  }

  /**
   * Change AudioCodec used.
   * This could fail depend of the Codec supported in each Protocol. For example G711 is not supported in SRT
   */
  fun setAudioCodec(codec: AudioCodec) {
    setAudioCodecImp(codec)
    recordController.setAudioCodec(codec)
    audioEncoder.type = codec
  }

  protected abstract fun setVideoCodecImp(codec: VideoCodec)
  protected abstract fun setAudioCodecImp(codec: AudioCodec)
}