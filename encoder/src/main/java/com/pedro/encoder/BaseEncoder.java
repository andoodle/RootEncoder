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

package com.pedro.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.common.TimeUtils;
import com.pedro.encoder.audio.G711Codec;
import com.pedro.encoder.utils.CodecUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by pedro on 18/09/19.
 */
public abstract class BaseEncoder implements EncoderCallback {

  protected String TAG = "BaseEncoder";
  protected final G711Codec g711Codec = new G711Codec();
  private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
  private HandlerThread handlerThread;
  private ExecutorService executorService;
  protected BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(80);
  protected MediaCodec codec;
  protected volatile long presentTimeUs;
  protected volatile boolean running = false;
  // GPX fork patch: whether codec.start() has run since the last stop — gates the stop-path flush.
  private volatile boolean codecStarted = false;
  protected boolean isBufferMode = true;
  // When true, timestamp baselines are preserved across stop/start cycles so the output PTS stays
  // monotonic across a restart (e.g. reconnect recovery) instead of rebasing to zero. See
  // forceContinuousTs(). Subclasses keep their own rebase reference (firstTimestamp / tsBuffer).
  protected volatile boolean forceContinuousTs = false;
  protected CodecUtil.CodecType codecType = CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND;
  private MediaCodec.Callback callback;
  private volatile long oldTimeStamp = 0L;
  protected boolean shouldReset = true;
  protected boolean prepared = false;
  private Handler handler;
  private CodecErrorCallback encoderErrorCallback;
  protected String type;
  protected CodecUtil.CodecTypeError typeError;
  protected TimestampMode timestampMode = TimestampMode.CLOCK;

  public void setEncoderErrorCallback(CodecErrorCallback encoderErrorCallback) {
    this.encoderErrorCallback = encoderErrorCallback;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public void setTimestampMode(TimestampMode timestampMode) {
    if (isRunning()) return;
    this.timestampMode = timestampMode;
  }

  public void restart() {
    start(false);
    initCodec();
  }

  public void start(long startTs) {
    if (!prepared) throw new IllegalStateException(TAG + " not prepared yet. You must call prepare method before start it");
    // Continuous mode keeps the original baseline so the PTS continues across a restart; otherwise
    // each start rebases to the supplied timestamp (original behavior).
    if (!forceContinuousTs || presentTimeUs == 0) presentTimeUs = startTs;
    start(true);
    initCodec();
  }

  public void start() {
    start(TimeUtils.getCurrentTimeMicro());
  }

  /**
   * Preserve the timestamp baseline across stop/start cycles so the output PTS stays monotonic
   * across a restart (e.g. SRT reconnect recovery) instead of rebasing to zero. Without it, an HLS
   * packager downstream sees a backward timestamp jump and emits a discontinuity. Default false.
   */
  public void forceContinuousTs(boolean force) {
    this.forceContinuousTs = force;
  }

  protected void setCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !type.equals(CodecUtil.G711_MIME)) {
      // GPX fork patch: this method spawns a HandlerThread per call, and every ordinary caller
      // reaches it via a prepare path that ran stop() first (which quits and joins the thread), so
      // historically nothing leaked. VideoEncoder's configure-retry (see prepareVideoEncoder) is the
      // first caller that legitimately calls setCallback twice WITHOUT an intervening stop(), so
      // retire any thread still held here rather than leaking one per attempt. Idempotent after
      // stop() — quitting an already-quit HandlerThread is a no-op.
      releaseCallbackThread();
      handlerThread = new HandlerThread(TAG);
      handlerThread.start();
      handler = new Handler(handlerThread.getLooper());
      createAsyncCallback();
      codec.setCallback(callback, handler);
    }
  }

  /**
   * GPX fork patch: quit and join whatever HandlerThread {@link #setCallback()} last created, if
   * any. Split out so a caller that must re-register the async callback without going through the
   * full {@link #stop()} path (VideoEncoder's configure-retry) can still retire the old thread.
   */
  protected void releaseCallbackThread() {
    if (handlerThread == null) return;
    try {
      // quitSafely is API 18+; this module's minSdk is lower, and although handlerThread can only
      // be non-null when setCallback ran (itself API 23+ gated), lint cannot prove that.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        handlerThread.quitSafely();
      } else {
        handlerThread.quit();
      }
      // NEVER join our own thread. In async mode MediaCodec.Callback runs ON handlerThread, and the
      // codec-crash recovery path (onOutputBufferAvailable -> reloadCodec -> reset ->
      // prepareVideoEncoder -> setCallback) therefore reaches here executing on the very thread
      // being joined. It cannot die while blocked on itself, so the join would burn its full
      // timeout on every codec-crash recovery. The looper is already quit above, so the thread
      // exits on its own as soon as the callback unwinds.
      if (Thread.currentThread() != handlerThread) handlerThread.join(500);
    } catch (Exception ignored) {
    } finally {
      handlerThread = null;
      handler = null;
      callback = null;
    }
  }

  private void initCodec() {
    running = true;
    if (!type.equals(CodecUtil.G711_MIME)) {
      codec.start();
      codecStarted = true;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || type.equals(CodecUtil.G711_MIME)) {
      executorService = Executors.newSingleThreadExecutor();
      executorService.submit(() -> {
        while (running) {
          try {
            getDataFromEncoder();
          } catch (IllegalStateException e) {
            Log.i(TAG, "Encoding error", e);
            reloadCodec(e);
          }
        }
      });
    }
  }

  public abstract boolean reset();

  public abstract void start(boolean resetTs);

  protected abstract void stopImp();

  protected boolean checkValidTimeStamp(MediaCodec.BufferInfo info) {
    boolean valid = oldTimeStamp <= info.presentationTimeUs;
    oldTimeStamp = info.presentationTimeUs;
    return valid;
  }

  private void reloadCodec(IllegalStateException e) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (e instanceof MediaCodec.CodecException) {
        if (((MediaCodec.CodecException) e).isTransient()) {
          return;
        }
        if (((MediaCodec.CodecException) e).isRecoverable()) {
          reset();
          return;
        }
      }
    }
    //Sometimes encoder crash, we will try recover it. Reset encoder a time if crash
    CodecErrorCallback callback = encoderErrorCallback;
    if (callback != null) {
      shouldReset = callback.onEncodeError(typeError, e);
    }
    if (shouldReset) {
      Log.e(typeError.name(), "Encoder crashed, trying to recover it");
      reset();
    }
  }

  public void stop() {
    stop(true);
  }

  public void stop(boolean resetTs) {
    if (resetTs && !forceContinuousTs) {
      presentTimeUs = 0;
    }
    running = false;
    stopImp();
    if (handlerThread != null) {
      if (handlerThread.getLooper() != null) {
        if (handlerThread.getLooper().getThread() != null) {
          handlerThread.getLooper().getThread().interrupt();
        }
        handlerThread.getLooper().quit();
      }
      handlerThread.quit();
      // GPX fork patch: only flush a codec that actually reached Executing. Flushing a merely
      // Configured codec (prepared but never started — every cold-start re-prepare) is a no-op that
      // still makes MediaCodec log a hard "flush() is valid only at Executing states" error natively
      // before throwing the IllegalStateException swallowed below.
      if (codec != null && codecStarted) {
        try {
          codec.flush();
        } catch (IllegalStateException ignored) { }
      }
      codecStarted = false;
      //wait for thread to die for 500ms.
      try {
        handlerThread.getLooper().getThread().join(500);
      } catch (Exception ignored) { }
    }
    if (executorService != null) executorService.shutdownNow();
    queue.clear();
    queue = new ArrayBlockingQueue<>(80);
    try {
      codec.stop();
      codec.release();
      codec = null;
    } catch (IllegalStateException | NullPointerException e) {
      codec = null;
    }
    prepared = false;
    oldTimeStamp = 0L;
  }

  protected abstract MediaCodecInfo chooseEncoder(String mime);

  protected void getDataFromEncoder() throws IllegalStateException {
    if (type.equals(CodecUtil.G711_MIME)) {
      processG711();
      return;
    }
    if (isBufferMode) {
      int inBufferIndex = codec.dequeueInputBuffer(0);
      if (inBufferIndex >= 0) {
        inputAvailable(codec, inBufferIndex);
      }
    }
    while (running) {
      int outBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
      if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat mediaFormat = codec.getOutputFormat();
        formatChanged(codec, mediaFormat);
      } else if (outBufferIndex >= 0) {
        outputAvailable(codec, outBufferIndex, bufferInfo);
      } else {
        break;
      }
    }
  }

  protected abstract Frame getInputFrame() throws InterruptedException;

  protected abstract long calculatePts(Frame frame, long presentTimeUs);

  private void processInput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
      int inBufferIndex) throws IllegalStateException {
    try {
      Frame frame = getInputFrame();
      while (frame == null) frame = getInputFrame();
      byteBuffer.clear();
      int size = Math.max(0, Math.min(frame.getSize(), byteBuffer.remaining()));
      byteBuffer.put(frame.getBuffer(), frame.getOffset(), size);
      long pts = calculatePts(frame, presentTimeUs);
      mediaCodec.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      Log.i(TAG, "Encoding error", e);
    }
  }

  protected abstract boolean checkBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo);

  protected abstract void sendBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo);

  private void processOutput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
      int outBufferIndex, @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
    if (checkBuffer(byteBuffer, bufferInfo)) sendBuffer(byteBuffer, bufferInfo);
    mediaCodec.releaseOutputBuffer(outBufferIndex, false);
  }

  public void forceCodecType(CodecUtil.CodecType codecType) {
    this.codecType = codecType;
  }

  public boolean isRunning() {
    return running;
  }

  /**
   * GPX fork patch: true when a codec has been successfully configured and not since stopped.
   *
   * Distinct from {@link #isRunning()}: an encoder can be prepared but not started (every
   * cold-start re-prepare), and {@link #stop()} clears this without the caller preparing again.
   *
   * Needed because a caller deciding whether a codec change is a no-op cannot rely on the stored
   * type/profile/level fields — those are written speculatively before configure() and survive a
   * failure, so they describe what was requested, not what exists. See StreamBase's
   * recordCodecPrepared.
   */
  public boolean isPrepared() {
    return prepared;
  }

  @Override
  public void inputAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex)
      throws IllegalStateException {
    ByteBuffer byteBuffer;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      byteBuffer = mediaCodec.getInputBuffer(inBufferIndex);
    } else {
      byteBuffer = mediaCodec.getInputBuffers()[inBufferIndex];
    }
    processInput(byteBuffer, mediaCodec, inBufferIndex);
  }

  @Override
  public void outputAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
      @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
    ByteBuffer byteBuffer;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      byteBuffer = mediaCodec.getOutputBuffer(outBufferIndex);
    } else {
      byteBuffer = mediaCodec.getOutputBuffers()[outBufferIndex];
    }
    processOutput(byteBuffer, mediaCodec, outBufferIndex, bufferInfo);
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private void createAsyncCallback() {
    callback = new MediaCodec.Callback() {
      @Override
      public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) {
        try {
          inputAvailable(mediaCodec, inBufferIndex);
        } catch (IllegalStateException e) {
          Log.i(TAG, "Encoding error", e);
          reloadCodec(e);
        }
      }

      @Override
      public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
          @NonNull MediaCodec.BufferInfo bufferInfo) {
        try {
          outputAvailable(mediaCodec, outBufferIndex, bufferInfo);
        } catch (IllegalStateException e) {
          Log.i(TAG, "Encoding error", e);
          reloadCodec(e);
        }
      }

      @Override
      public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
        Log.e(TAG, "Error", e);
        CodecErrorCallback callback = encoderErrorCallback;
        if (callback != null) callback.onCodecError(typeError, e);
      }

      @Override
      public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
          @NonNull MediaFormat mediaFormat) {
        formatChanged(mediaCodec, mediaFormat);
      }
    };
  }

  private void processG711() {
    try {
      Frame frame = getInputFrame();
      while (frame == null) frame = getInputFrame();
      byte[] data = g711Codec.encode(frame.getBuffer(), frame.getOffset(), frame.getSize());
      ByteBuffer buffer = ByteBuffer.wrap(data, 0, data.length);
      bufferInfo.presentationTimeUs = calculatePts(frame, presentTimeUs);
      bufferInfo.size = data.length;
      bufferInfo.offset = 0;
      sendBuffer(buffer, bufferInfo);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      Log.i(TAG, "Encoding error", e);
    }
  }
}
