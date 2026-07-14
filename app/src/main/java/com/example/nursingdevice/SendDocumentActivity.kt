package com.example.nursingdevice

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SendDocumentActivity : AppCompatActivity() {

    private lateinit var headerStatusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var detailedLogText: TextView
    private lateinit var logScrollView: ScrollView
    private var wifiDirectLaunched = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_document)

        headerStatusText = findViewById(R.id.DisplaySelectedDocument)
        fileNameText = findViewById(R.id.syncFileNameText)
        detailedLogText = findViewById(R.id.syncStatusText)
        logScrollView = findViewById(R.id.logScrollView)

        val fileName = intent.getStringExtra("FILE_NAME")
        val fileContent = intent.getStringExtra("FILE_CONTENT")

        if (fileContent != null) {
            handleMedicalDataFile(fileContent, fileName)
        } else {
            Toast.makeText(this, "Error: No file to send", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleMedicalDataFile(fileContentText: String, fileName: String?) {
        try {
            val fileContent = fileContentText.toByteArray(Charsets.UTF_8)
            MyHostApduService.setFileForTransfer(fileContent, "text/plain")

            val displayName = fileName ?: "medical_update.txt"
            headerStatusText.text = "Waiting for Receiver..."
            fileNameText.text = "Payload: $displayName\nSize: ${fileContent.size} bytes"
            detailedLogText.text = if (TransferModeStore.isWifiDirect(this)) {
                "Ready to transmit over Wi-Fi Direct.\n"
            } else {
                "Ready to transmit. Hold near reader.\n"
            }

            if (TransferModeStore.isWifiDirect(this) && !wifiDirectLaunched) {
                wifiDirectLaunched = true
                startActivity(Intent(this, WifiDirectTransferActivity::class.java).apply {
                    putExtra(WifiDirectTransferActivity.EXTRA_DIRECTION, WifiDirectTransferActivity.DIRECTION_SEND)
                })
            }

        } catch (e: Exception) {
            Log.e("SendDocumentActivity", "Failed to load file", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        MyHostApduService.onStatusUpdate = { message ->
            if (message == "Step 1: Connection Established") {
                headerStatusText.text = "Authenticating..."
            } else if (message == "Transmitting Data...") {
                headerStatusText.text = "Sending Data..."
            } else if (message == "Transfer Complete" || message.contains("Completed")) {
                headerStatusText.text = "Transfer Complete"
                onTransferComplete()
            }

            val currentText = detailedLogText.text.toString()
            if (currentText.isEmpty()) {
                detailedLogText.text = message
            } else {
                detailedLogText.text = "$currentText\n$message"
            }
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onPause() {
        super.onPause()
        MyHostApduService.onStatusUpdate = null
    }

    private fun onTransferComplete() {
        // Record to session history only after successful NFC transfer
        val fileContent = intent.getStringExtra("FILE_CONTENT")
        if (fileContent != null) {
            SessionCache.addUpdatedRecord(fileContent)
            NursePatientManager(this).addSessionRecord(fileContent, intent.getStringExtra("FILE_NAME"))
        }

        Toast.makeText(this, "Data synced successfully", Toast.LENGTH_SHORT).show()

        // Navigate back to home screen, clearing the back stack so user can't go back to the form
        val homeIntent = Intent(this, MainActivity::class.java)
        homeIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        MyHostApduService.onStatusUpdate = null
        MyHostApduService.resetTransferState()
    }
}
