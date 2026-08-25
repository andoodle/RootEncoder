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

package com.pedro.library.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.common.TimeUtils
import com.pedro.common.newSingleThreadExecutor
import com.pedro.encoder.input.gl.FilterAction
import com.pedro.encoder.input.gl.SurfaceManager
import com.pedro.encoder.input.gl.render.MainRender
import com.pedro.encoder.input.gl.render.StreamOverlayRender
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.FpsLimiter
import com.pedro.encoder.utils.ViewPort
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.library.util.Filter
import com.pedro.library.util.SensorRotationManager
import com.pedro.library.view.preview.MultiPreviewConfig
import com.pedro.library.view.preview.PreviewSurfaceInfo
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

// GPX R16 — bound for stop()'s wait on the queued releaseSurfaceManagers() task. Long enough for a
// release, short enough that a caller blocked in stop() -- which can reach the main thread through
// StreamBase.stopPreview()'s SurfaceHolder callback -- is not held for long.
private const val STOP_RELEASE_AWAIT_MS = 300L

// GPX R34 — bound for start()'s wait on the queued GL-init task. Matches secureSubmit's previous
// default timeout so normal-path timing is unchanged; the difference is that a miss now throws
// instead of being swallowed. See the GPX R34 comment on start() for why.
private const val START_INIT_AWAIT_MS = 5000L

/**
 * Created by pedro on 14/3/22.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class GlStreamInterface(private val context: Context): OnFrameAvailableListener, GlInterface {

  private var takePhotoCallback: TakePhotoCallback? = null
  private val running = AtomicBoolean(false)
  private val surfaceManager = SurfaceManager()
  private val surfaceManagerEncoder = SurfaceManager()
  private val surfaceManagerEncoderRecord = SurfaceManager()
  private val surfaceManagerPhoto = SurfaceManager()
  private val surfaceManagerPreview = SurfaceManager()
  private val multiPreviewSurfaceManagers = ConcurrentHashMap<Surface, PreviewSurfaceInfo>()
  private val mainRender = MainRender()
  // GPX R11 — drawn into the stream encoder surface only, never the record encoder, preview or photo.
  private val streamOverlayRender = StreamOverlayRender()
  // GPX fork change 8 — the record-target counterpart: drawn into the record encoder surface only,
  // never the stream encoder, preview or photo. A second independent StreamOverlayRender instance
  // rather than a new class — that class is already generic, see its own KDoc.
  private val recordOverlayRender = StreamOverlayRender()

  private var encoderWidth = 0
  private var encoderHeight = 0
  private var encoderRecordWidth = 0
  private var encoderRecordHeight = 0
  // GPX R5 — size the next photo capture renders and reads back at. The one-argument takePhoto sets
  // it to the encoder size, so a caller that does not ask for a size keeps the encoder size.
  private var photoWidth = 0
  private var photoHeight = 0
  private var streamOrientation = 0
  private var previewOrientation = 0
  private var previewWidth = 0
  private var previewHeight = 0
  private var isPortrait = false
  private var isPortraitPreview = false
  private var orientationForced = OrientationForced.NONE
  private val filterQueue: BlockingQueue<Filter> = LinkedBlockingQueue()
  private val threadQueue = LinkedBlockingQueue<Runnable>()
  private var muteVideo = false
  private var isPreviewHorizontalFlip: Boolean = false
  private var isPreviewVerticalFlip = false
  private var isStreamHorizontalFlip = false
  private var isStreamVerticalFlip = false
  private var aspectRatioMode = AspectRatioMode.Adjust
  private var executor: ExecutorService? = null
  // GPX R16 — the teardown submitted by the last stop(). start() waits on it before reusing GL state.
  private var pendingRelease: Future<*>? = null
  private val fpsLimiter = FpsLimiter()
  private val forceRender = ForceRenderer()
  var autoHandleOrientation = false
  private var shouldHandleOrientation = true
  private var renderErrorCallback: RenderErrorCallback? = null
  private var previewViewPort: ViewPort? = null
  private var streamViewPort: ViewPort? = null
  private var surfaceHandlerThread: HandlerThread? = null
  private val sync = Any()
  private val glTimestamp = GlTimestamp()

  private val sensorRotationManager = SensorRotationManager(context, true, true) { orientation, isPortrait ->
    if (autoHandleOrientation && shouldHandleOrientation) {
      setCameraOrientation(orientation)
      setIsPortrait(isPortrait)
    }
  }

  override fun setEncoderSize(width: Int, height: Int) {
    encoderWidth = width
    encoderHeight = height
  }

  override fun setEncoderRecordSize(width: Int, height: Int) {
    encoderRecordWidth = width
    encoderRecordHeight = height
  }

  override fun getEncoderSize(): Point {
    return Point(encoderWidth, encoderHeight)
  }

  override fun muteVideo() {
    muteVideo = true
  }

  override fun unMuteVideo() {
    muteVideo = false
  }

  override fun isVideoMuted(): Boolean = muteVideo

  override fun setForceRender(enabled: Boolean, fps: Int) {
    forceRender.setEnabled(enabled, fps)
    // GPX R11 — apply the toggle to a render loop already live. start() is the only other place that
    // reads the flag, so without this a caller that enables force-render mid-session — because the
    // camera input died and the encoder must keep producing frames for an overlay — gets nothing
    // until the next GL restart.
    if (running.get()) {
      if (enabled && !forceRender.isRunning()) {
        forceRender.start(forceRenderCallback)
      } else if (!enabled && forceRender.isRunning()) {
        forceRender.stop()
      }
    }
  }

  override fun setForceRender(enabled: Boolean) {
    setForceRender(enabled, 5)
  }

  // GPX R11 — shared by start() and the live setForceRender toggle above. Body follows upstream's
  // timestamped force-render draw: the clock is read under sync and travels with the task.
  private val forceRenderCallback: () -> Unit = {
    synchronized(sync) {
      val timestamp = TimeUtils.getCurrentTimeNano()
      executor?.execute {
        try {
          draw(true, timestamp)
        } catch (e: RuntimeException) {
          renderErrorCallback?.onRenderError(e) ?: throw e
        }
      }
    }
    Unit
  }

  // GPX R11 — the working implementation of the stream overlay plane. OpenGlView's override of
  // this is a no-op, so a caller typed to GlInterface loses the overlay silently.
  override fun setStreamOverlay(bitmap: Bitmap?) {
    streamOverlayRender.setBitmap(bitmap)
  }

  // GPX fork change 8 — the record-target counterpart to setStreamOverlay above. Same no-op-on-
  // OpenGlView caveat applies.
  override fun setRecordOverlay(bitmap: Bitmap?) {
    recordOverlayRender.setBitmap(bitmap)
  }

  override fun isRunning(): Boolean = running.get()

  override fun setRenderErrorCallback(callback: RenderErrorCallback?) {
    this.renderErrorCallback = callback
  }

  override fun getSurfaceTexture(): SurfaceTexture {
    return mainRender.getSurfaceTexture()
  }

  override fun getSurface(): Surface {
    return mainRender.getSurface()
  }

  override fun addMediaCodecSurface(surface: Surface) {
    executor?.submit {
      if (surfaceManager.isReady) {
        surfaceManagerEncoder.release()
        surfaceManagerEncoder.eglSetup(surface, surfaceManager)
      }
    }
  }

  override fun removeMediaCodecSurface() {
    executor?.submit {
      surfaceManagerEncoder.release()
    }
  }

  override fun addMediaCodecRecordSurface(surface: Surface) {
    executor?.submit {
      if (surfaceManager.isReady) {
        surfaceManagerEncoderRecord.release()
        surfaceManagerEncoderRecord.eglSetup(surface, surfaceManager)
      }
    }
  }

  override fun removeMediaCodecRecordSurface() {
    executor?.submit {
      surfaceManagerEncoderRecord.release()
    }
  }

  override fun takePhoto(takePhotoCallback: TakePhotoCallback?) {
    this.takePhotoCallback = takePhotoCallback
    this.photoWidth = encoderWidth
    this.photoHeight = encoderHeight
  }

  // GPX R5 — capture at an explicit size rather than the encoder size.
  override fun takePhoto(width: Int, height: Int, takePhotoCallback: TakePhotoCallback?) {
    this.takePhotoCallback = takePhotoCallback
    this.photoWidth = width
    this.photoHeight = height
  }

  override fun start() {
    // GPX R16 — do not touch shared GL state until the previous stop()'s release finished. get() is
    // called unconditionally, not only when the task is unfinished, so an already-failed release is
    // not skipped. Retrying belongs to the caller, not here.
    pendingRelease?.let { release ->
      try {
        release.get(STOP_RELEASE_AWAIT_MS, TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        throw IllegalStateException(
          "GlStreamInterface.start(): prior stop() release did not complete within " +
            "${STOP_RELEASE_AWAIT_MS}ms; refusing to reuse shared GL state. Caller must retry " +
            "the whole start operation.", e
        )
      } catch (e: ExecutionException) {
        throw IllegalStateException(
          "GlStreamInterface.start(): prior stop() release failed; refusing to reuse shared GL state.",
          e.cause ?: e
        )
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException(
          "GlStreamInterface.start(): interrupted waiting for prior stop() release; refusing to " +
            "reuse shared GL state.", e
        )
      }
    }
    pendingRelease = null
    glTimestamp.reset()
    threadQueue.clear()
    executor?.shutdownNow()
    executor = null
    executor = newSingleThreadExecutor(threadQueue)
    surfaceHandlerThread?.quitSafely()
    surfaceHandlerThread = HandlerThread("GlStreamHandler")
    surfaceHandlerThread?.start()
    val width = max(encoderWidth, encoderRecordWidth)
    val height = max(encoderHeight, encoderRecordHeight)
    surfaceManager.release()
    surfaceManager.eglSetup()
    surfaceManagerPhoto.release()
    surfaceManagerPhoto.eglSetup(width, height, surfaceManager)
    sensorRotationManager.start()
    // GPX R34 — track the GL-init task and await it before returning, mirroring the pendingRelease
    // wait above. The previous executor?.secureSubmit { ... } here also blocked the caller, but on a
    // timeout or a thrown exception it swallowed both silently (secureSubmit's catch block is empty)
    // and start() returned as if GL init had finished, with `running` still false and mainRender
    // possibly left half-initialized. Every caller -- StreamBase.startPreview(), warmSources() and
    // startSources() -- reads glInterface.surfaceTexture on the very next line with no wait of its
    // own, so a swallowed failure handed the camera a not-yet-created or stale SurfaceTexture. This
    // was the root cause of gpxstream-app issue #108: a preview-only race that surfaced as a false
    // "CAMERA DIDN'T START" error over an otherwise-healthy headless recording.
    val initTask = executor?.submit {
      surfaceManager.makeCurrent()
      mainRender.initGl(context, width, height, width, height)
      running.set(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val surfaceHandler = surfaceHandlerThread?.looper?.let { Handler(it) }
        mainRender.getSurfaceTexture().setOnFrameAvailableListener(this, surfaceHandler)
      } else {
        mainRender.getSurfaceTexture().setOnFrameAvailableListener(this)
      }
      forceRender.start(forceRenderCallback)
    }
    initTask?.let { task ->
      try {
        task.get(START_INIT_AWAIT_MS, TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        throw IllegalStateException(
          "GlStreamInterface.start(): GL init did not complete within ${START_INIT_AWAIT_MS}ms; " +
            "surfaceTexture is not safe to read yet.", e
        )
      } catch (e: ExecutionException) {
        throw IllegalStateException(
          "GlStreamInterface.start(): GL init failed.", e.cause ?: e
        )
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException(
          "GlStreamInterface.start(): interrupted waiting for GL init.", e
        )
      }
    }
  }

  override fun stop() {
    running.set(false)
    forceRender.stop()
    surfaceHandlerThread?.quitSafely()
    surfaceHandlerThread = null
    threadQueue.clear()
    val executor = this.executor
    if (executor != null) {
      // GPX R16 — shutdown, not shutdownNow: the submitted release must run rather than be raced
      // against cancellation. submit keeps the Future alive past this bounded wait so start() can
      // observe whether it completed.
      pendingRelease = executor.submit { releaseSurfaceManagers() }
      executor.shutdown()
      try {
        executor.awaitTermination(STOP_RELEASE_AWAIT_MS, TimeUnit.MILLISECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      this.executor = null
    } else if (pendingRelease == null) {
      // GPX R16 — release() can reach stop() twice; do not race a teardown already tracked.
      releaseSurfaceManagers()
    }
  }

  private fun releaseSurfaceManagers() {
    sensorRotationManager.stop()
    surfaceManagerPhoto.release()
    surfaceManagerEncoder.release()
    surfaceManagerEncoderRecord.release()
    multiPreviewSurfaceManagers.values.forEach { info ->
      info.surfaceManager.release()
    }
    multiPreviewSurfaceManagers.clear()
    surfaceManagerPreview.release()
    surfaceManager.release()
    mainRender.release()
    // GPX R11 — the EGL context that owned the overlay's GL objects is gone, so invalidate its
    // handles. The
    // next draw on a fresh context re-creates them and re-uploads a still-visible bitmap.
    streamOverlayRender.release()
    // GPX fork change 8 — same reasoning, for the record-target overlay plane.
    recordOverlayRender.release()
  }

  private fun draw(forced: Boolean, clockTimestamp: Long) {
    if (!isRunning) return
    if (!forced) forceRender.frameAvailable()

    if (!filterQueue.isEmpty() && mainRender.isReady()) {
      try {
        if (surfaceManager.makeCurrent()) {
          val filter = filterQueue.take()
          mainRender.setFilterAction(filter.filterAction, filter.position, filter.baseFilterRender)
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }
    }

    if (surfaceManager.isReady && mainRender.isReady()) {
      if (!surfaceManager.makeCurrent()) return
      mainRender.updateFrame()
      mainRender.drawSource()
    }
    val timestamp = glTimestamp.getTimestamp(surfaceTexture.timestamp, clockTimestamp)
    val limitFps = fpsLimiter.limitFPS(timestamp)

    val orientation = when (orientationForced) {
      OrientationForced.PORTRAIT -> true
      OrientationForced.LANDSCAPE -> false
      OrientationForced.NONE -> isPortrait
    }
    val orientationPreview = when (orientationForced) {
      OrientationForced.PORTRAIT -> true
      OrientationForced.LANDSCAPE -> false
      OrientationForced.NONE -> isPortraitPreview
    }
    if (surfaceManagerEncoder.isReady || surfaceManagerEncoderRecord.isReady || surfaceManagerPhoto.isReady) {
      mainRender.drawFilters(false)
    }
    // render VideoEncoder (stream and record)
    if (surfaceManagerEncoder.isReady && mainRender.isReady() && !limitFps) {
      val w = if (muteVideo) 0 else encoderWidth
      val h = if (muteVideo) 0 else encoderHeight
      if (surfaceManagerEncoder.makeCurrent()) {
        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,
          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)
        // GPX R11 — drawn over the frame content into this surface only. The record encoder branch
        // renders from the same filtered texture but draws its own recordOverlayRender instead
        // (GPX fork change 8), not this one.
        streamOverlayRender.draw(context)
        surfaceManagerEncoder.setPresentationTime(timestamp)
        surfaceManagerEncoder.swapBuffer()
      }
    }
    // render VideoEncoder (record if the resolution is different than stream)
    if (surfaceManagerEncoderRecord.isReady && mainRender.isReady() && !limitFps) {
      val w = if (muteVideo) 0 else encoderRecordWidth
      val h = if (muteVideo) 0 else encoderRecordHeight
      if (surfaceManagerEncoderRecord.makeCurrent()) {
        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,
          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)
        // GPX fork change 8 — this destination's own overlay plane, independent of streamOverlayRender.
        recordOverlayRender.draw(context)
        // Fix: same timestamp fix for the dedicated record surface
        surfaceManagerEncoderRecord.setPresentationTime(timestamp)
        surfaceManagerEncoderRecord.swapBuffer()
      }
    }
    //render surface photo if request photo
    if (takePhotoCallback != null && surfaceManagerPhoto.isReady && mainRender.isReady()) {
      if (surfaceManagerPhoto.makeCurrent()) {
        mainRender.drawScreen(photoWidth, photoHeight, AspectRatioMode.NONE,
          streamOrientation, isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)
        // GPX fork change 9 — burn the RECORDING-destination overlay composite into the photo
        // readback too, same call shape as recordOverlayRender.draw(context) above. Without this a
        // photo taken while a RECORDING-destined overlay item is showing carried no overlay, so a
        // VOD thumbnail (the app's only photo-capture caller) never matched the recording it was a
        // thumbnail of.
        recordOverlayRender.draw(context)
        takePhotoCallback?.onTakePhoto(GlUtil.getBitmap(photoWidth, photoHeight))
        takePhotoCallback = null
        surfaceManagerPhoto.swapBuffer()
      }
    }
    // render preview
    if (surfaceManagerPreview.isReady && mainRender.isReady() && !limitFps) {
      val w =  if (previewWidth == 0) encoderWidth else previewWidth
      val h =  if (previewHeight == 0) encoderHeight else previewHeight
      if (surfaceManager.makeCurrent()) {
        mainRender.drawFilters(true)
        surfaceManager.swapBuffer()
      }
      if (surfaceManagerPreview.makeCurrent()) {
        mainRender.drawScreenPreview(w, h, orientationPreview, aspectRatioMode, previewOrientation,
          isPreviewVerticalFlip, isPreviewHorizontalFlip, previewViewPort)
        surfaceManagerPreview.swapBuffer()
      }
    }
    // render extra multi-preview surfaces (using independent configuration from PreviewSurfaceInfo)
    if (multiPreviewSurfaceManagers.isNotEmpty() && mainRender.isReady() && !limitFps) {
      // Only draw filters if default preview is not active (to avoid double drawing)
      if (!surfaceManagerPreview.isReady) {
        if (surfaceManager.makeCurrent()) {
          mainRender.drawFilters(true)
          surfaceManager.swapBuffer()
        }
      }
      val previewSnapshot = multiPreviewSurfaceManagers.values.toList()
      previewSnapshot.forEach { info ->
        if (info.surfaceManager.isReady) {
          if (info.surfaceManager.makeCurrent()) {
            // Each preview uses its own isPortrait and viewPort configuration
            mainRender.drawScreenPreview(info.config.width, info.config.height, info.config.isPortrait, info.config.aspectRatioMode, 0,
              info.config.verticalFlip, info.config.horizontalFlip, info.config.viewPort)
            info.surfaceManager.swapBuffer()
          }
        }
      }
    }
  }

  override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
    if (!isRunning) return
    synchronized(sync) {
      val timestamp = TimeUtils.getCurrentTimeNano()
      executor?.execute {
        try {
          draw(false, timestamp)
        } catch (e: RuntimeException) {
          renderErrorCallback?.onRenderError(e) ?: throw e
        }
      }
    }
  }

  fun setOrientationConfig(orientationConfig: OrientationConfig) {
    when (orientationConfig.forced) {
      OrientationForced.PORTRAIT, OrientationForced.LANDSCAPE -> {
        forceOrientation(orientationConfig.forced)
      }
      OrientationForced.NONE -> {
        if (orientationConfig.isPortrait == null && orientationConfig.cameraOrientation == null) {
          forceOrientation(orientationConfig.forced)
        } else {
          orientationConfig.isPortrait?.let { setIsPortrait(it) }
          orientationConfig.cameraOrientation?.let { setCameraOrientation(it) }
          shouldHandleOrientation = false
          this.orientationForced = orientationConfig.forced
        }
      }
    }
  }

  fun forceOrientation(forced: OrientationForced) {
    when (forced) {
      OrientationForced.PORTRAIT -> {
        setCameraOrientation(90)
        shouldHandleOrientation = false
      }
      OrientationForced.LANDSCAPE -> {
        setCameraOrientation(0)
        shouldHandleOrientation = false
      }
      OrientationForced.NONE -> {
        val orientation = CameraHelper.getCameraOrientation(context)
        setCameraOrientation(if (orientation == 0) 270 else orientation - 90)
        shouldHandleOrientation = true
      }
    }
    this.orientationForced = forced
  }

  fun attachPreview(surface: Surface) {
    if (surfaceManager.isReady) {
      surfaceManagerPreview.release()
      surfaceManagerPreview.eglSetup(surface, surfaceManager)
    }
  }

  fun deAttachPreview() {
    surfaceManagerPreview.release()
  }

  /**
   * Add a multi-preview surface
   * @param surface the surface to add
   * @param config configuration for the preview surface
   */
  fun addMultiPreviewSurface(surface: Surface, config: MultiPreviewConfig) {
    if (surfaceManager.isReady) {
      multiPreviewSurfaceManagers.remove(surface)?.surfaceManager?.release()

      val w = if (config.width > 0) config.width else if (previewWidth == 0) encoderWidth else previewWidth
      val h = if (config.height > 0) config.height else if (previewHeight == 0) encoderHeight else previewHeight

      val surfaceManager = SurfaceManager()
      surfaceManager.eglSetup(surface, this@GlStreamInterface.surfaceManager)
      val finalConfig = MultiPreviewConfig(
        w,
        h,
        config.horizontalFlip,
        config.verticalFlip,
        config.aspectRatioMode,
        config.isPortrait,
        config.viewPort
      )
      multiPreviewSurfaceManagers[surface] = PreviewSurfaceInfo(surfaceManager, finalConfig)
    }
  }

  fun removeMultiPreviewSurface(surface: Surface) {
    multiPreviewSurfaceManagers.remove(surface)?.surfaceManager?.release()
  }

  fun removeAllMultiPreviewSurfaces() {
    multiPreviewSurfaceManagers.values.forEach { info ->
      info.surfaceManager.release()
    }
    multiPreviewSurfaceManagers.clear()
  }

  fun updateMultiPreviewConfig(surface: Surface, config: MultiPreviewConfig): Boolean {
    val info = multiPreviewSurfaceManagers[surface] ?: return false

    info.config.width = if (config.width > 0) config.width else if (previewWidth == 0) encoderWidth else previewWidth
    info.config.height = if (config.height > 0) config.height else if (previewHeight == 0) encoderHeight else previewHeight
    info.config.horizontalFlip = config.horizontalFlip
    info.config.verticalFlip = config.verticalFlip
    info.config.aspectRatioMode = config.aspectRatioMode
    info.config.isPortrait = config.isPortrait
    info.config.viewPort = config.viewPort

    return true
  }

  fun hasMultiPreviewSurface(surface: Surface): Boolean {
    return multiPreviewSurfaceManagers.containsKey(surface)
  }

  fun getMultiPreviewSurfaceCount(): Int = multiPreviewSurfaceManagers.size

  override fun setStreamRotation(orientation: Int) {
    this.streamOrientation = orientation
  }

  fun setPreviewRotation(orientation: Int) {
    this.previewOrientation = orientation
  }


  fun setPreviewResolution(width: Int, height: Int) {
    this.previewWidth = width
    this.previewHeight = height
  }

  fun setIsPortrait(isPortrait: Boolean) {
    setPreviewIsPortrait(isPortrait)
    setStreamIsPortrait(isPortrait)
  }

  fun setPreviewIsPortrait(isPortrait: Boolean) {
    this.isPortraitPreview = isPortrait
  }

  fun setStreamIsPortrait(isPortrait: Boolean) {
    this.isPortrait = isPortrait
  }

  fun setCameraOrientation(orientation: Int) {
    mainRender.setCameraRotation(orientation)
  }

  override fun setFilter(filterPosition: Int, baseFilterRender: BaseFilterRender) {
    filterQueue.add(Filter(FilterAction.SET_INDEX, filterPosition, baseFilterRender))
  }

  override fun addFilter(baseFilterRender: BaseFilterRender) {
    filterQueue.add(Filter(FilterAction.ADD, 0, baseFilterRender))
  }

  override fun addFilter(filterPosition: Int, baseFilterRender: BaseFilterRender) {
    filterQueue.add(Filter(FilterAction.ADD_INDEX, filterPosition, baseFilterRender))
  }

  override fun clearFilters() {
    filterQueue.add(Filter(FilterAction.CLEAR, 0, NoFilterRender()))
  }

  override fun removeFilter(filterPosition: Int) {
    filterQueue.add(Filter(FilterAction.REMOVE_INDEX, filterPosition, NoFilterRender()))
  }

  override fun removeFilter(baseFilterRender: BaseFilterRender) {
    filterQueue.add(Filter(FilterAction.REMOVE, 0, baseFilterRender))
  }

  override fun filtersCount(): Int {
    return mainRender.filtersCount()
  }

  override fun setRotation(rotation: Int) {
    setCameraOrientation(rotation)
  }

  override fun forceFpsLimit(fps: Int) {
    glTimestamp.setFps(fps)
    fpsLimiter.setFPS(fps)
  }

  override fun setIsStreamHorizontalFlip(flip: Boolean) {
    isStreamHorizontalFlip = flip
  }

  override fun setIsStreamVerticalFlip(flip: Boolean) {
    isStreamVerticalFlip = flip
  }

  override fun setIsPreviewHorizontalFlip(flip: Boolean) {
    isPreviewHorizontalFlip = flip
  }

  override fun setIsPreviewVerticalFlip(flip: Boolean) {
    isPreviewVerticalFlip = flip
  }

  override fun setFilter(baseFilterRender: BaseFilterRender) {
    filterQueue.add(Filter(FilterAction.SET, 0, baseFilterRender))
  }

  fun setAspectRatioMode(aspectRatioMode: AspectRatioMode) {
    this.aspectRatioMode = aspectRatioMode
  }

  fun setPreviewViewPort(viewPort: ViewPort?) {
    previewViewPort = viewPort
  }

  fun setStreamViewPort(viewPort: ViewPort?) {
    streamViewPort = viewPort
  }
}