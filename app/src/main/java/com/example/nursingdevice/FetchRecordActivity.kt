package com.example.nursingdevice

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class FetchRecordActivity : BaseActivity(), NfcAdapter.ReaderCallback {

    private var statusTextView: TextView? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null
    private var receivedDataTextView: TextView? = null // Added missing reference

    private var sessionKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        statusTextView = findViewById(R.id.statusText)
        logTextView = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        receivedDataTextView = findViewById(R.id.receivedDataText) // Hooked up the UI element

        statusTextView?.text = "Ready to Fetch..."
        logTextView?.text = "Waiting for Aggregator Sync...\n"
        receivedDataTextView?.text = "Awaiting data..."

        enableNFC()
    }

    private fun logStep(message: String) {
        runOnUiThread {
            val currentText = logTextView?.text.toString()
            logTextView?.text = if (currentText.isEmpty()) message else "$currentText\n$message"
            logScrollView?.post { logScrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun enableNFC() {
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        runOnUiThread { statusTextView?.text = "Authenticating..." }
        logStep("Step 1: Tag Discovered. Initiating handshake...")
        val isoDep = IsoDep.get(tag) ?: return

        try {
            isoDep.connect()
            isoDep.timeout = 10000

            // ===== NSE-AA Mutual Authentication (Sethia et al., 2019) =====

            // Step 1: SELECT — receive challenge
            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess() || response.size < 18)
                throw IOException("AID selection / challenge failed.")
            logStep("Step 2: Challenge Received")

            val serverNonce = response.copyOfRange(0, 16)
            val t1 = response.copyOfRange(16, response.size - 2)

            val t1Plain = CryptoUtils.aesDecrypt(t1, CryptoUtils.getKUD())
            val serverVirtualId = t1Plain.copyOfRange(0, 16)
            val serverNonceFromT1 = t1Plain.copyOfRange(16, 32)

            if (!serverNonceFromT1.contentEquals(serverNonce))
                throw IOException("Challenge nonce mismatch")
            val expectedVId = CryptoUtils.computeOtherVirtualIdentity(serverNonce)
            if (!serverVirtualId.contentEquals(expectedVId))
                throw IOException("Server identity verification failed")
            logStep("Step 3: Server Identity Verified")

            // Step 2: Build AUTH_RESP
            val readerNonce = CryptoUtils.generateNonce()
            val readerVirtualId = CryptoUtils.generateMyVirtualIdentity(readerNonce)

            val t2 = CryptoUtils.aesEncrypt(
                CryptoUtils.MY_PWB + readerNonce + serverNonce,
                CryptoUtils.getKUD()
            )
            sessionKey = CryptoUtils.deriveSessionKey(
                CryptoUtils.MY_PWB, CryptoUtils.OTHER_PWB, readerNonce, serverNonce
            )
            val t3 = CryptoUtils.hmacSha256(sessionKey!!,
                readerVirtualId + readerNonce + serverVirtualId + serverNonce)

            val authCmd = Utils.concatArrays(
                CryptoUtils.CMD_AUTH_RESP, readerVirtualId, readerNonce, t2, t3
            )
            val authRes = isoDep.transceive(authCmd)
            if (!authRes.isSuccess()) throw IOException("AUTH_RESP rejected.")
            logStep("Step 4: Auth Response Sent")

            // Step 3: Verify mutual auth confirmation
            val t4 = authRes.copyOfRange(0, authRes.size - 2)
            val t4Plain = CryptoUtils.aesDecrypt(t4, sessionKey!!)
            val authOk = String(t4Plain.copyOfRange(0, 7), Charsets.UTF_8)
            val serverPwb = t4Plain.copyOfRange(7, 39)
            val echoedNonce = t4Plain.copyOfRange(39, 55)

            if (authOk != "AUTH_OK" ||
                !serverPwb.contentEquals(CryptoUtils.OTHER_PWB) ||
                !echoedNonce.contentEquals(readerNonce)) {
                throw IOException("NSE-AA mutual authentication failed")
            }

            runOnUiThread { statusTextView?.text = "Downloading Data..." }
            logStep("Step 5: NSE-AA Mutual Auth Complete. Requesting data...")

            response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)

            if (response.size <= 2) {
                throw IOException("No data available. Make sure Aggregator is ready on the Sync screen.")
            }

            val encryptedMetadata = response.copyOfRange(0, response.size - 2)
            val metadata = CryptoUtils.xorEncryptDecrypt(encryptedMetadata, sessionKey!!)

            if (metadata.isEmpty()) {
                throw IOException("Received empty metadata block.")
            }

            val transferMode = String(metadata.copyOfRange(0, 1), Charsets.UTF_8)

            when (transferMode) {
                "T" -> handleTextReception(metadata.copyOfRange(1, metadata.size))
                "F", "M" -> handleFileReception(isoDep, metadata.copyOfRange(1, metadata.size))
                else -> throw IOException("Unknown transfer mode: $transferMode")
            }

        } catch (e: Exception) {
            logStep("Error: ${e.message}")
            runOnUiThread {
                statusTextView?.text = "Fetch Failed"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
            sessionKey = null
        }
    }

    private fun handleFileReception(isoDep: IsoDep, fileInfoPayload: ByteArray) {
        val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
        val tempFile = File(cacheDir, "temp_fetched.txt")
        var receivedBytes = 0

        try {
            FileOutputStream(tempFile).use { fos ->
                while (receivedBytes < fileSize) {
                    val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)

                    if (response.size <= 2) throw IOException("Transfer interrupted by sender.")

                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(response.copyOfRange(0, response.size - 2), sessionKey!!)
                    fos.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size

                    val progress = if (fileSize > 0) (receivedBytes * 100 / fileSize) else 0
                    logStep("Transferring: $receivedBytes / $fileSize bytes ($progress%)")
                }
            }

            val fetchedText = tempFile.readText(Charsets.UTF_8)
            SessionCache.fetchedRecordData = fetchedText

            runOnUiThread {
                statusTextView?.text = "Fetch Complete"
                receivedDataTextView?.text = fetchedText // Instantly update the UI box
                logStep("File securely decrypted and cached.")
                Toast.makeText(this, "Record Fetched! Press back to view.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val fetchedText = String(payload, Charsets.UTF_8)
        SessionCache.fetchedRecordData = fetchedText

        runOnUiThread {
            statusTextView?.text = "Fetch Complete"
            receivedDataTextView?.text = fetchedText // Instantly update the UI box
            logStep("Text response securely cached.")
            Toast.makeText(this, "Response Fetched! Press back to view.", Toast.LENGTH_LONG).show()
        }
    }

    private fun ByteArray.isSuccess(): Boolean =
        this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        enableNFC()
    }
}