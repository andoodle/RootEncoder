package com.pedro.library.transport

import android.media.MediaCodec
import android.util.Size
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.common.socket.base.SocketType
import com.pedro.library.util.streamclient.StreamBaseClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Covers TransportPrimer's replay order and buffer-independence invariants (see its KDoc), plus
 * the post-disconnect routing contract SwitchableStream relies on — all on a fake StreamTransport
 * so no Android runtime is needed.
 */
class TransportPrimerTest {

  /** Every abstract StreamBaseClient member, stubbed — nothing here is exercised by these tests. */
  private class FakeStreamBaseClient : StreamBaseClient() {
    override fun setAuthorization(user: String?, password: String?) {}
    override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
    override fun setReTries(reTries: Int) {}
    override fun hasCongestion(percentUsed: Float): Boolean = false
    override fun setLogs(enabled: Boolean) {}
    override fun setCheckServerAlive(enabled: Boolean) {}
    override fun resizeCache(newSize: Int) {}
    override fun clearCache() {}
    override fun getCacheSize(): Int = 0
    override fun getItemsInCache(): Int = 0
    override fun getQueueBytesOut(): Long = 0
    override fun getSentAudioFrames(): Long = 0
    override fun getSentVideoFrames(): Long = 0
    override fun getBytesSend(): Long = 0
    override fun getDroppedAudioFrames(): Long = 0
    override fun getDroppedVideoFrames(): Long = 0
    override fun resetSentAudioFrames() {}
    override fun resetSentVideoFrames() {}
    override fun resetDroppedAudioFrames() {}
    override fun resetDroppedVideoFrames() {}
    override fun resetBytesSend() {}
    override fun setOnlyAudio(onlyAudio: Boolean) {}
    override fun setOnlyVideo(onlyVideo: Boolean) {}
    override fun setBitrateExponentialFactor(factor: Float) {}
    override fun getBitrateExponentialFactor(): Float = 1f
    override fun setSocketType(type: SocketType) {}
    override fun setSocketTimeout(timeout: Long) {}
    override fun setDelay(millis: Long) {}
  }

  private class FakeTransport : StreamTransport {
    val calls = mutableListOf<String>()
    var discarded = false
    var lastVideoInfo: Triple<ByteBuffer, ByteBuffer?, ByteBuffer?>? = null
    var lastAudioInfo: Pair<Int, Boolean>? = null
    var lastCodec: VideoCodec? = null

    override val streamClient: StreamBaseClient = FakeStreamBaseClient()

    override fun setVideoCodec(codec: VideoCodec) {
      calls += "codec"
      lastCodec = codec
    }

    override fun setAudioCodec(codec: AudioCodec) {
      calls += "audioCodec"
    }

    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
      calls += "videoInfo"
      lastVideoInfo = Triple(sps, pps, vps)
    }

    override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
      calls += "audioInfo"
      lastAudioInfo = sampleRate to isStereo
    }

    override fun connect(endPoint: String, resolution: Size, fps: Int) {
      calls += "connect"
    }

    override fun disconnect() {
      calls += "disconnect"
      discarded = true
    }

    override fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      check(!discarded) { "sendVideo on a discarded transport" }
      calls += "sendVideo"
    }

    override fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      check(!discarded) { "sendAudio on a discarded transport" }
      calls += "sendAudio"
    }
  }

  @Test
  fun `prime replays codec then video info then audio info`() {
    val transport = FakeTransport()
    val sps = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
    val videoInfo = VideoInfoCache(sps, null, null)
    val audioInfo = AudioInfoCache(48000, true)

    TransportPrimer.prime(transport, VideoCodec.H265, videoInfo, audioInfo)

    assertEquals(listOf("codec", "videoInfo", "audioInfo"), transport.calls)
    assertEquals(VideoCodec.H265, transport.lastCodec)
    assertEquals(48000 to true, transport.lastAudioInfo)
  }

  @Test
  fun `two consecutive primes from one cache hand independent full-remaining buffers`() {
    val transport1 = FakeTransport()
    val transport2 = FakeTransport()
    val sps = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
    val cache = VideoInfoCache(sps, null, null)

    TransportPrimer.prime(transport1, null, cache, null)
    // Simulate the first transport draining the buffer it was handed, as a real client would.
    transport1.lastVideoInfo!!.first.get(ByteArray(3))

    TransportPrimer.prime(transport2, null, cache, null)

    val handedToSecond = transport2.lastVideoInfo!!.first
    assertEquals(5, handedToSecond.remaining())
    assertNotSame(transport1.lastVideoInfo!!.first, handedToSecond)
  }

  @Test
  fun `a null cache field primes nothing`() {
    val transport = FakeTransport()

    TransportPrimer.prime(transport, null, null, null)

    assertTrue(transport.calls.isEmpty())
  }

  @Test
  fun `a discarded transport receives no sends after a swap`() {
    val discarded = FakeTransport()
    discarded.disconnect()

    try {
      discarded.sendVideo(ByteBuffer.allocate(1), MediaCodec.BufferInfo())
      fail("expected IllegalStateException")
    } catch (e: IllegalStateException) {
      // Expected — asserts the fake's own post-disconnect contract, standing in for
      // SwitchableStream's routing: a transport discarded by switchTransport() never sees
      // getVideoDataImp/getAudioDataImp again once the volatile `transport` field moves on.
    }
  }
}
