package com.example.nursingdevice

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SendDocumentActivity : BaseActivity() {

    private lateinit var headerStatusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var detailedLogText: TextView
    private lateinit var logScrollView: ScrollView

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_document)

        headerStatusText = findViewById(R.id.DisplaySelectedDocument)
        fileNameText = findViewById(R.id.syncFileNameText)
        detailedLogText = findViewById(R.id.syncStatusText)
        logScrollView = findViewById(R.id.logScrollView)

        val filePath = intent.getStringExtra("FILE_PATH")
        val fileName = intent.getStringExtra("FILE_NAME")

        if (filePath != null) {
            handleMedicalDataFile(filePath, fileName)
        } else {
            Toast.makeText(this, "Error: No file to send", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleMedicalDataFile(filePath: String, fileName: String?) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(this, "Error: File not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val fileContent = file.readBytes()
            MyHostApduService.setFileForTransfer(fileContent, "text/plain")

            val displayName = fileName ?: file.name
            headerStatusText.text = "Waiting for Receiver..."
            fileNameText.text = "Payload: $displayName\nSize: ${fileContent.size} bytes"
            detailedLogText.text = "Ready to transmit. Hold near reader.\n"

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
        val filePath = intent.getStringExtra("FILE_PATH")
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists()) {
                SessionCache.addUpdatedRecord(file.readText())
                file.delete()
            }
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
