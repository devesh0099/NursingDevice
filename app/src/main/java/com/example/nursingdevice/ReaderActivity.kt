package com.example.nursingdevice

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class ReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var statusTextView: TextView? = null
    private var logTextView: TextView? = null
    private var receivedDataTextView: TextView? = null

    private var scrollView: ScrollView? = null
    private var logScrollView: ScrollView? = null

    private val CHUNK_SIZE = 245
    private val MAX_CHUNK_COUNT = 50000

    private var sessionKey: ByteArray? = null
    private val purpose by lazy { intent.getStringExtra(EXTRA_PURPOSE) ?: PURPOSE_SCAN_PATIENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        try {
            statusTextView = findViewById(R.id.statusText)
            logTextView = findViewById(R.id.logText)
            receivedDataTextView = findViewById(R.id.receivedDataText)
            scrollView = findViewById(R.id.scrollView)
            logScrollView = findViewById(R.id.logScrollView)

            if (statusTextView == null || receivedDataTextView == null || logTextView == null) {
                Toast.makeText(this, "Layout error", Toast.LENGTH_SHORT).show()
                return
            }

            statusTextView?.text = "Waiting for NFC device..."
            logTextView?.text = "Ready to scan...\n"
            enableNFC()

        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error initializing views: ${e.message}", e)
            Toast.makeText(this, "Initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Safely append to the dedicated log text view
    private fun logStep(message: String) {
        runOnUiThread {
            val currentText = logTextView?.text.toString()
            if (currentText.isEmpty()) {
                logTextView?.text = message
            } else {
                logTextView?.text = "$currentText\n$message"
            }
            // Auto-scroll the log window to the bottom
            logScrollView?.post { logScrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun enableNFC() {
        statusTextView?.text = "Waiting for NFC device..."
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d("ReaderActivity", "NFC Tag Discovered!")

        runOnUiThread { statusTextView?.text = "Authenticating..." }
        logStep("Step 1: Tag Discovered. Initiating handshake...")

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 10000

            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw IOException("AID selection failed.")
            logStep("Step 2: App Selected")

            // Phase 3: exchange certificates and derive the peer's public key +
            // our own private key; fall back to the hardcoded pair when disabled.
            val peerPublicKey = if (CryptoUtils.CERT_AUTH_ENABLED) {
                NfcAuth.exchangeCerts(isoDep).also { logStep("Step 2b: Certificates verified") }
            } else CryptoUtils.getOtherPublicKey()
            val myPrivateKey = if (CryptoUtils.CERT_AUTH_ENABLED) {
                CryptoUtils.getSessionPrivateKey() ?: throw IOException("No credential — log in with your PIN.")
            } else CryptoUtils.getMyPrivateKey()

            sessionKey = CryptoUtils.generateSessionKey()
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey!!, peerPublicKey)
            val signature = CryptoUtils.rsaSign(encryptedKey, myPrivateKey)

            // Chunked so the command never exceeds the peer's HCE receive limit.
            var authRes = NfcAuth.sendChunked(isoDep, CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey)
            if (!authRes.isSuccess()) throw IOException("Auth Step 1 (Key Exchange) failed.")
            logStep("Step 3: Session Key Sent")

            authRes = NfcAuth.sendChunked(isoDep, CryptoUtils.CMD_AUTH_SEND_SIG, signature)
            if (!authRes.isSuccess()) throw IOException("Auth Step 2 (Signature Validation) failed.")
            logStep("Step 4: Signature Sent")

            val encryptedAck = authRes.getData()
            val decryptedAck = CryptoUtils.xorEncryptDecrypt(encryptedAck, sessionKey!!)
            if (String(decryptedAck, Charsets.UTF_8) != "AUTH_OK") {
                throw IOException("Authentication rejected by peer — signature/credential mismatch " +
                    "(is the other device logged in and registered under the same CA?).")
            }

            runOnUiThread { statusTextView?.text = "Connection Secured" }
            logStep("Step 5: Secure connection established. Requesting data...")

            response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (response.size <= 2) throw IOException("No metadata received from sender")
            if (!response.isSuccess()) throw IOException("Failed to get metadata.")

            val encryptedMetadata = response.copyOfRange(0, response.size - 2)
            if (encryptedMetadata.isEmpty()) {
                throw IOException("Received empty metadata from sender")
            }

            val metadataPayload = CryptoUtils.xorEncryptDecrypt(encryptedMetadata, sessionKey!!)
            if (metadataPayload.isEmpty()) {
                throw IOException("Decryption failed or empty payload")
            }

            val transferMode = String(metadataPayload.copyOfRange(0, 1), Charsets.UTF_8)

            runOnUiThread { statusTextView?.text = "Transferring Data..." }

            when (transferMode) {
                "T" -> handleTextReception(metadataPayload.copyOfRange(1, metadataPayload.size))
                "F" -> handleFileReception(isoDep, metadataPayload.copyOfRange(1, metadataPayload.size))
                "M" -> {
                    if (purpose == PURPOSE_FETCH_HISTORY) {
                        handleMultiFileReception(isoDep, metadataPayload)
                    } else {
                        handleFileReception(isoDep, metadataPayload.copyOfRange(1, metadataPayload.size))
                    }
                }
                else -> throw IOException("Unknown transfer mode: $transferMode")
            }

        } catch (e: IOException) {
            Log.e("ReaderActivity", "Error: ${e.message}", e)
            logStep("Connection Error: ${e.message}")
            runOnUiThread {
                statusTextView?.text = "Connection Failed"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try { isoDep.close() } catch (e: IOException) { }
            sessionKey = null
        }
    }

    private fun handleFileReception(isoDep: IsoDep, fileInfoPayload: ByteArray) {
        val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
        val fileName = String(fileInfoPayload.copyOfRange(4, fileInfoPayload.size), Charsets.UTF_8)

        logStep("Downloading: $fileName (${String.format("%.2f", fileSize / 1024.0)} KB)")
        var receivedBytes = 0

        try {
            val output = ByteArrayOutputStream()
            var chunkCount = 0
            while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                if (!response.isSuccess()) break

                val encryptedChunk = response.getData()
                val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                output.write(decryptedChunk)
                receivedBytes += decryptedChunk.size
                chunkCount++

                val progress = (receivedBytes * 100 / fileSize)
                logStep("Transferring: ${String.format("%.2f", receivedBytes / 1024.0)} / ${String.format("%.2f", fileSize / 1024.0)} KB ($progress%)")
            }

            displayContent(output.toString(Charsets.UTF_8.name()))
            logStep("Transfer Securely Completed.")
            runOnUiThread { statusTextView?.text = "Transfer Complete" }

        } catch (e: Exception) {
            throw e
        }
    }

    private fun handleMultiFileReception(isoDep: IsoDep, firstMetadataPayload: ByteArray) {
        try {
            val historyManager = NursePatientManager(this)
            val patientId = historyManager.getPatient()?.patientId
                ?.takeIf { it.isNotBlank() && it != "N/A" }
                ?: SessionCache.currentPatientId.takeIf { it.isNotBlank() && it != "N/A" }
                ?: throw IOException("Scan the Aggregator patient card before syncing all notes.")

            var metadataPayload = firstMetadataPayload
            var receivedCount = 0
            var totalBytes = 0

            while (metadataPayload.isNotEmpty()) {
                val mode = String(metadataPayload.copyOfRange(0, 1), Charsets.UTF_8)
                if (mode != "M") throw IOException("Expected history metadata, got $mode")
                if (metadataPayload.size < 5) throw IOException("History metadata was incomplete")

                val fileInfoPayload = metadataPayload.copyOfRange(1, metadataPayload.size)
                val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
                val receivedFileName = String(fileInfoPayload.copyOfRange(4, fileInfoPayload.size), Charsets.UTF_8)

                logStep("Streaming Secure Data: $receivedFileName")

                var receivedBytes = 0
                var chunkCount = 0
                val output = ByteArrayOutputStream()
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val chunkResponse = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!chunkResponse.isSuccess()) throw IOException("Transfer interrupted")

                    val encryptedChunk = chunkResponse.getData()
                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                    output.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    logStep("Transferring: ${String.format("%.2f", receivedBytes / 1024.0)} / ${String.format("%.2f", fileSize / 1024.0)} KB ($progress%)")
                }

                historyManager.saveCloudHistory(output.toString(Charsets.UTF_8.name()), receivedFileName, patientId)
                receivedCount++
                totalBytes += receivedBytes
                logStep("Saved history note: $receivedFileName")

                val response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
                if (!response.isSuccess()) throw IOException("Failed to get next file metadata.")
                metadataPayload = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey!!)
            }

            logStep("Transfer Securely Completed.")
            runOnUiThread {
                receivedDataTextView?.text = "Fetched $receivedCount note(s), $totalBytes bytes total."
                statusTextView?.text = "History Sync Complete"
                Toast.makeText(this, "Fetched $receivedCount history note(s)", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FetchEntireHistoryActivity::class.java).apply {
                    putExtra(FetchEntireHistoryActivity.EXTRA_CACHED_ONLY, true)
                })
                finish()
            }

        } catch (e: Exception) {
            throw e
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val receivedString = String(payload, Charsets.UTF_8)
        logStep("Text received securely")

        if (purpose == PURPOSE_FETCH_HISTORY) {
            runOnUiThread {
                receivedDataTextView?.text = receivedString
                scrollView?.post { scrollView?.scrollTo(0, 0) }
                statusTextView?.text = "History Fetch Complete"
                Toast.makeText(this, receivedString, Toast.LENGTH_LONG).show()
            }
            return
        }

        SessionCache.processScannedData(receivedString)
        NursePatientManager(this).savePatient(receivedString)

        runOnUiThread {
            receivedDataTextView?.text = receivedString
            scrollView?.post { scrollView?.scrollTo(0, 0) }
            statusTextView?.text = "Transfer Complete"
            Toast.makeText(this, "Patient Data Cached. Press Back to update vitals.", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayContent(content: String) {
        try {
            SessionCache.processScannedData(content)
            NursePatientManager(this).savePatient(content)

            runOnUiThread {
                receivedDataTextView?.text = content
                logStep("Patient Loaded! You may now go back.")
                scrollView?.post { scrollView?.scrollTo(0, 0) }
                Toast.makeText(this, "Patient Data Cached. Press Back to update vitals.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error displaying file: ${e.message}", e)
        }
    }

    private fun ByteArray.isSuccess(): Boolean =
        this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()

    private fun ByteArray.getData(): ByteArray =
        this.copyOfRange(0, this.size - 2)

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    companion object {
        const val EXTRA_PURPOSE = "purpose"
        const val PURPOSE_SCAN_PATIENT = "scan_patient"
        const val PURPOSE_FETCH_HISTORY = "fetch_history"
    }
}
