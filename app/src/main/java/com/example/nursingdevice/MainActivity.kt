package com.example.nursingdevice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
// Make sure to copy the connections/StoragePermission.java file over too!
import com.example.nursingdevice.connections.StoragePermission

class MainActivity : AppCompatActivity() {

    private var storagePermission: StoragePermission? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This links it to your XML layout!
        setContentView(R.layout.activity_main)

        val manager = NursePatientManager(this)
        val nurse = manager.getNurse()

        val nurseText = findViewById<TextView>(R.id.nurseText)
        nurseText.text = "👩‍⚕️ Nurse: ${nurse.name}"

        // Renamed these to match our nursing app discussion earlier
        val sendMedicalFormButton = findViewById<Button>(R.id.SendMedicalFormButton)
        val scanPatientTagButton = findViewById<Button>(R.id.ScanPatientTagButton)
        val getPatientBtn = findViewById<Button>(R.id.getPatientBtn)

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == StoragePermission.REQUEST_CODE_STORAGE_PERMISSION) {
            storagePermission!!.onActivityResult(requestCode, resultCode, data)
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
            storagePermission!!.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}