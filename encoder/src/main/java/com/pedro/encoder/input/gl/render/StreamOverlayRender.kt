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

package com.pedro.encoder.input.gl.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.pedro.encoder.R
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPX fork addition: a full-frame bitmap plane composited ONLY into the STREAM encoder output.
 *
 * Unlike the filter chain (MainRender.drawFilters), which feeds every consumer (stream encoder,
 * record encoder, preview, photo) from one filtered texture, this render is drawn by
 * GlStreamInterface exclusively into the stream encoder surface, AFTER the frame content —
 * so a stream-facing graphic (e.g. a "no signal" slate) never contaminates the recording file,
 * the preview, or photo captures.
 *
 * Threading: [setBitmap] is callable from any thread; [draw]/[release] run on the GL thread with
 * the target surface current and the viewport already set by the caller. GL objects are created
 * lazily on first draw and re-created after [release] (which only invalidates handles — the EGL
 * context that owned them is destroyed by the caller's stop path).
 */
class StreamOverlayRender {

  private val squareVertexData = floatArrayOf(
    // X, Y, Z, U, V — V is flipped relative to ScreenRender: this texture is uploaded from a
    // Bitmap (row 0 = image top at t=0), not sampled from an FBO (origin bottom-left).
    -1f, -1f, 0f, 0f, 1f, // bottom left
    1f, -1f, 0f, 1f, 1f, // bottom right
    -1f, 1f, 0f, 0f, 0f, // top left
    1f, 1f, 0f, 1f, 0f, // top right
  )

  private val squareVertex: FloatBuffer = ByteBuffer
    .allocateDirect(squareVertexData.size * BaseRenderOffScreen.FLOAT_SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .apply { put(squareVertexData).position(0) }

  private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
  private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

  private var program = -1
  private var aPositionHandle = -1
  private var aTextureHandle = -1
  private var uMVPMatrixHandle = -1
  private var uSTMatrixHandle = -1
  private var uSamplerHandle = -1
  private var texId = -1

  @Volatile
  private var pendingBitmap: Bitmap? = null
  private val dirty = AtomicBoolean(false)

  @Volatile
  private var visible = false

  /** Set (or clear with null) the overlay bitmap. Uploaded on the GL thread at the next draw. */
  fun setBitmap(bitmap: Bitmap?) {
    pendingBitmap = bitmap
    visible = bitmap != null
    dirty.set(true)
  }

  fun hasContent(): Boolean = visible

  /**
   * Draw the overlay quad over whatever the caller just rendered. No clear, alpha-blended.
   * Requires: GL thread, target surface current, viewport already set.
   */
  fun draw(context: Context) {
    if (!visible && !dirty.get()) return
    ensureInit(context)
    if (dirty.getAndSet(false)) {
      val bitmap = pendingBitmap
      if (bitmap != null && !bitmap.isRecycled) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      }
    }
    if (!visible) return

    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    GLES20.glUseProgram(program)

    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aPositionHandle)

    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aTextureHandle)

    GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
    GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

    GLES20.glUniform1i(uSamplerHandle, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

    GlUtil.disableResources(aTextureHandle, aPositionHandle)
    GLES20.glDisable(GLES20.GL_BLEND)
  }

  /**
   * Invalidate GL handles after the owning EGL context is torn down. Deliberately makes no GL
   * calls (the context is gone); the next [draw] on a fresh context re-creates program + texture
   * and re-uploads the pending bitmap.
   */
  fun release() {
    program = -1
    texId = -1
    if (visible) dirty.set(true)
  }

  private fun ensureInit(context: Context) {
    if (program != -1) return
    val vertexShader = GlUtil.getStringFromRaw(context, R.raw.simple_vertex)
    val fragmentShader = GlUtil.getStringFromRaw(context, R.raw.simple_fragment)
    program = GlUtil.createProgram(vertexShader, fragmentShader)
    aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
    uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
    uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    texId = textures[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
  }
}
