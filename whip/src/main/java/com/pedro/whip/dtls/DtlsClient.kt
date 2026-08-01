/*
 *
 *  * Copyright (C) 2024 pedroSG94.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.pedro.whip.dtls

import com.pedro.rtsp.utils.CryptoProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ExtensionType
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolName
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Hashtable
import java.util.Vector
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * GPX patch: DTLS-SRTP CLIENT role for WHIP.
 *
 * The original whip module only implemented the DTLS server role (see [DtlsConnection]). Production
 * WHIP ingests such as Millicast/Dolby always answer a=setup:passive (the SFU is the DTLS server) and
 * ignore an offerer's attempt to flip that, so the publisher MUST act as the DTLS client and send the
 * ClientHello. This class is the client-side mirror of [DtlsConnection]: it offers the use_srtp
 * extension + WebRTC ALPN, presents our certificate when the server requests one, validates the
 * server certificate against the SDP fingerprint, and exports the same SRTP keying material.
 */
class DtlsClient(
  private val certificate: DtlsCertificate,
  private val remoteFingerprint: String
) : DefaultTlsClient(certificate.crypto) {

  interface Callback {
    fun onHandshakeComplete(properties: List<CryptoProperties>)
    fun onHandshakeFailed(reason: String?)
  }

  private var dtlsTransport: DTLSTransport? = null
  private var exportedSrtpKeys: ByteArray? = null

  fun start(transport: DtlsTransport, callback: Callback) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        // As the DTLS client this drives the handshake: it sends the ClientHello first.
        dtlsTransport = DTLSClientProtocol().connect(this@DtlsClient, transport)
        val keys = exportedSrtpKeys
          ?: throw IllegalStateException("SRTP keying material not captured during handshake")
        callback.onHandshakeComplete(deriveCryptoPropertiesFromExport(keys))
      } catch (e: Exception) {
        callback.onHandshakeFailed(e.message)
      }
    }
  }

  fun close() {
    runCatching { dtlsTransport?.close() }
  }

  override fun getSupportedVersions(): Array<ProtocolVersion> =
    arrayOf(ProtocolVersion.DTLSv12)

  override fun getProtocolNames(): Vector<ProtocolName?> =
    Vector<ProtocolName?>(1).apply { add(ProtocolName.WEBRTC) }

  // Offer the use_srtp extension. Same wire bytes as DtlsConnection.getServerExtensions:
  // profiles-length(2) | SRTP_AES128_CM_HMAC_SHA1_80(0x0001) | mki-length(0).
  override fun getClientExtensions(): Hashtable<*, *> {
    var ret = super.getClientExtensions()
    val prof = ByteArray(5)
    ByteBuffer.wrap(prof).apply {
      putChar(2.toChar())
      put(byteArrayOf(0x00, 0x01))
      put(0.toByte())
    }
    if (ret == null) ret = Hashtable<Any?, Any?>()
    @Suppress("UNCHECKED_CAST")
    (ret as Hashtable<Any?, Any?>)[ExtensionType.use_srtp] = prof
    return ret
  }

  override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
    TlsSRTPUtils.getUseSRTPExtension(serverExtensions)
    super.processServerExtensions(serverExtensions)
  }

  override fun getAuthentication(): TlsAuthentication {
    return object : TlsAuthentication {
      override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
        val certs = serverCertificate.certificate?.certificateList
        if (certs.isNullOrEmpty()) throw IOException("no server certs offered")
        try {
          validateX509(certs[0])
          val fingerprint = computeFingerprint(certs[0])
          if (!fingerprint.equals(remoteFingerprint, ignoreCase = true)) {
            throw IOException("fingerprints don't match")
          }
        } catch (e: CertificateException) {
          throw IOException("offered cert is invalid: ${e.message}")
        }
      }

      override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials =
        buildSignerCredentials(certificateRequest)
    }
  }

  private fun buildSignerCredentials(certificateRequest: CertificateRequest?): TlsCredentialedSigner {
    val certif = Certificate(arrayOf(certificate.certificate))
    val cryptoParams = TlsCryptoParameters(context)
    val offered = certificateRequest?.supportedSignatureAlgorithms
      ?.filterIsInstance<SignatureAndHashAlgorithm>()
    val sigAlg = offered?.firstOrNull {
      it.signature == SignatureAlgorithm.rsa && it.hash == HashAlgorithm.sha256
    } ?: SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.rsa)
    return BcDefaultTlsCredentialedSigner(cryptoParams, certificate.crypto, certificate.key, certif, sigAlg)
  }

  // exportKeyingMaterial() is only valid inside this callback — capture it here.
  override fun notifyHandshakeComplete() {
    super.notifyHandshakeComplete()
    try {
      val keyLength = 16
      val saltLength = 14
      exportedSrtpKeys = context.exportKeyingMaterial(
        "EXTRACTOR-dtls_srtp", null, 2 * (keyLength + saltLength)
      )
    } catch (_: Exception) { }
  }

  private fun validateX509(cert: TlsCertificate) {
    val cf = CertificateFactory.getInstance("X.509")
    val bis = ByteArrayInputStream(cert.encoded)
    while (bis.available() > 0) {
      (cf.generateCertificate(bis) as X509Certificate).checkValidity()
    }
  }

  private fun computeFingerprint(cert: TlsCertificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
  }

  private fun deriveCryptoPropertiesFromExport(keys: ByteArray): List<CryptoProperties> {
    // RFC 5764: [clientMasterKey(16) | serverMasterKey(16) | clientMasterSalt(14) | serverMasterSalt(14)]
    val keyLength = 16
    val saltLength = 14
    val clientMasterKey = ByteArray(keyLength)
    val serverMasterKey = ByteArray(keyLength)
    val clientMasterSalt = ByteArray(saltLength)
    val serverMasterSalt = ByteArray(saltLength)
    var offs = 0
    System.arraycopy(keys, offs, clientMasterKey, 0, keyLength); offs += keyLength
    System.arraycopy(keys, offs, serverMasterKey, 0, keyLength); offs += keyLength
    System.arraycopy(keys, offs, clientMasterSalt, 0, saltLength); offs += saltLength
    System.arraycopy(keys, offs, serverMasterSalt, 0, saltLength)
    return listOf(
      deriveCryptoProperties(clientMasterKey, clientMasterSalt),
      deriveCryptoProperties(serverMasterKey, serverMasterSalt)
    )
  }

  private fun deriveCryptoProperties(masterKey: ByteArray, masterSalt: ByteArray): CryptoProperties {
    val cipherKey = deriveKey(masterKey, masterSalt, 0x00.toByte(), 16)
    val authKey = deriveKey(masterKey, masterSalt, 0x01.toByte(), 20)
    val salt = deriveKey(masterKey, masterSalt, 0x02.toByte(), 14)
    return CryptoProperties(authKey, cipherKey, salt)
  }

  private fun deriveKey(masterKey: ByteArray, masterSalt: ByteArray, label: Byte, lengthBytes: Int): ByteArray {
    val x = masterSalt.copyOf(14)
    x[7] = (x[7].toInt() xor label.toInt()).toByte()
    val iv = ByteArray(16).also { System.arraycopy(x, 0, it, 0, 14) }
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
    val result = ByteArray(lengthBytes)
    var offset = 0
    var counter = 0
    while (offset < lengthBytes) {
      iv[14] = (counter shr 8).toByte()
      iv[15] = counter.toByte()
      val block = cipher.doFinal(iv)
      val toCopy = minOf(16, lengthBytes - offset)
      System.arraycopy(block, 0, result, offset, toCopy)
      offset += toCopy
      counter++
    }
    return result
  }
}
