package com.example.nursingdevice

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GetPatientActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_get_patient)

        val statusText = findViewById<TextView>(R.id.statusText)
        val receivedDataText = findViewById<TextView>(R.id.receivedDataText)

        statusText.text = "Current Session History"

        if (SessionCache.sessionHistory.isEmpty()) {
            receivedDataText.text = "No records have been updated during this session."
        } else {
            // Join all session reports together with a divider for easy reading
            receivedDataText.text = SessionCache.sessionHistory.joinToString("\n\n------------------------\n\n")
        }
    }
}