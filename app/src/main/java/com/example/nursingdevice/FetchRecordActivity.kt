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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class FetchRecordActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

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

            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw IOException("AID selection failed.")
            logStep("Step 2: App Selected")

            // Phase 3: exchange certificates for the peer public key + our private key.
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
            if (!authRes.isSuccess()) throw IOException("Auth Step 1 failed.")
            logStep("Step 3: Session Key Sent")

            authRes = NfcAuth.sendChunked(isoDep, CryptoUtils.CMD_AUTH_SEND_SIG, signature)
            if (!authRes.isSuccess()) throw IOException("Auth Step 2 failed.")
            logStep("Step 4: Signature Sent")

            val decryptedAck = CryptoUtils.xorEncryptDecrypt(authRes.copyOfRange(0, authRes.size - 2), sessionKey!!)
            if (String(decryptedAck, Charsets.UTF_8) != "AUTH_OK") {
                throw IOException("Authentication rejected by peer — signature/credential mismatch " +
                    "(is the other device logged in and registered under the same CA?).")
            }

            runOnUiThread { statusTextView?.text = "Downloading Data..." }
            logStep("Step 5: Connection Secured. Requesting data...")

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
        var receivedBytes = 0

        try {
            val output = ByteArrayOutputStream()
            while (receivedBytes < fileSize) {
                val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)

                if (response.size <= 2) throw IOException("Transfer interrupted by sender.")

                val decryptedChunk = CryptoUtils.xorEncryptDecrypt(response.copyOfRange(0, response.size - 2), sessionKey!!)
                output.write(decryptedChunk)
                receivedBytes += decryptedChunk.size

                val progress = if (fileSize > 0) (receivedBytes * 100 / fileSize) else 0
                logStep("Transferring: $receivedBytes / $fileSize bytes ($progress%)")
            }

            val fetchedText = output.toString(Charsets.UTF_8.name())
            SessionCache.setFetchedRecord(fetchedText)
            NursePatientManager(this).saveFetchedRecord(fetchedText)

            runOnUiThread {
                statusTextView?.text = "Fetch Complete"
                receivedDataTextView?.text = fetchedText // Instantly update the UI box
                logStep("File securely decrypted and cached.")
                Toast.makeText(this, "Record Fetched! Press back to view.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val fetchedText = String(payload, Charsets.UTF_8)
        SessionCache.setFetchedRecord(fetchedText)
        NursePatientManager(this).saveFetchedRecord(fetchedText)

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
