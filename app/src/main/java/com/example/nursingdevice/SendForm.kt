package com.example.nursingdevice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SendForm : BaseActivity(), RecognitionListener {

    private lateinit var nurseIdInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var bloodGroupSpinner: Spinner
    private lateinit var genderSpinner: Spinner
    private lateinit var dobInput: EditText
    private lateinit var bpInput: EditText
    private lateinit var heartRateInput: EditText
    private lateinit var respiratoryRateInput: EditText
    private lateinit var temperatureInput: EditText
    private lateinit var generateButton: Button
    private lateinit var startVoiceBtn: Button

    private lateinit var voiceNurseIdBtn: ImageButton
    private lateinit var voiceNameBtn: ImageButton
    private lateinit var voiceBpBtn: ImageButton
    private lateinit var voiceHeartRateBtn: ImageButton
    private lateinit var voiceRespRateBtn: ImageButton
    private lateinit var voiceTempBtn: ImageButton

    private lateinit var formContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var fileContentText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var editButton: Button
    private lateinit var sendNfcButton: Button

    private lateinit var medicationInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var voiceMedicationBtn: ImageButton
    private lateinit var voiceDescriptionBtn: ImageButton

    private var generatedFile: File? = null
    private var fileContent: String = ""

    private var currentFieldIndex: Int = 0
    private val voiceEnabledFields = mutableListOf<EditText>()
    private val RECORD_AUDIO_PERMISSION_CODE = 100

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_form)

        nurseIdInput = findViewById(R.id.nurseIdInput)
        nameInput = findViewById(R.id.nameInput)
        bloodGroupSpinner = findViewById(R.id.bloodGroupSpinner)
        genderSpinner = findViewById(R.id.genderSpinner)
        dobInput = findViewById(R.id.dobInput)
        bpInput = findViewById(R.id.bpInput)
        heartRateInput = findViewById(R.id.heartRateInput)
        respiratoryRateInput = findViewById(R.id.respiratoryRateInput)
        temperatureInput = findViewById(R.id.temperatureInput)
        generateButton = findViewById(R.id.generateButton)
        startVoiceBtn = findViewById(R.id.startVoiceBtn)

        voiceNurseIdBtn = findViewById(R.id.voiceNurseIdBtn)
        voiceNameBtn = findViewById(R.id.voiceNameBtn)
        voiceBpBtn = findViewById(R.id.voiceBpBtn)
        voiceHeartRateBtn = findViewById(R.id.voiceHeartRateBtn)
        voiceRespRateBtn = findViewById(R.id.voiceRespRateBtn)
        voiceTempBtn = findViewById(R.id.voiceTempBtn)

        formContainer = findViewById(R.id.formContainer)
        previewContainer = findViewById(R.id.previewContainer)
        fileContentText = findViewById(R.id.fileContentText)
        fileNameText = findViewById(R.id.fileNameText)
        editButton = findViewById(R.id.editButton)
        sendNfcButton = findViewById(R.id.sendNfcButton)

        medicationInput = findViewById(R.id.medicationInput)
        descriptionInput = findViewById(R.id.descriptionInput)
        voiceMedicationBtn = findViewById(R.id.voiceMedicationBtn)
        voiceDescriptionBtn = findViewById(R.id.voiceDescriptionBtn)

        checkAudioPermission()
        setupSpeechRecognizer()
        setupSpinners()
        setupVoiceInputButtons()

        // WORKFLOW ENFORCEMENT: Lock identity fields and load patient data
        enforceReadOnlyIdentity()

        // Only add editable fields to voice sequence (medication last — continuous mode)
        voiceEnabledFields.clear()
        voiceEnabledFields.add(nurseIdInput)
        voiceEnabledFields.add(bpInput)
        voiceEnabledFields.add(heartRateInput)
        voiceEnabledFields.add(respiratoryRateInput)
        voiceEnabledFields.add(temperatureInput)
        voiceEnabledFields.add(descriptionInput)
        voiceEnabledFields.add(medicationInput)

        startVoiceBtn.setOnClickListener {
            currentFieldIndex = 0
            startVoiceInputAtIndex(0)
        }

        generateButton.setOnClickListener { generateTxtFile() }
        editButton.setOnClickListener { showForm() }
        sendNfcButton.setOnClickListener { sendViaNfc() }
    }

    private fun enforceReadOnlyIdentity() {
        // Load demographics from cache
        if (SessionCache.currentPatientName != "None") {
            nameInput.setText(SessionCache.currentPatientName)
            dobInput.setText("Age: ${SessionCache.currentPatientAge}")

            // Populate the locked spinners with the actual patient data
            val bloodGroups = arrayOf(SessionCache.currentPatientBloodType)
            bloodGroupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)

            val genders = arrayOf(SessionCache.currentPatientGender)
            genderSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genders)
        }

        // Lock fields to prevent creating new patients or modifying core identity
        nameInput.isEnabled = false
        dobInput.isEnabled = false
        bloodGroupSpinner.isEnabled = false
        genderSpinner.isEnabled = false

        // Disable voice button for name
        voiceNameBtn.isEnabled = false
        voiceNameBtn.alpha = 0.3f
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(this)

            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        }
    }

    private fun getFieldName(index: Int): String {
        return when (index) {
            0 -> "Nurse ID"
            1 -> "Blood Pressure"
            2 -> "Heart Rate"
            3 -> "Respiratory Rate"
            4 -> "Temperature"
            5 -> "Description"
            6 -> "Medication"
            else -> "Field"
        }
    }

    private fun isContinuousField(index: Int): Boolean = index == 5 || index == 6

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    private fun setupVoiceInputButtons() {
        voiceNurseIdBtn.setOnClickListener { startVoiceInputAtIndex(0) }
        voiceBpBtn.setOnClickListener { startVoiceInputAtIndex(1) }
        voiceHeartRateBtn.setOnClickListener { startVoiceInputAtIndex(2) }
        voiceRespRateBtn.setOnClickListener { startVoiceInputAtIndex(3) }
        voiceTempBtn.setOnClickListener { startVoiceInputAtIndex(4) }
        voiceDescriptionBtn.setOnClickListener { startVoiceInputAtIndex(5) }
        voiceMedicationBtn.setOnClickListener { startVoiceInputAtIndex(6) }
    }

    private fun startVoiceInputAtIndex(index: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (index >= voiceEnabledFields.size) return

        currentFieldIndex = index
        voiceEnabledFields[index].requestFocus()
        if (isContinuousField(index)) {
            Toast.makeText(this, "Listening for ${getFieldName(index)}... say \"stop\" when done", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Listening for ${getFieldName(index)}...", Toast.LENGTH_SHORT).show()
        }

        try {
            speechRecognizer.startListening(speechIntent)
            isListening = true
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting voice: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { isListening = false }
    override fun onError(error: Int) {
        isListening = false
        if (isContinuousField(currentFieldIndex) && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            // Continuous fields: keep listening through silence
            Handler(Looper.getMainLooper()).postDelayed({
                startVoiceInputAtIndex(currentFieldIndex)
            }, 500)
            return
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH) {
            Toast.makeText(this, "Didn't catch that, try again", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0]

            if (currentFieldIndex < voiceEnabledFields.size) {
                // Medication field: continuous listening until user says "stop"
// Continuous fields (Description and Medication): listen until user says "stop"
                if (isContinuousField(currentFieldIndex)) {
                    if (spokenText.trim().equals("stop", ignoreCase = true)) {
                        Toast.makeText(this, "${getFieldName(currentFieldIndex)} input done", Toast.LENGTH_SHORT).show()
                        isListening = false

                        // Auto-advance to the next field (e.g., from Description to Medication)
                        currentFieldIndex++
                        if (currentFieldIndex < voiceEnabledFields.size) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startVoiceInputAtIndex(currentFieldIndex)
                            }, 1000)
                        } else {
                            Toast.makeText(this, "All fields completed!", Toast.LENGTH_LONG).show()
                            currentFieldIndex = 0
                        }
                        return
                    }

                    val field = voiceEnabledFields[currentFieldIndex]
                    val existing = field.text.toString().trim()
                    if (existing.isEmpty()) {
                        field.setText(spokenText)
                    } else {
                        field.setText("$existing, $spokenText")
                    }
                    field.setSelection(field.text.length)
                    Toast.makeText(this, "Added: $spokenText", Toast.LENGTH_SHORT).show()

                    // Keep listening on the same field
                    Handler(Looper.getMainLooper()).postDelayed({
                        startVoiceInputAtIndex(currentFieldIndex)
                    }, 500)
                    return
                }

                // All other fields: single input, then advance
                val field = voiceEnabledFields[currentFieldIndex]
                val existing = field.text.toString().trim()
                if (existing.isEmpty()) {
                    field.setText(spokenText)
                } else {
                    field.setText("$existing $spokenText")
                }
                field.setSelection(field.text.length)
                Toast.makeText(this, "Logged: $spokenText", Toast.LENGTH_SHORT).show()

                currentFieldIndex++
                if (currentFieldIndex < voiceEnabledFields.size) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        startVoiceInputAtIndex(currentFieldIndex)
                    }, 1000)
                } else {
                    Toast.makeText(this, "All fields completed!", Toast.LENGTH_LONG).show()
                    currentFieldIndex = 0
                }
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun setupSpinners() {
        val bloodGroups = arrayOf("Locked - View Only")
        val bloodGroupAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)
        bloodGroupSpinner.adapter = bloodGroupAdapter

        val genders = arrayOf("Locked - View Only")
        val genderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genders)
        genderSpinner.adapter = genderAdapter
    }

    private fun generateTxtFile() {
        val name = nameInput.text.toString().trim()
        val nurseId = nurseIdInput.text.toString().trim()
        val bp = bpInput.text.toString().trim()
        val heartRate = heartRateInput.text.toString().trim()
        val respiratoryRate = respiratoryRateInput.text.toString().trim()
        val temperature = temperatureInput.text.toString().trim()
        val medication = medicationInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()

        val content = StringBuilder()
        content.append("MEDICAL UPDATE RECORD\n==================\n\n")
        content.append("Nurse ID: $nurseId\n")
        content.append("Patient Name: $name\n")
        if (bp.isNotEmpty()) content.append("Blood Pressure: $bp\n")
        if (heartRate.isNotEmpty()) content.append("Heart Rate: $heartRate bpm\n")
        if (respiratoryRate.isNotEmpty()) content.append("Respiratory Rate: $respiratoryRate breaths/min\n")
        if (temperature.isNotEmpty()) content.append("Body Temperature: ${temperature}F\n")
        if (medication.isNotEmpty()) content.append("Medication: $medication\n")
        if (description.isNotEmpty()) content.append("Description: $description\n")
        content.append("\n==================\n")
        content.append("Updated on: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}\n")

        fileContent = content.toString()
        val fileName = "medical_data_${name.replace(" ", "_")}.txt"

        try {
            generatedFile = File(cacheDir, fileName)
            generatedFile?.writeText(fileContent)

            showPreview(fileName)
            Toast.makeText(this, "Record ready for NFC transfer", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error generating file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPreview(fileName: String) {
        formContainer.visibility = View.GONE
        previewContainer.visibility = View.VISIBLE
        fileContentText.text = fileContent
        fileNameText.text = "File name: $fileName"
    }

    private fun showForm() {
        previewContainer.visibility = View.GONE
        formContainer.visibility = View.VISIBLE
    }

    private fun sendViaNfc() {
        if (generatedFile == null) return
        val intent = Intent(this, SendDocumentActivity::class.java)
        intent.putExtra("FILE_PATH", generatedFile?.absolutePath)
        intent.putExtra("FILE_NAME", generatedFile?.name)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { speechRecognizer.destroy() } catch (e: Exception) {}
    }
}