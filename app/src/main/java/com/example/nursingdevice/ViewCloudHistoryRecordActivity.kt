package com.example.nursingdevice

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewCloudHistoryRecordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_cloud_history_record)

        val titleText = findViewById<TextView>(R.id.recordTitleText)
        val contentText = findViewById<TextView>(R.id.recordContentText)
        val closeButton = findViewById<Button>(R.id.recordCloseButton)

        titleText.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        contentText.text = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        closeButton.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_UPDATED_AT = "extra_updated_at"
    }
}
