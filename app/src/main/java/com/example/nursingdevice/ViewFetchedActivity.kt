package com.example.nursingdevice

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewFetchedActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_fetched)

        val fetchedDataText = findViewById<TextView>(R.id.fetchedDataText)
        fetchedDataText.text = SessionCache.fetchedRecordData
    }
}