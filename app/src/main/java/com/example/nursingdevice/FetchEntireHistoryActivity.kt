package com.example.nursingdevice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class FetchEntireHistoryActivity : Activity() {

    private lateinit var currentPathText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var adapter: HistoryBrowserAdapter

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = CloudHistoryRepository()
    private val manager by lazy { NursePatientManager(this) }

    private var selectedDate: String? = null
    private var currentPatientId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fetch_entire_history)

        currentPathText = findViewById(R.id.historyCurrentPathText)
        statusText = findViewById(R.id.historyStatusText)
        progressBar = findViewById(R.id.historyProgressBar)
        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyStateText = findViewById(R.id.historyEmptyStateText)
        backButton = findViewById(R.id.historyBackButton)

        adapter = HistoryBrowserAdapter(
            items = emptyList(),
            onFolderClick = { folder -> openDateFolder(folder.date) },
            onRecordClick = { record ->
                startActivity(
                    Intent(this, ViewCloudHistoryRecordActivity::class.java).apply {
                        putExtra(ViewCloudHistoryRecordActivity.EXTRA_TITLE, record.title)
                        putExtra(ViewCloudHistoryRecordActivity.EXTRA_CONTENT, record.content)
                        putExtra(ViewCloudHistoryRecordActivity.EXTRA_UPDATED_AT, record.updatedAt)
                    }
                )
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        backButton.setOnClickListener { navigateBack() }

        if (intent.getBooleanExtra(EXTRA_CACHED_ONLY, false)) {
            showCachedHistory()
        } else {
            startFetch()
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentPatientId != null) {
            renderCurrentLevel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun showCachedHistory() {
        val patient = manager.getPatient()
        if (patient == null || patient.patientId.isBlank() || patient.patientId == "N/A") {
            statusText.text = "No patient scanned"
            currentPathText.text = "/"
            showEmptyState("Scan the Aggregator mCard first so we have a patient ID.")
            progressBar.visibility = View.GONE
            Toast.makeText(this, "Scan a patient card first", Toast.LENGTH_SHORT).show()
            return
        }

        currentPatientId = patient.patientId
        progressBar.visibility = View.GONE
        statusText.text = "Showing mCard history"
        renderCurrentLevel()
    }

    private fun startFetch() {
        val patient = manager.getPatient()
        if (patient == null || patient.patientId.isBlank() || patient.patientId == "N/A") {
            statusText.text = "No patient scanned"
            currentPathText.text = "/"
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = "Scan the Aggregator mCard first so we have a patient ID."
            recyclerView.visibility = View.GONE
            progressBar.visibility = View.GONE
            Toast.makeText(this, "Scan a patient card first", Toast.LENGTH_SHORT).show()
            return
        }

        currentPatientId = patient.patientId
        progressBar.visibility = View.VISIBLE
        statusText.text = "Fetching entire history..."
        emptyStateText.visibility = View.GONE
        recyclerView.visibility = View.GONE

        activityScope.launch {
            repo.fetchEntireHistory(this@FetchEntireHistoryActivity, patient.patientId).fold(
                onSuccess = {
                    progressBar.visibility = View.GONE
                    statusText.text = "History fetched"
                    renderCurrentLevel()
                    Toast.makeText(
                        this@FetchEntireHistoryActivity,
                        "Fetched entire history for ${patient.patientId}",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    progressBar.visibility = View.GONE
                    if (manager.getCloudHistoryDates(patient.patientId).isNotEmpty()) {
                        statusText.text = "Showing cached history"
                        renderCurrentLevel()
                    } else {
                        statusText.text = "Fetch failed"
                        emptyStateText.visibility = View.VISIBLE
                        emptyStateText.text = error.message ?: "Failed to fetch history"
                        recyclerView.visibility = View.GONE
                    }
                    Toast.makeText(
                        this@FetchEntireHistoryActivity,
                        error.message ?: "Failed to fetch history",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun renderCurrentLevel() {
        val patientId = currentPatientId ?: return
        recyclerView.visibility = View.VISIBLE

        if (selectedDate == null) {
            currentPathText.text = "/"
            backButton.visibility = View.GONE
            val dates = manager.getCloudHistoryDates(patientId)
            if (dates.isEmpty()) {
                showEmptyState("No cloud history fetched yet.")
                return
            }

            val items = dates.map { date ->
                HistoryBrowserItem.DateFolder(
                    date = date,
                    count = manager.getCloudHistoryBlocks(patientId, date).size
                )
            }
            adapter.updateItems(items)
            emptyStateText.visibility = View.GONE
        } else {
            currentPathText.text = "/$selectedDate"
            backButton.visibility = View.VISIBLE
            val blocks = manager.getCloudHistoryBlocks(patientId, selectedDate!!)
            if (blocks.isEmpty()) {
                showEmptyState("No records found for $selectedDate.")
                return
            }

            adapter.updateItems(
                blocks.map { block ->
                    HistoryBrowserItem.RecordFile(
                        title = block.title,
                        content = block.content,
                        updatedAt = block.updatedAt
                    )
                }
            )
            emptyStateText.visibility = View.GONE
        }
    }

    private fun openDateFolder(date: String) {
        selectedDate = date
        renderCurrentLevel()
    }

    private fun navigateBack() {
        if (selectedDate == null) {
            finish()
            return
        }

        selectedDate = null
        renderCurrentLevel()
    }

    private fun showEmptyState(message: String) {
        adapter.updateItems(emptyList())
        recyclerView.visibility = View.GONE
        emptyStateText.visibility = View.VISIBLE
        emptyStateText.text = message
    }

    companion object {
        const val EXTRA_CACHED_ONLY = "cached_only"
    }
}
