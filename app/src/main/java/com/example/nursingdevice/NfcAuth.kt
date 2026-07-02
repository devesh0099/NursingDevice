package com.example.nursingdevice

import android.nfc.tech.IsoDep
import java.io.IOException
import java.security.PublicKey

/**
 * Reader-side helper for the Phase 3 certificate-exchange step.
 *
 * Before the session-key exchange, the reader sends its own certificate and
 * receives the card's certificate, each verifying the other against the shared
 * CA (see CryptoUtils.verifyPeerCertAndExtractKey). This replaces the old
 * hardcoded OTHER_PUBLIC_KEY trust model with per-device identities.
 *
 * Certificates are sent as PEM bytes in a single (extended-length) APDU — the
 * same extended-length path the existing 264-byte AUTH_KEY command relies on.
 */
object NfcAuth {

    /**
     * Exchange certificates with the card and return the card's verified public key.
     * @throws IOException if this device has no credential, the exchange fails, or
     *         the peer's certificate is not trusted.
     */
    fun exchangeCerts(isoDep: IsoDep): PublicKey {
        val myCert = CryptoUtils.getMyCertificatePem()
            ?: throw IOException("No credential on this device — log in with your PIN first, then tap again.")

        val res = try {
            isoDep.transceive(Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_CERT, myCert.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IOException("Certificate exchange transmit failed (${e.message}). Keep the phones together and retap.")
        }

        if (res.size < 2) throw IOException("Certificate exchange failed: peer returned an empty response.")
        val sw = res.copyOfRange(res.size - 2, res.size)
        if (!sw.contentEquals(Utils.SELECT_OK_SW)) {
            throw IOException(
                "Certificate exchange rejected by peer (status ${sw.toHex()}). " +
                "Likely the peer app isn't logged in, or the two devices trust different CAs."
            )
        }

        val peerCertPem = String(res.copyOfRange(0, res.size - 2), Charsets.UTF_8)
        return try {
            CryptoUtils.verifyPeerCert(peerCertPem)
        } catch (e: PeerCertException) {
            throw IOException("Peer certificate rejected: ${e.message}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
