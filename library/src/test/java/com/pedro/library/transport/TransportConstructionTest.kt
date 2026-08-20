package com.pedro.library.transport

import com.pedro.common.ConnectChecker
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.library.util.streamclient.WhipStreamClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GenericTransport and WhipTransport both construct cleanly on the plain JVM under this module's
 * `isReturnDefaultValues = true` unit-test config (SwitchableStream itself cannot: StreamBase's
 * constructor reaches android.jar surfaces returnDefaultValues does not stub, per the mockk note
 * in gpxstream-app's libs.versions.toml — its routing and swap paths are covered by the app's
 * driver tests through the composite build plus the bench gate, not here).
 *
 * The transport constructors also set their protocol's canonical audio codec at construction
 * time (GenericTransport -> AAC on all four clients, WhipTransport -> OPUS) — the underlying
 * RootEncoder clients expose no getter for the codec they were handed, so what these tests can
 * assert is that construction (which reaches that setAudioCodec call) completes without
 * throwing, not the codec value itself.
 */
class TransportConstructionTest {

  private val checker = object : ConnectChecker {
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
  }
  private val listener = object : StreamClientListener {
    override fun onRequestKeyframe() {}
  }

  @Test
  fun `GenericTransport constructs with a GenericStreamClient and the AAC init does not throw`() {
    val transport: StreamTransport = GenericTransport(checker, listener)
    assertNotNull(transport.streamClient)
    assertTrue(transport.streamClient is GenericStreamClient)
  }

  @Test
  fun `WhipTransport constructs with one stable WhipStreamClient and the OPUS init does not throw`() {
    val transport: StreamTransport = WhipTransport(checker, listener)
    assertTrue(transport.streamClient is WhipStreamClient)
    // Stable identity across repeated reads — the fix over WhipStream.getStreamClient(), which
    // built a fresh wrapper on every call.
    assertTrue(transport.streamClient === transport.streamClient)
  }
}
