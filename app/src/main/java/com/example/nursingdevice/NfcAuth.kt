package com.example.nursingdevice

import android.nfc.tech.IsoDep
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PublicKey
import kotlin.math.min

private const val TAG = "NfcAuth"

/**
 * Reader-side helper for the Phase 3 certificate-exchange step.
 *
 * Before the session-key exchange, the reader sends its own certificate and
 * receives the card's certificate, each verifying the other against the shared
 * CA (see CryptoUtils.verifyPeerCertAndExtractKey). This replaces the old
 * hardcoded OTHER_PUBLIC_KEY trust model with per-device identities.
 *
 * Certificates are exchanged in AUTH_CHUNK_SIZE pieces (never as one big APDU):
 * some NFC controllers — e.g. the Galaxy Tab Active5 — cannot receive
 * extended-length APDUs in HCE mode, so a ~1.3 KB single-APDU cert arrives
 * truncated and gets rejected as malformed. The reader uploads its cert in
 * chunks via AUTH_CRT and then pulls the card's cert in chunks via AUTH_CRG.
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
        Log.d(TAG, "exchangeCerts: maxTransceiveLength=${isoDep.maxTransceiveLength}, extendedApduSupported=${isoDep.isExtendedLengthApduSupported}, myCert=${myCert.toByteArray(Charsets.UTF_8).size}B")

        // 1. Upload our certificate in chunks; the card verifies it on the last one.
        val res = try {
            sendChunked(isoDep, CryptoUtils.CMD_AUTH_SEND_CERT, myCert.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw e
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

        // 2. Pull the card's certificate in chunks and verify it against our CA.
        val peerCertPem = fetchCardCert(isoDep)
        return try {
            CryptoUtils.verifyPeerCert(peerCertPem)
        } catch (e: PeerCertException) {
            throw IOException("Peer certificate rejected: ${e.message}")
        }
    }

    /**
     * Send [payload] under [cmd] as [cmd][flag][chunk] APDUs of at most
     * AUTH_CHUNK_SIZE payload bytes, so no command ever exceeds the peer's HCE
     * receive limit. Intermediate chunks must be acked with 9000; the final
     * chunk's full response is returned to the caller.
     */
    fun sendChunked(isoDep: IsoDep, cmd: ByteArray, payload: ByteArray): ByteArray {
        val cmdName = String(cmd, Charsets.UTF_8)
        var offset = 0
        var index = 0
        while (true) {
            val end = min(offset + CryptoUtils.AUTH_CHUNK_SIZE, payload.size)
            val last = end == payload.size
            val flag = byteArrayOf(if (last) CryptoUtils.AUTH_CHUNK_LAST else CryptoUtils.AUTH_CHUNK_MORE)
            val apdu = Utils.concatArrays(cmd, flag, payload.copyOfRange(offset, end))
            Log.d(TAG, "sendChunked[$cmdName] -> chunk #$index: apduLen=${apdu.size} payload=${end - offset} last=$last")
            val res = isoDep.transceive(apdu)
            if (last) {
                Log.d(TAG, "sendChunked[$cmdName] final resp: len=${res.size} sw=${res.toHex().takeLast(4)}")
                return res
            }
            if (res.size < 2 || !res.copyOfRange(res.size - 2, res.size).contentEquals(Utils.SELECT_OK_SW)) {
                Log.e(TAG, "sendChunked[$cmdName] chunk #$index rejected: resp=${res.toHex()}")
                throw IOException("Peer rejected a data chunk (status ${res.toHex()}). Keep the devices together and retap.")
            }
            offset = end
            index++
        }
    }

    /** Pull the card's certificate chunk by chunk ([flag][chunk][SW] responses). */
    private fun fetchCardCert(isoDep: IsoDep): String {
        val buf = ByteArrayOutputStream()
        repeat(MAX_CERT_CHUNKS) { index ->
            val res = isoDep.transceive(CryptoUtils.CMD_AUTH_GET_CERT)
            if (res.size < 3 || !res.copyOfRange(res.size - 2, res.size).contentEquals(Utils.SELECT_OK_SW)) {
                Log.e(TAG, "fetchCardCert chunk #$index bad resp: ${res.toHex()}")
                throw IOException("Failed to fetch peer certificate (status ${res.toHex()}). Is the peer app logged in?")
            }
            val flag = res[0]
            buf.write(res, 1, res.size - 3)
            Log.d(TAG, "fetchCardCert <- chunk #$index: respLen=${res.size} last=${flag == CryptoUtils.AUTH_CHUNK_LAST} total=${buf.size()}")
            if (flag == CryptoUtils.AUTH_CHUNK_LAST) return String(buf.toByteArray(), Charsets.UTF_8)
        }
        throw IOException("Peer certificate exceeded ${MAX_CERT_CHUNKS * CryptoUtils.AUTH_CHUNK_SIZE} bytes — aborting.")
    }

    // A PEM cert is ~1.3 KB (6 chunks); 64 chunks (~15 KB) is far past any real cert.
    private const val MAX_CERT_CHUNKS = 64

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
