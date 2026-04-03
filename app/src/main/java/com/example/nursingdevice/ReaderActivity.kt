package com.example.nursingdevice

import android.content.Intent
import android.net.Uri
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

class ReaderActivity : BaseActivity(), NfcAdapter.ReaderCallback {

    private var statusTextView: TextView? = null
    private var logTextView: TextView? = null
    private var receivedDataTextView: TextView? = null

    private var scrollView: ScrollView? = null
    private var logScrollView: ScrollView? = null

    private val CHUNK_SIZE = 245
    private val MAX_CHUNK_COUNT = 50000

    private var sessionKey: ByteArray? = null

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

            sessionKey = CryptoUtils.generateSessionKey()
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey!!, CryptoUtils.getOtherPublicKey())
            val signature = CryptoUtils.rsaSign(encryptedKey, CryptoUtils.getMyPrivateKey())

            val sendKeyCmd = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey)
            var authRes = isoDep.transceive(sendKeyCmd)
            if (!authRes.isSuccess()) throw IOException("Auth Step 1 (Key Exchange) failed.")
            logStep("Step 3: Session Key Sent")

            val sendSigCmd = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_SIG, signature)
            authRes = isoDep.transceive(sendSigCmd)
            if (!authRes.isSuccess()) throw IOException("Auth Step 2 (Signature Validation) failed.")
            logStep("Step 4: Signature Sent")

            val encryptedAck = authRes.getData()
            val decryptedAck = CryptoUtils.xorEncryptDecrypt(encryptedAck, sessionKey!!)
            if (String(decryptedAck, Charsets.UTF_8) != "AUTH_OK") {
                throw IOException("Authentication rejected by the sender.")
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
                "M" -> handleMultiFileReception(isoDep)
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
        val tempFile = File(cacheDir, fileName)
        var receivedBytes = 0

        try {
            FileOutputStream(tempFile).use { fos ->
                var chunkCount = 0
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!response.isSuccess()) break

                    val encryptedChunk = response.getData()
                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                    fos.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    logStep("Transferring: ${String.format("%.2f", receivedBytes / 1024.0)} / ${String.format("%.2f", fileSize / 1024.0)} KB ($progress%)")
                }
            }

            displayFileInView(tempFile)
            logStep("Transfer Securely Completed.")
            runOnUiThread { statusTextView?.text = "Transfer Complete" }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun handleMultiFileReception(isoDep: IsoDep) {
        val fileName = "aggregated_reports.txt"
        val tempFile = File(cacheDir, fileName)

        try {
            val response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get file metadata.")

            val metadataPayload = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey!!)
            val fileSize = ByteBuffer.wrap(metadataPayload.copyOfRange(0, 4)).int
            val receivedFileName = String(metadataPayload.copyOfRange(4, metadataPayload.size), Charsets.UTF_8)

            logStep("Streaming Secure Data: $receivedFileName")

            var receivedBytes = 0
            var chunkCount = 0

            FileOutputStream(tempFile).use { fos ->
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val chunkResponse = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!chunkResponse.isSuccess()) break

                    val encryptedChunk = chunkResponse.getData()
                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                    fos.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    logStep("Transferring: ${String.format("%.2f", receivedBytes / 1024.0)} / ${String.format("%.2f", fileSize / 1024.0)} KB ($progress%)")
                }
            }

            displayFileInView(tempFile)
            logStep("Transfer Securely Completed.")
            runOnUiThread { statusTextView?.text = "Transfer Complete" }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val receivedString = String(payload, Charsets.UTF_8)
        logStep("Text received securely")

        SessionCache.processScannedData(receivedString)

        runOnUiThread {
            receivedDataTextView?.text = receivedString
            scrollView?.post { scrollView?.scrollTo(0, 0) }
            statusTextView?.text = "Transfer Complete"
            Toast.makeText(this, "Patient Data Cached. Press Back to update vitals.", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayFileInView(file: File) {
        try {
            val content = file.readText(Charsets.UTF_8)

            SessionCache.processScannedData(content)

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
}