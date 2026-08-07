package com.example.nursingdevice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.nursingdevice.connections.StoragePermission
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var storagePermission: StoragePermission? = null
    private lateinit var sendMedicalFormButton: MaterialButton
    private lateinit var fetchEntireHistoryButton: MaterialButton
    private lateinit var nurseText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nurseText = findViewById(R.id.nurseText)
        sendMedicalFormButton = findViewById(R.id.SendMedicalFormButton)
        fetchEntireHistoryButton = findViewById(R.id.fetchEntireHistoryBtn)
        val scanPatientTagButton = findViewById<Button>(R.id.ScanPatientTagButton)
        val getPatientBtn = findViewById<Button>(R.id.getPatientBtn)
        val transportSwitch = findViewById<Switch>(R.id.transportModeSwitch)
        transportSwitch.isChecked = TransferModeStore.isWifiDirect(this)
        transportSwitch.setOnCheckedChangeListener { _, checked ->
            TransferModeStore.setWifiDirect(this, checked)
        }

        val fetchRecordBtn = findViewById<Button>(R.id.fetchRecordBtn)
        val viewFetchedBtn = findViewById<Button>(R.id.viewFetchedBtn)

        fetchRecordBtn.setOnClickListener {
            if (TransferModeStore.isWifiDirect(this)) {
                startActivity(Intent(this, WifiDirectTransferActivity::class.java).apply {
                    putExtra(WifiDirectTransferActivity.EXTRA_DIRECTION, WifiDirectTransferActivity.DIRECTION_RECEIVE)
                    putExtra(WifiDirectTransferActivity.EXTRA_PURPOSE, WifiDirectTransferActivity.PURPOSE_FETCH_RECORD)
                })
            } else {
                startActivity(Intent(this, FetchRecordActivity::class.java))
            }
        }

        fetchEntireHistoryButton.setOnClickListener {
            showFetchHistorySourceDialog()
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
            if (TransferModeStore.isWifiDirect(this)) {
                startActivity(Intent(this, WifiDirectTransferActivity::class.java).apply {
                    putExtra(WifiDirectTransferActivity.EXTRA_DIRECTION, WifiDirectTransferActivity.DIRECTION_RECEIVE)
                    putExtra(WifiDirectTransferActivity.EXTRA_PURPOSE, WifiDirectTransferActivity.PURPOSE_SCAN_PATIENT)
                })
            } else {
                val intent = Intent(this, ReaderActivity::class.java)
                startActivity(intent)
            }
        }

        getPatientBtn.setOnClickListener {
            startActivity(Intent(this, GetPatientActivity::class.java))
        }
    }

    private fun showFetchHistorySourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Fetch entire history")
            .setItems(arrayOf("Cloud", "mCard")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, FetchEntireHistoryActivity::class.java))
                    1 -> startMCardHistoryFetch()
                }
            }
            .show()
    }

    private fun startMCardHistoryFetch() {
        if (TransferModeStore.isWifiDirect(this)) {
            startActivity(Intent(this, WifiDirectTransferActivity::class.java).apply {
                putExtra(WifiDirectTransferActivity.EXTRA_DIRECTION, WifiDirectTransferActivity.DIRECTION_RECEIVE)
                putExtra(WifiDirectTransferActivity.EXTRA_PURPOSE, WifiDirectTransferActivity.PURPOSE_FETCH_HISTORY)
            })
        } else {
            startActivity(Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_PURPOSE, ReaderActivity.PURPOSE_FETCH_HISTORY)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        val manager = NursePatientManager(this)
        SessionCache.loadPatient(manager.getPatient())
        SessionCache.setFetchedRecord(manager.getLatestFetchedRecord())

        val nurse = manager.getNurse()
        val nurseLabel = if (nurse.name.isNotEmpty()) "Nurse: ${nurse.name}" else "Not logged in"

        if (SessionCache.currentPatientName == "None") {
            nurseText.text = "$nurseLabel | No Patient Scanned"
            sendMedicalFormButton.isEnabled = false
            fetchEntireHistoryButton.isEnabled = false
            sendMedicalFormButton.text = "Scan a Patient Tag First"
            sendMedicalFormButton.alpha = 0.5f
            fetchEntireHistoryButton.alpha = 0.5f
        } else {
            nurseText.text = "$nurseLabel | Patient: ${SessionCache.currentPatientName}"
            sendMedicalFormButton.isEnabled = true
            fetchEntireHistoryButton.isEnabled = true
            sendMedicalFormButton.text = "Update Patient Record"
            sendMedicalFormButton.alpha = 1.0f
            fetchEntireHistoryButton.alpha = 1.0f
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
            storagePermission!!.onRequestPermissionsResult(requestCode, permissions.copyOf(), grantResults)
        }
    }
}
