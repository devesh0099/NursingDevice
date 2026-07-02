package com.example.nursingdevice

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var manager: NursePatientManager
    private val repo = NurseRepository()

    private lateinit var nurseIdInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var loginBtn: MaterialButton
    private lateinit var toggleLink: TextView
    private lateinit var registerSection: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var ageInput: EditText
    private lateinit var genderInput: EditText
    private lateinit var pocSpinner: Spinner
    private lateinit var contactInput: EditText
    private lateinit var registerBtn: MaterialButton

    private var isRegisterMode = false

    private val pocValues = listOf("homecare", "first_responder", "ambulance", "hospital")
    private val pocLabels = listOf("Home Care", "First Responder", "Ambulance", "Hospital")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        manager = NursePatientManager(this)

        nurseIdInput    = findViewById(R.id.nurseIdInput)
        pinInput        = findViewById(R.id.pinInput)
        loginBtn        = findViewById(R.id.loginBtn)
        toggleLink      = findViewById(R.id.toggleLink)
        registerSection = findViewById(R.id.registerSection)
        nameInput       = findViewById(R.id.nurseNameInput)
        ageInput        = findViewById(R.id.nurseAgeInput)
        genderInput     = findViewById(R.id.nurseGenderInput)
        pocSpinner      = findViewById(R.id.pocSpinner)
        contactInput    = findViewById(R.id.nurseContactInput)
        registerBtn     = findViewById(R.id.registerBtn)

        pocSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pocLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        toggleLink.setOnClickListener { toggleMode() }
        loginBtn.setOnClickListener { handleLogin() }
        registerBtn.setOnClickListener { handleRegister() }
    }

    private fun toggleMode() {
        isRegisterMode = !isRegisterMode
        registerSection.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
        toggleLink.text = if (isRegisterMode) "Already registered? Login" else "New nurse? Register here"
    }

    private fun handleLogin() {
        val nurseId = nurseIdInput.text.toString().trim()
        val pin     = pinInput.text.toString().trim()
        if (nurseId.isEmpty()) {
            Toast.makeText(this, "Enter your Nurse ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.isEmpty()) {
            Toast.makeText(this, "Enter your PIN", Toast.LENGTH_SHORT).show()
            return
        }

        // Unlock the encrypted credential store with the PIN. This both verifies
        // the PIN and loads the nurse's credentials into memory for NFC use.
        val unlock = CredentialStore.unlock(this, pin, nurseId)

        // A wrong PIN is fatal for login regardless of the network path — stop here
        // with the exact reason so the nurse knows what happened.
        if (unlock is UnlockResult.Failed) {
            Toast.makeText(this, "Login failed: ${unlock.reason}", Toast.LENGTH_LONG).show()
            return
        }
        val noCreds = unlock is UnlockResult.NoCredential

        // Local fast-path: same nurse, credentials already on this device.
        val cached = manager.getNurse()
        if (cached.id == nurseId && cached.id.isNotEmpty()) {
            if (noCreds) {
                Toast.makeText(this, "PIN OK, but no credentials on this device yet. Register or 'rotate' to provision them.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Welcome back, ${cached.name}!", Toast.LENGTH_SHORT).show()
            }
            goToMain()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            repo.login(nurseId).fold(
                onSuccess = { data ->
                    manager.saveNurseData(data)
                    if (noCreds) {
                        Toast.makeText(
                            this@AuthActivity,
                            "Logged in, but no credentials on this device. Re-register or rotate to provision them.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@AuthActivity, "Welcome, ${data.name}!", Toast.LENGTH_SHORT).show()
                    }
                    goToMain()
                },
                onFailure = { err ->
                    Toast.makeText(this@AuthActivity, err.message ?: "Login failed", Toast.LENGTH_LONG).show()
                    setLoading(false)
                }
            )
        }
    }

    private fun handleRegister() {
        val nurseId = nurseIdInput.text.toString().trim()
        val pin     = pinInput.text.toString().trim()
        val name    = nameInput.text.toString().trim()
        val age     = ageInput.text.toString().trim().toIntOrNull()
        val gender  = genderInput.text.toString().trim().takeIf { it.isNotBlank() }
        val poc     = pocValues[pocSpinner.selectedItemPosition]
        val contact = contactInput.text.toString().trim().takeIf { it.isNotBlank() }

        if (nurseId.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Nurse ID and Name are required", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length < 4) {
            Toast.makeText(this, "Set a PIN of at least 4 digits", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            repo.register(nurseId, name, age, gender, poc, contact).fold(
                onSuccess = { reg ->
                    manager.saveNurseData(reg.nurse)
                    // Persist the server-issued credentials, encrypted under this PIN.
                    val creds = reg.credentials
                    if (creds?.privateKey != null) {
                        val saved = CredentialStore.saveFromServer(
                            this@AuthActivity, pin, nurseId, "nurse", creds
                        )
                        val note = if (saved) "credentials secured" else "credential save failed"
                        Toast.makeText(this@AuthActivity, "Registered as ${reg.nurse.name} ($note)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@AuthActivity,
                            "Registered as ${reg.nurse.name}, but no credentials returned. Try 'rotate' later.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    goToMain()
                },
                onFailure = { err ->
                    // Save locally even if backend is unreachable so the nurse can work offline
                    manager.saveNurse(Nurse(name = name, id = nurseId))
                    Toast.makeText(
                        this@AuthActivity,
                        "Saved locally (backend: ${err.message})",
                        Toast.LENGTH_LONG
                    ).show()
                    goToMain()
                }
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        loginBtn.isEnabled = !loading
        registerBtn.isEnabled = !loading
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
