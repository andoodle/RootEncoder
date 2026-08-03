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

package com.pedro.encoder.video;

import android.graphics.ImageFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.common.TimeUtils;
import com.pedro.common.VideoCodec;
import com.pedro.encoder.BaseEncoder;
import com.pedro.encoder.Frame;
import com.pedro.encoder.TimestampMode;
import com.pedro.encoder.input.video.FpsLimiter;
import com.pedro.encoder.input.video.GetCameraData;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.encoder.utils.SpsColorPatcher;
import com.pedro.encoder.utils.yuv.YUVUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Created by pedro on 19/01/17.
 * This class need use same resolution, fps and imageFormat that Camera1ApiManagerGl
 */

public class VideoEncoder extends BaseEncoder implements GetCameraData {

  private final GetVideoData getVideoData;
  private volatile boolean spsPpsSetted = false;
  private boolean forceKey = false;
  //video data necessary to send after requestKeyframe.
  private ByteBuffer oldSps, oldPps, oldVps;

  //surface to buffer encoder
  private Surface inputSurface;

  private int width = 640;
  private int height = 480;
  private int fps = 30;
  private int bitRate = 1200 * 1024; //in kbps
  private int rotation = 90;
  private int iFrameInterval = 2;
  private long firstTimestamp = 0;
  //for disable video
  private final FpsLimiter fpsLimiter = new FpsLimiter();
  private FormatVideoEncoder formatVideoEncoder = FormatVideoEncoder.YUV420Dynamical;
  private int profile = -1;
  private int level = -1;
  private final SpsColorPatcher spsColorPatcher = new SpsColorPatcher();
  private boolean forceBt709Color = false;
  // GPX R2 — when set, the next prepareVideoEncoder asks for VBR instead of CBR. The record encoder
  // VBR so a recorded file spends bits where the picture needs them; the stream encoder wants CBR
  // so the link sees a flat rate.
  private boolean tryForceVBRBitrateMode = false;
  // GPX R17 — sticky per-process record that this device's encoder rejected KEY_MAX_B_FRAMES at
  // configure
  // time, so the retry is paid once rather than on every reset() or reloadCodec() recovery, which is
  // exactly where extra codec setup hurts. Static so the record encoder inherits what the stream
  // encoder learned. Not persisted: one failed configure per process launch on a rejecting device is
  // an acceptable price in a library with no preferences seam.
  //
  // Scope caveat for raising H264 above Baseline: rejection is a property of one vendor component,
  // but this flag is process-wide. Baseline forbids B-frames structurally whatever the flag says, so
  // today it is harmless. On Main or High, H264 depends on the key too, and a rejection latched by
  // the HEVC component would silently stop the H264 encoder requesting it.
  private static volatile boolean maxBFramesKeyRejected = false;
  // GPX R17 — what this instance actually asked for at its last configure. Reading the static flag
  // would misreport the stream encoder's request if the record encoder latched a rejection in
  // between, and this log is the evidence of whether B-frame suppression was attempted at all.
  private boolean zeroBFramesRequestedForThisCodec = false;

  public VideoEncoder(GetVideoData getVideoData) {
    this.getVideoData = getVideoData;
    typeError = CodecUtil.CodecTypeError.VIDEO_CODEC;
    type = VideoCodec.H264;
    TAG = "VideoEncoder";
  }

  public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int rotation,
      int iFrameInterval, FormatVideoEncoder formatVideoEncoder) {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval,
        formatVideoEncoder, -1, -1);
  }

  /**
   * Prepare encoder with custom parameters
   */
  public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int rotation,
      int iFrameInterval, FormatVideoEncoder formatVideoEncoder, int profile,
      int level) {
    if (prepared) stop();

    if (width % 2 != 0) {
      throw new IllegalArgumentException("Invalid width: " + width + ", must be an even value");
    }
    if (height % 2 != 0) {
      throw new IllegalArgumentException("Invalid height: " + height + ", must be an even value");
    }
    if (fps <= 0) {
      throw new IllegalArgumentException("Invalid fps: " + fps + ", must be higher than 0");
    }
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.bitRate = bitRate;
    this.rotation = rotation;
    this.iFrameInterval = iFrameInterval;
    this.formatVideoEncoder = formatVideoEncoder;
    this.profile = profile;
    this.level = level;
    isBufferMode = true;
    MediaCodecInfo encoder = chooseEncoder(type.getMime());
    try {
      if (encoder != null) {
        Log.i(TAG, "Encoder selected " + encoder.getName());
        if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
          this.formatVideoEncoder = chooseColorDynamically(encoder);
          if (this.formatVideoEncoder == null) {
            Log.e(TAG, "YUV420 dynamical choose failed");
            return false;
          }
        }
      } else {
        Log.e(TAG, "Valid encoder not found");
        return false;
      }
      // GPX R17 — ask for zero B-frames, and fall back if the vendor rejects the key.
      //
      // No publisher in this library can carry a decode order that differs from presentation order:
      // RTMP hardcodes the FLV composition-time offset to 0, MPEG-TS writes PES with PTS and no DTS,
      // and RTP has no DTS field. A B-frame therefore degrades timing on every publishing path.
      // Requesting Baseline keeps H264 free of them structurally, but HEVC has no B-frame-free
      // profile, so KEY_MAX_B_FRAMES is the only lever that closes H265.
      //
      // The key is API 29+, and it is a request rather than a guarantee: a vendor may ignore it, so
      // the bitstream still has to be checked.
      boolean requestZeroBFrames =
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !maxBFramesKeyRejected;
      try {
        configureCodec(encoder, requestZeroBFrames);
      } catch (Exception e) {
        if (!requestZeroBFrames) {
          // GPX R17 — nothing to retry, either pre-Q or already latched. Release here rather than
          // falling into
          // the outer catch's stop(), whose codec.stop() throws from the Error state and then nulls
          // the codec without releasing it.
          releaseCodecForRetry();
          throw e;
        }
        // GPX R17 — configure() is where a vendor rejects a format, and a rejection moves it to the
        // Error state, where configure() is itself illegal -- so a retry on the same instance would
        // throw again. The instance is torn down and rebuilt, and the async callback re-registered,
        // because an unserviced codec hangs. Without this retry one unrecognised key would stop the
        // device streaming, since the caller's catch turns any throw into prepared = false.
        Log.w(TAG, "configure failed with KEY_MAX_B_FRAMES; retrying without it", e);
        releaseCodecForRetry();
        try {
          configureCodec(encoder, false);
        } catch (Exception retryFailure) {
          // GPX R17 — the retry failed too, so the key was not the cause; leave the flag alone so
          // prepare tries it again.
          releaseCodecForRetry();
          throw retryFailure;
        }
        // GPX R17 — best available evidence that the key was the cause: without it the same configure
        // succeeds. Not proof -- a transient first-attempt failure followed by a clean retry latches
        // the flag too, and there is no un-latch. Latching on the first throw instead would suppress
        // the key for the whole process after any configure failure, reinstating the B-frame
        // exposure this exists to close. A false latch is at least visible: the negotiated-format
        // log reports zeroBFramesRequested=false.
        maxBFramesKeyRejected = true;
        Log.w(TAG, "KEY_MAX_B_FRAMES rejected by this device; suppressed for the process");
      }
      running = false;
      if (formatVideoEncoder == FormatVideoEncoder.SURFACE
          && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        isBufferMode = false;
        inputSurface = codec.createInputSurface();
      }
      Log.i(TAG, "prepared");
      prepared = true;
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Create VideoEncoder failed.", e);
      this.stop();
      return false;
    }
  }

  /**
   * GPX R17 — build the format, register the async callback and configure, as one unit that can be
   * attempted
   * twice. Kept separate from prepareVideoEncoder because a retry bolted onto the linear body is how
   * the callback re-registration and the thread teardown get missed.
   */
  private void configureCodec(MediaCodecInfo encoder, boolean requestZeroBFrames) throws Exception {
    if (codec == null) codec = MediaCodec.createByCodecName(encoder.getName());
    MediaFormat videoFormat = buildVideoFormat(encoder, requestZeroBFrames);
    setCallback();
    codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    zeroBFramesRequestedForThisCodec = requestZeroBFrames;
  }

  /**
   * GPX R17 — return the codec to a state where configure() is legal again after a rejected format.
   * Not
   * {@link #stop()}: that calls codec.stop() first, which throws from the Error state, and its catch
   * then nulls the codec without releasing it.
   */
  private void releaseCodecForRetry() {
    releaseCallbackThread();
    if (codec != null) {
      try {
        codec.release();
      } catch (Exception ignored) {
      }
      codec = null;
    }
  }

  private MediaFormat buildVideoFormat(MediaCodecInfo encoder, boolean requestZeroBFrames) {
    MediaFormat videoFormat;
    //if you don't use mediacodec rotation you need swap width and height in rotation 90 or 270
    // for correct encoding resolution
    String resolution;
    if ((rotation == 90 || rotation == 270)) {
      resolution = height + "x" + width;
      videoFormat = MediaFormat.createVideoFormat(type.getMime(), height, width);
    } else {
      resolution = width + "x" + height;
      videoFormat = MediaFormat.createVideoFormat(type.getMime(), width, height);
    }
    Log.i(TAG, "Prepare video info: " + this.formatVideoEncoder.name() + ", " + resolution);
    videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
        this.formatVideoEncoder.getFormatCodec());
    videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
    videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
    videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
    videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
    //Set CBR mode if supported by encoder.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && CodecUtil.isCBRModeSupported(encoder, type.getMime())) {
      Log.i(TAG, "set bitrate mode CBR");
      videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
          MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
    } else {
      Log.i(TAG, "bitrate mode CBR not supported using default mode");
    }
    if (tryForceVBRBitrateMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      Log.i(TAG, "set bitrate mode VBR (forced)");
      videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
          MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
    }
    // Rotation by encoder.
    // Removed because this is ignored by most encoders, producing different results on different devices
    //  videoFormat.setInteger(MediaFormat.KEY_ROTATION, rotation);

    if (this.profile > 0) {
      // MediaFormat.KEY_PROFILE, API > 21
      videoFormat.setInteger("profile", this.profile);
    }
    if (this.level > 0) {
      // MediaFormat.KEY_LEVEL, API > 23
      videoFormat.setInteger("level", this.level);
    }
    // GPX R3 — ask the encoder to repeat SPS/PPS ahead of every IDR frame. A recorded file is then
    // decodable
    // from any keyframe rather than only from its first frame, which is what makes clip extraction
    // at a keyframe boundary produce a playable file. Encoders that do not know the key ignore it.
    if (type == VideoCodec.H264 || type == VideoCodec.H265) {
      videoFormat.setInteger("prepend-sps-pps-to-idr-frames", 1);
    }
    // GPX patch — set BT.709 color metadata so the encoder embeds correct VUI in the SPS NAL unit.
    // Without this, devices default to smpte170m/bt470bg which ffprobe/players read incorrectly.
    // KEY_COLOR_STANDARD / KEY_COLOR_TRANSFER / KEY_COLOR_RANGE added in API 24.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && forceBt709Color) {
      videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);  // primaries + matrix = BT.709
      videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO); // transfer = BT.709 (gamma)
      videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);        // TV range (16-235)
    }
    if (requestZeroBFrames) {
      videoFormat.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
    }
    return videoFormat;
  }

  @Override
  public void start(boolean resetTs) {
    if (resetTs && !forceContinuousTs) firstTimestamp = 0;
    forceKey = false;
    shouldReset = resetTs;
    spsPpsSetted = false;
    if (formatVideoEncoder != FormatVideoEncoder.SURFACE) {
      YUVUtil.preAllocateBuffers(width * height * 3 / 2);
    }
    Log.i(TAG, "started");
  }

  @Override
  protected void stopImp() {
    spsPpsSetted = false;
    if (inputSurface != null) inputSurface.release();
    inputSurface = null;
    oldSps = null;
    oldPps = null;
    oldVps = null;
    Log.i(TAG, "stopped");
  }

  @Override
  public boolean reset() {
    stop(false);
    boolean result = prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval, formatVideoEncoder,
        profile, level);
    if (!result) return false;
    restart();
    return true;
  }

  private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
    for (int color : mediaCodecInfo.getCapabilitiesForType(type.getMime()).colorFormats) {
      if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420PLANAR;
      } else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420SEMIPLANAR;
      }
    }
    return null;
  }

  /**
   * Prepare encoder with default parameters
   */
  public boolean prepareVideoEncoder() {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval,
        formatVideoEncoder, profile, level);
  }

  /**
   * GPX R18 — re-prepare with the stored geometry but a new profile and level.
   *
   * The no-arg replay above re-sends the stored profile and level while chooseEncoder() reads the
   * current {@code type}, so a caller that changed the codec gets an encoder on the new codec
   * carrying the old codec's profile and level. The MediaCodecInfo.CodecProfileLevel constants are
   * codec-namespaced -- 1 is AVCProfileBaseline for H264 and HEVCProfileMain for H265 -- so that
   * pairing is meaningless at best and written into the SPS at worst.
   *
   * This overload exists so a codec change and its profile and level always move together. Set
   * {@code type} immediately before calling it.
   */
  public boolean prepareVideoEncoder(int profile, int level) {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval,
        formatVideoEncoder, profile, level);
  }

  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  public void setVideoBitrateOnFly(int bitrate) {
    if (isRunning()) {
      this.bitRate = bitrate;
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
      try {
        codec.setParameters(bundle);
      } catch (IllegalStateException e) {
        Log.e(TAG, "encoder need be running", e);
      }
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  public void requestKeyframe() {
    if (isRunning()) {
      if (spsPpsSetted && oldSps != null) {
        Bundle bundle = new Bundle();
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
          codec.setParameters(bundle);
          getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        } catch (IllegalStateException e) {
          Log.e(TAG, "encoder need be running", e);
        }
      } else {
        //You need wait until encoder generate first frame.
        spsPpsSetted = false;
        forceKey = true;
      }
    }
  }

  /**
   * GPX R2 — request VBR instead of CBR on the next prepareVideoEncoder. Has no effect on one that
   * is already prepared.
   */
  public void setTryForceVBRBitrateMode(boolean tryForceVBRBitrateMode) {
    this.tryForceVBRBitrateMode = tryForceVBRBitrateMode;
  }

  public Surface getInputSurface() {
    return inputSurface;
  }

  public void setInputSurface(Surface inputSurface) {
    this.inputSurface = inputSurface;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public int getRotation() {
    return rotation;
  }

  public void setFps(int fps) {
    this.fps = fps;
  }

  public void setRotation(int rotation) {
    this.rotation = rotation;
  }

  public int getFps() {
    return fps;
  }

  public int getBitRate() {
    return bitRate;
  }

  public void setForceFps(int fps) {
    fpsLimiter.setFPS(fps);
  }

  public void forceBt709Color(boolean enabled) {
    if (prepared) throw new IllegalStateException("Encoder already prepared, this must be called before prepareVideo");
    this.forceBt709Color = enabled;
  }

  @Override
  public void inputYUVData(@NonNull Frame frame) {
    if (running && !queue.offer(frame)) {
      Log.i(TAG, "frame discarded");
    }
  }

  private boolean sendSPSandPPS(MediaFormat mediaFormat) {
    //AV1
    if (type == VideoCodec.AV1) {
      ByteBuffer bufferInfo = mediaFormat.getByteBuffer("csd-0");
      //we need an av1ConfigurationRecord with sequenceObu to work
      if (bufferInfo != null && bufferInfo.remaining() > 4) {
        oldSps = bufferInfo.duplicate();
        getVideoData.onVideoInfo(oldSps, null, null);
        return true;
      }
    } else if (type == VideoCodec.VP8 || type == VideoCodec.VP9) {
      //Only parse using keyframes.
      return false;
      //H265
    } else if (type == VideoCodec.H265) {
      ByteBuffer bufferInfo = mediaFormat.getByteBuffer("csd-0");
      if (bufferInfo != null) {
        List<ByteBuffer> byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(bufferInfo.duplicate());
        oldSps = forceBt709Color ? spsColorPatcher.patchSpsNalColorToBt709(byteBufferList.get(1), true) : byteBufferList.get(1);
        oldPps = byteBufferList.get(2);
        oldVps = byteBufferList.get(0);
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        return true;
      }
      //H264
    } else {
      ByteBuffer sps = mediaFormat.getByteBuffer("csd-0");
      ByteBuffer pps = mediaFormat.getByteBuffer("csd-1");
      if (sps != null && pps != null) {
        oldSps = forceBt709Color ? spsColorPatcher.patchSpsNalColorToBt709(sps.duplicate(), false) : sps.duplicate();
        oldPps = pps.duplicate();
        oldVps = null;
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        return true;
      }
    }
    return false;
  }

  /**
   * choose the video encoder by mime.
   */
  @Override
  protected MediaCodecInfo chooseEncoder(String mime) {
    List<MediaCodecInfo> mediaCodecInfoList;
    if (codecType == CodecUtil.CodecType.HARDWARE) {
      mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(mime, true);
    } else if (codecType == CodecUtil.CodecType.SOFTWARE) {
      mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(mime, true);
    } else if (codecType == CodecUtil.CodecType.CBR_PRIORITY) {
      //Priority: hardware CBR > software CBR > hardware > software
      mediaCodecInfoList = CodecUtil.getAllEncodersCbrPriority(mime);
    } else {
      //Priority: hardware CBR > hardware > software CBR > software
      mediaCodecInfoList = CodecUtil.getAllEncoders(mime, true, true);
    }

    Log.i(TAG, mediaCodecInfoList.size() + " encoders found");
    for (MediaCodecInfo mci : mediaCodecInfoList) {
      Log.i(TAG, "Encoder " + mci.getName());
      MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
      for (int color : codecCapabilities.colorFormats) {
        Log.i(TAG, "Color supported: " + color);
        if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
          if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci;
        } else {
          //check if encoder support any yuv420 color
          if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
              || color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
            return mci;
          }
        }
      }
    }
    return null;
  }

  @Override
  protected Frame getInputFrame() throws InterruptedException {
    Frame frame = queue.take();
    if (frame == null) return null;
    if (fpsLimiter.limitFPS()) return getInputFrame();
    byte[] buffer = frame.getBuffer();
    boolean isYV12 = frame.getFormat() == ImageFormat.YV12;

    int orientation = frame.isFlip() ? frame.getOrientation() + 180 : frame.getOrientation();
    if (orientation >= 360) orientation -= 360;
    buffer = isYV12 ? YUVUtil.rotateYV12(buffer, width, height, orientation)
        : YUVUtil.rotateNV21(buffer, width, height, orientation);

    buffer = isYV12 ? YUVUtil.YV12toYUV420byColor(buffer, width, height, formatVideoEncoder)
        : YUVUtil.NV21toYUV420byColor(buffer, width, height, formatVideoEncoder);
    frame.setBuffer(buffer);
    return frame;
  }

  @Override
  protected long calculatePts(Frame frame, long presentTimeUs) {
    return Math.max(0, frame.getTimeStamp() - presentTimeUs);
  }

  @Override
  public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
    logNegotiatedFormat(mediaFormat);
    getVideoData.onVideoFormat(mediaFormat);
    spsPpsSetted = sendSPSandPPS(mediaFormat);
  }

  /**
   * GPX R18 — log what the encoder negotiated, as opposed to what was requested.
   *
   * Reports two things a MediaFormat consumer cannot get elsewhere: the profile and level as this
   * encoder stored them, and zeroBFramesRequestedForThisCodec, which is private state rather than a
   * format key. It also covers the record encoder.
   *
   * Vendors are not required to populate KEY_PROFILE or KEY_LEVEL in the output format, so an absent
   * value is logged as "-" rather than defaulted: "not reported" and "reported as zero" are
   * different facts. The authoritative check remains the bitstream.
   */
  private void logNegotiatedFormat(MediaFormat mediaFormat) {
    try {
      Log.i(TAG, "negotiated format: mime=" + optString(mediaFormat, MediaFormat.KEY_MIME)
          + " profile=" + optInt(mediaFormat, MediaFormat.KEY_PROFILE)
          + " level=" + optInt(mediaFormat, MediaFormat.KEY_LEVEL)
          + " max-bframes=" + optInt(mediaFormat, "max-bframes")
          + " bitrate-mode=" + optInt(mediaFormat, MediaFormat.KEY_BITRATE_MODE)
          + " (requested profile=" + profile + " level=" + level
          + " zeroBFramesRequested=" + zeroBFramesRequestedForThisCodec + ")");
    } catch (Exception ignored) {
      // A diagnostic must never be able to break an encoder callback.
    }
  }

  private static String optInt(MediaFormat format, String key) {
    try {
      return format.containsKey(key) ? String.valueOf(format.getInteger(key)) : "-";
    } catch (Exception e) {
      return "-";
    }
  }

  private static String optString(MediaFormat format, String key) {
    try {
      return format.containsKey(key) ? String.valueOf(format.getString(key)) : "-";
    } catch (Exception e) {
      return "-";
    }
  }

  @Override
  protected boolean checkBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo) {
    if (forceKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      forceKey = false;
      requestKeyframe();
    }
    if (!spsPpsSetted && type == VideoCodec.H264) {
      Log.i(TAG, "formatChanged not called, doing manual sps/pps extraction...");
      Pair<ByteBuffer, ByteBuffer> buffers = VideoEncoderHelper.decodeSpsPpsFromBuffer(byteBuffer.duplicate(), bufferInfo.size);
      if (buffers != null) {
        Log.i(TAG, "manual sps/pps extraction success");
        oldSps = buffers.first;
        oldPps = buffers.second;
        oldVps = null;
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual sps/pps extraction failed");
      }
    } else if (!spsPpsSetted && type == VideoCodec.H265) {
      Log.i(TAG, "formatChanged not called, doing manual vps/sps/pps extraction...");
      List<ByteBuffer> byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(byteBuffer.duplicate());
      if (byteBufferList.size() == 3) {
        Log.i(TAG, "manual vps/sps/pps extraction success");
        oldSps = byteBufferList.get(1);
        oldPps = byteBufferList.get(2);
        oldVps = byteBufferList.get(0);
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual vps/sps/pps extraction failed");
      }
    } else if (!spsPpsSetted && type == VideoCodec.VP8) {
      ByteBuffer header = VideoEncoderHelper.extractVp8Header(byteBuffer.duplicate(), bufferInfo);
      if (header != null) {
        oldSps = header;
        getVideoData.onVideoInfo(header, null, null);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual vp8 extraction failed");
      }
    } else if (!spsPpsSetted && type == VideoCodec.VP9) {
      ByteBuffer header = VideoEncoderHelper.extractVp9BitStreamHeader(byteBuffer.duplicate(), bufferInfo);
      if (header != null) {
        oldSps = header;
        getVideoData.onVideoInfo(header, null, null);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual vp9 extraction failed");
      }
    } else if (!spsPpsSetted && type == VideoCodec.AV1) {
      Log.i(TAG, "formatChanged not called, doing manual av1 extraction...");
      ByteBuffer obuSequence = VideoEncoderHelper.extractObuSequence(byteBuffer.duplicate(), bufferInfo);
      if (obuSequence != null) {
        oldSps = obuSequence;
        getVideoData.onVideoInfo(obuSequence, null, null);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual av1 extraction failed");
      }
    }
    if (timestampMode == TimestampMode.CLOCK) {
      if (formatVideoEncoder != FormatVideoEncoder.SURFACE) {
        // Buffer mode: synthesize PTS from wall clock.
        bufferInfo.presentationTimeUs = TimeUtils.getCurrentTimeMicro() - presentTimeUs;
      } else {
        // Surface mode: EGL timestamp is camera sensor time (nanoseconds from boot ÷ 1000).
        // It has clean, jitter-free intervals — but it's a huge absolute value that breaks RTMP.
        // Rebase to relative by subtracting the first frame's PTS → clean intervals, starts at 0.
        if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;
        bufferInfo.presentationTimeUs -= firstTimestamp;
      }
    } else {
      if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;
      bufferInfo.presentationTimeUs -= firstTimestamp;
    }
    return checkValidTimeStamp(bufferInfo);
  }

  @Override
  protected void sendBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo) {
    getVideoData.getVideoData(byteBuffer, bufferInfo);
  }
}
