package com.example.nursingdevice

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GetPatientActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_get_patient)

        val statusText = findViewById<TextView>(R.id.statusText)
        val receivedDataText = findViewById<TextView>(R.id.receivedDataText)

        statusText.text = "Current Session History"

        val sessionRecords = NursePatientManager(this).getSessionRecords()
        if (sessionRecords.isEmpty()) {
            receivedDataText.text = "No records have been updated during this session."
        } else {
            receivedDataText.text = sessionRecords.joinToString("\n\n------------------------\n\n")
        }
    }
}
