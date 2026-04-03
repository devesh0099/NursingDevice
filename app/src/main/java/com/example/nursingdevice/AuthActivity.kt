package com.example.nursingdevice

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : BaseActivity() {
    private lateinit var manager: NursePatientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        manager = NursePatientManager(this)
        manager.saveNurse(Nurse("Nurse Jane Doe"))  // Dummy nurse

        findViewById<Button>(R.id.registerBtn).setOnClickListener {
            Toast.makeText(this, "Nurse details stored in cache!", Toast.LENGTH_SHORT).show()
            goToMain()
        }

        findViewById<Button>(R.id.loginBtn).setOnClickListener {
            Toast.makeText(this, "Logged in from cache!", Toast.LENGTH_SHORT).show()
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
