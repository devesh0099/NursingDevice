package com.example.nursingdevice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.nursingdevice.connections.StoragePermission
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var storagePermission: StoragePermission? = null
    private lateinit var sendMedicalFormButton: MaterialButton
    private lateinit var nurseText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nurseText = findViewById(R.id.nurseText)
        sendMedicalFormButton = findViewById(R.id.SendMedicalFormButton)
        val scanPatientTagButton = findViewById<Button>(R.id.ScanPatientTagButton)
        val getPatientBtn = findViewById<Button>(R.id.getPatientBtn)

        val fetchRecordBtn = findViewById<Button>(R.id.fetchRecordBtn)
        val viewFetchedBtn = findViewById<Button>(R.id.viewFetchedBtn)

        fetchRecordBtn.setOnClickListener {
            startActivity(Intent(this, FetchRecordActivity::class.java))
        }

        viewFetchedBtn.setOnClickListener {
            startActivity(Intent(this, ViewFetchedActivity::class.java))
        }

        // Manage Storage Permissions
        storagePermission = StoragePermission(applicationContext, this)
        storagePermission!!.isStoragePermissionGranted()

        sendMedicalFormButton.setOnClickListener {
            val intent = Intent(this, SendForm::class.java)
            startActivity(intent)
        }

        scanPatientTagButton.setOnClickListener {
            val intent = Intent(this, ReaderActivity::class.java)
            startActivity(intent)
        }

        getPatientBtn.setOnClickListener {
            startActivity(Intent(this, GetPatientActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val nurse = NursePatientManager(this).getNurse()
        val nurseLabel = if (nurse.name.isNotEmpty()) "Nurse: ${nurse.name}" else "Not logged in"

        if (SessionCache.currentPatientName == "None") {
            nurseText.text = "$nurseLabel | No Patient Scanned"
            sendMedicalFormButton.isEnabled = false
            sendMedicalFormButton.text = "Scan a Patient Tag First"
            sendMedicalFormButton.alpha = 0.5f
        } else {
            nurseText.text = "$nurseLabel | Patient: ${SessionCache.currentPatientName}"
            sendMedicalFormButton.isEnabled = true
            sendMedicalFormButton.text = "Update Patient Record"
            sendMedicalFormButton.alpha = 1.0f
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StoragePermission.REQUEST_CODE_STORAGE_PERMISSION) {
            Log.d("Storage permission", "Going for storage permission")
            storagePermission!!.onRequestPermissionsResult(requestCode, permissions as Array<String?>, grantResults)
        }
    }
}