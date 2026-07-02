package com.example.nursingdevice

import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    // 1. My Private Key (Who am I?)
    private const val MY_PRIVATE_KEY_STR = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDVsnB+TKHieG+D8meSl0Q0AdJuAE8uyCqOhxHwpfnoAtijtfvKuYLX4iuNvtPvkf6y7pDjjeskXl6W+CrNj05c3qH170Vra9iTkCoQeHlYYIJwqhFDyk0nAHMo6U47/M7s5+fMwuhWGSvy2RjZM2h2/wvzI0GjMQRvdX3JeP0IzA7aFHCHM5tVWxvWVwb/QwS8kWnhr+XLsPLo3ac3F5YJS9os7MMGGA3SNAFnuK4ANal87wuVjUgGUqEBNQ0hvrZeNueyX6GRT7j2e9gTB81/7qjP+DTIbJvXwDbuyl/TLn4hiANsPsfMmyKEb3x9uVlgEyDDBvvy4yJKfe2EeKq3AgMBAAECggEABPk7GtPP9rqxc1adqEp1/DUVcsyPpNNkZ8UAzi/tIfdWMT7UjLJs0N2pZtcL2ekOqDDbCJ8udSSIutnGx6b8sdBxE42NX5BdTfEbUYVqr/uUpAIJSdS4OMUPAq/Y/snSmEfwQkWmoHTDuMpQ6wk1XR09gXV1TlK41QFDVRn7eNkAFPlh9OEIig2xJTCjT6SMna1OS/eVOGV0in8YBUPRTKzv+88YrZeyNa/lAvsd5HM9dGuzOK4Gv14UG00pqLvdaxyxy5uSzhuiNpuxLgYPcw3z0g+Z1HMImG/yQfHTTmqQ+1/b8h18mi3lZ7Yn6lIZ/C1plcMMMzJB60T0KpXOjQKBgQDuikarhdm8bwYIDhihqfbtUNFbb/VpjzAKZcPu8gEN6PNEvJ+2QplSHnxiKH3GkS46L/WpSGLvkerhuc1FkYhilaJnkm1t3JasNxlMzht0YRrkd0rl9pHKyAM/Iv7XKFWHCPfigFTeD/NpMMAuPFuCgtiLbOp5dgsYTZeqfeieLQKBgQDlVqhNrjCp9233sIO6h6eV8qt3pfZfCVFWWhvusG3uCZTPYnU3E7D7neGQf5LWdEssJ/lTmjX40qhRZnGA/SrOEpOkrdb6EwGrNg+NHN7xS3ALCV72so87juZ9zDetwIlYHXCsOsY6a9v5Tfwi7I5o+AQPTpY7DKLQ0YkF5K5e8wKBgQDXh4rpCcy9bJdHie0JjTe2H3K6qoNUeHMQwfhyGqmHNvcvITsxhCViRHdgfXN/icf5/UF7ThNOoUpX3/iwJhnT8Z3G9U/4lvpw6mvqsMOvuNmCmqLK+6mpTmVYk7ctEp2MoDbRqeCEsGbfoZPTcufAGZetCqElU9ocgGdMPe2DlQKBgF+XqF2Ars4Z0V991tqIMsVgujIMHk5svteEhcIPDjM7ESkEPCFx9sJag6vMUTNMlAzauKUtUTPe8sPDNKp0XEQ7IAlzYHkqNPbdeMvz7cWcER64kDm6IdPMc6yZ09d7uoPc+ZsAgKHXVYBsDh2shougXWjX8+y3DqKFxFyNflUbAoGBAMzuPmXhgkDITOa7mS9FoV/E54B+OTEqjGi7zy6BmC7dxCbkjzYI/mdHsO3vPEeGENXv+S54PYQIu6/HtzQLQd/2KqFef7h4MB3rZrpJapsKlkNZy/j5IQuilbZk09psycMAPBQL2W3CBC5zdcdE/lO4Oifyku5S+yr6lypjbxsj"

    // 2. Their Public Key (Who do I trust?)
    private const val OTHER_PUBLIC_KEY_STR = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmZlPs2oh/gQQ0oxBq5fRapYoZ6f1oL21UQ+eFWi6spgvF1rFK++cyfglbMDLvkV94SQb2vmbwGL1qNB6wvb8O2knNiiNUweGqV5UQl1Nfb+KpI1gvgwM7tXP1wg/Bmsf0Yo3sJeeYCwY++EigHkvv6Hsd1JP1F0YhgLF9requ2bkhld/6+2fJ4ZfG5x+bSXAHo1/Gwthbf0tfMWDQ0GPNJW/rkyrzALP2EZHKxHDFIu3ZgJl6VR5JaRKUhLD97Fgd8QVE+9gx6eDxJ6p4Gmnjc7/sRpwviklfSGXgQJhHX7LvoWstniRqQWPR550UmovTOhgW6RzwPZvnu3J/CobpwIDAQAB"

    // --- APDU Commands for 2-Step Auth ---
    val CMD_AUTH_SEND_KEY = "AUTH_KEY".toByteArray(Charsets.UTF_8) // Step 1: Send Encrypted Session Key
    val CMD_AUTH_SEND_SIG = "AUTH_SIG".toByteArray(Charsets.UTF_8) // Step 2: Send Signature

    // --- RSA Helpers ---

    fun getMyPrivateKey(): PrivateKey {
        val keyBytes = Base64.decode(MY_PRIVATE_KEY_STR, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    fun getOtherPublicKey(): PublicKey {
        val keyBytes = Base64.decode(OTHER_PUBLIC_KEY_STR, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    // --- Operations ---

    // Generate a fresh AES Session Key (16 bytes / 128 bit)
    fun generateSessionKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(128)
        return keyGen.generateKey().encoded
    }

    // RSA Encrypt (Confidentiality: Only THEY can read it)
    fun rsaEncrypt(data: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    // RSA Decrypt (Confidentiality: Only I can read it)
    fun rsaDecrypt(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    // RSA Sign (Authentication: Prove I sent it)
    fun rsaSign(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    // RSA Verify (Authentication: Prove THEY sent it)
    fun rsaVerify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(signature)
    }

    // XOR Encryption for File Data (Using the Session Key)
    fun xorEncryptDecrypt(data: ByteArray, sessionKey: ByteArray): ByteArray {
        val output = ByteArray(data.size)
        for (i in data.indices) {
            output[i] = (data[i].toInt() xor sessionKey[i % sessionKey.size].toInt()).toByte()
        }
        return output
    }

    // ------------------------------------------------------------------------
    // Phase 3 — credential-backed keys + certificate verification.
    //
    // When CERT_AUTH_ENABLED is true, the NFC handshake should use the per-device
    // credential (loaded via CredentialStore.unlock at login) and verify the
    // peer's certificate against the stored CA cert, instead of the hardcoded
    // MY_PRIVATE_KEY_STR / OTHER_PUBLIC_KEY_STR pair.
    //
    // Flag defaults to false so the existing (working, hardcoded-key) handshake is
    // preserved until the cert-exchange step is integrated and tested on hardware.
    // See CREDENTIALS_AND_STORAGE_PLAN.md §8 / PHASE_2_3_DEVICE_TESTING.md.
    // ------------------------------------------------------------------------

    // Cert-based mutual auth is fully wired (cert exchange on all endpoints).
    // Requires BOTH devices registered + logged in (credential loaded in memory).
    // Set to false on BOTH apps to fall back to the legacy hardcoded-key handshake.
    const val CERT_AUTH_ENABLED = true

    // APDU command for the added cert-exchange step (reader -> card).
    val CMD_AUTH_SEND_CERT = "AUTH_CRT".toByteArray(Charsets.UTF_8)

    /** This device's private key from the unlocked credential, or null if locked. */
    fun getSessionPrivateKey(): PrivateKey? {
        val b64 = CredentialHolder.current?.privateKeyB64 ?: return null
        val spec = PKCS8EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT))
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /** This device's own certificate (PEM) to present to a peer, or null. */
    fun getMyCertificatePem(): String? = CredentialHolder.current?.certPem

    /** The CA certificate (PEM) this device trusts, or null. */
    fun getCaCertificatePem(): String? = CredentialHolder.current?.caCertPem

    private fun parseCert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))) as X509Certificate

    /**
     * Verify a peer's certificate against our stored CA cert and return the peer's
     * public key. Throws PeerCertException with an EXACT, human-readable reason on
     * any failure, so the NFC UI/logs can show precisely what went wrong.
     */
    fun verifyPeerCert(peerCertPem: String): PublicKey {
        val caPem = getCaCertificatePem()
            ?: throw PeerCertException("no CA certificate on this device — log in with your PIN first")

        val ca = try { parseCert(caPem) }
            catch (e: Exception) { throw PeerCertException("stored CA certificate is unreadable: ${e.message}") }

        val peer = try { parseCert(peerCertPem) }
            catch (e: Exception) { throw PeerCertException("peer sent a malformed certificate: ${e.message}") }

        try {
            peer.verify(ca.publicKey)
        } catch (e: java.security.SignatureException) {
            throw PeerCertException("peer certificate is NOT signed by the trusted CA (untrusted / different CA)")
        } catch (e: Exception) {
            throw PeerCertException("could not verify peer certificate signature: ${e.message}")
        }

        try {
            peer.checkValidity()
        } catch (e: java.security.cert.CertificateExpiredException) {
            throw PeerCertException("peer certificate expired on ${peer.notAfter}")
        } catch (e: java.security.cert.CertificateNotYetValidException) {
            throw PeerCertException("peer certificate not valid until ${peer.notBefore} (check device clock)")
        }

        return peer.publicKey
    }

    /** Null-returning convenience wrapper around [verifyPeerCert] (logs the reason). */
    fun verifyPeerCertAndExtractKey(peerCertPem: String): PublicKey? =
        try { verifyPeerCert(peerCertPem) }
        catch (e: Exception) { Log.e("CryptoUtils", "Peer cert rejected: ${e.message}"); null }
}

/** Thrown when a peer's NFC certificate cannot be trusted. Message states the exact reason. */
class PeerCertException(message: String) : Exception(message)