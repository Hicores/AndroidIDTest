package `fun`.test.id

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var rootRepository: SsaidRootRepository
    private lateinit var historyStore: SsaidHistoryStore
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val operationRunning = AtomicBoolean(false)
    private var activeSuExecutable: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootRepository = SsaidRootRepository()
        historyStore = SsaidHistoryStore(this)

        applyWindowInsets()

        bindActions()
        loadBasicInformation()
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.refreshButton).setOnClickListener {
            loadBasicInformation()
            activeSuExecutable?.let { su -> loadSsaidEntries(su, showLoading = false) }
        }
        findViewById<ImageButton>(R.id.copyCurrentSsaidButton).setOnClickListener {
            copyIdentifier(R.string.ssaid_current_label, R.id.currentSsaidValue)
        }
        findViewById<ImageButton>(R.id.copyDeviceInfoButton).setOnClickListener {
            copyIdentifier(R.string.device_info_label, R.id.deviceInfoValue)
        }
        findViewById<Button>(R.id.manageSsaidButton).setOnClickListener {
            activeSuExecutable?.let { su ->
                loadSsaidEntries(su, showLoading = true)
            } ?: openRootRequestDialog()
        }
        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            openGithub()
        }
    }

    private fun openGithub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.open_github_failed))
        }
    }

    @Suppress("HardwareIds")
    private fun loadBasicInformation() {
        findViewById<TextView>(R.id.currentSsaidValue).text = currentSsaid()
        findViewById<TextView>(R.id.deviceInfoValue).text = deviceInfo()
        if (activeSuExecutable == null) {
            findViewById<TextView>(R.id.ssaidRootStatus).setText(R.string.ssaid_root_hint)
            findViewById<TextView>(R.id.ssaidEmptyState).setText(R.string.ssaid_list_empty)
            findViewById<View>(R.id.ssaidEmptyState).visibility = View.VISIBLE
            findViewById<View>(R.id.ssaidListContainer).visibility = View.GONE
        }
    }

    @Suppress("HardwareIds")
    private fun currentSsaid(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeUnless { it.isBlank() }
            ?: getString(R.string.unavailable)

    private fun deviceInfo(): String = getString(
        R.string.device_info_format,
        android.os.Build.MANUFACTURER,
        android.os.Build.BRAND,
        android.os.Build.MODEL,
        android.os.Build.VERSION.RELEASE,
        android.os.Build.VERSION.SDK_INT,
        android.os.Build.FINGERPRINT
    )

    @Suppress("DEPRECATION")
    private fun applyWindowInsets() {
        val main = findViewById<View>(R.id.main)
        val initialLeft = main.paddingLeft
        val initialTop = main.paddingTop
        val initialRight = main.paddingRight
        val initialBottom = main.paddingBottom
        main.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars())
            } else {
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
        main.requestApplyInsets()
    }

    private fun openRootRequestDialog() {
        val input = EditText(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.su_executable_label)
            val saved = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(SU_EXECUTABLE_KEY, DEFAULT_SU_EXECUTABLE)
                ?: DEFAULT_SU_EXECUTABLE
            setText(saved)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ssaid_root_request_title)
            .setMessage(R.string.ssaid_root_request_message)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.request_root, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val executable = input.text?.toString()?.trim().orEmpty()
                if (executable.isEmpty()) {
                    input.error = getString(R.string.su_executable_required)
                    return@setOnClickListener
                }
                getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(SU_EXECUTABLE_KEY, executable)
                    .apply()
                dialog.dismiss()
                requestRoot(executable)
            }
        }
        dialog.show()
    }

    private fun requestRoot(suExecutable: String) {
        activeSuExecutable = null
        setOperationRunning(true, R.string.root_requesting)
        findViewById<TextView>(R.id.ssaidRootStatus).setText(R.string.root_requesting)
        backgroundExecutor.execute {
            try {
                val entries = rootRepository.readEntries(suExecutable)
                runOnUiThread {
                    activeSuExecutable = suExecutable
                    setOperationRunning(false)
                    renderSsaidEntries(entries)
                    showMessage(getString(R.string.root_granted, entries.size))
                }
            } catch (error: RootOperationException) {
                runOnUiThread {
                    setOperationRunning(false)
                    findViewById<TextView>(R.id.ssaidRootStatus).text = error.message
                        ?: getString(R.string.root_failed)
                    showMessage(getString(R.string.root_failed))
                }
            }
        }
    }

    private fun loadSsaidEntries(suExecutable: String, showLoading: Boolean) {
        if (showLoading) {
            setOperationRunning(true, R.string.ssaid_loading)
            findViewById<TextView>(R.id.ssaidRootStatus).setText(R.string.ssaid_loading)
        }
        backgroundExecutor.execute {
            try {
                val entries = rootRepository.readEntries(suExecutable)
                runOnUiThread {
                    activeSuExecutable = suExecutable
                    setOperationRunning(false)
                    renderSsaidEntries(entries)
                }
            } catch (error: RootOperationException) {
                runOnUiThread {
                    setOperationRunning(false)
                    findViewById<TextView>(R.id.ssaidRootStatus).text = error.message
                        ?: getString(R.string.root_failed)
                    showMessage(getString(R.string.root_failed))
                }
            }
        }
    }

    private fun renderSsaidEntries(entries: List<SsaidEntry>) {
        val container = findViewById<LinearLayout>(R.id.ssaidListContainer)
        val emptyState = findViewById<TextView>(R.id.ssaidEmptyState)
        container.removeAllViews()

        val sortedEntries = entries.sortedWith(
            compareBy<SsaidEntry> { applicationLabel(it.packageName).lowercase(Locale.getDefault()) }
                .thenBy { it.packageName }
        )
        sortedEntries.forEach { entry ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_ssaid, container, false)
            row.findViewById<TextView>(R.id.ssaidAppLabel).text = applicationLabel(entry.packageName)
            row.findViewById<TextView>(R.id.ssaidPackageName).text = entry.packageName
            row.findViewById<TextView>(R.id.ssaidEntryValue).text = entry.value
            row.findViewById<Button>(R.id.editSsaidButton).setOnClickListener {
                openEditDialog(entry)
            }
            row.findViewById<Button>(R.id.randomSsaidButton).setOnClickListener {
                openRandomDialog(entry)
            }
            row.findViewById<Button>(R.id.historySsaidButton).setOnClickListener {
                showHistory(entry)
            }
            container.addView(row)
        }

        val hasEntries = sortedEntries.isNotEmpty()
        emptyState.visibility = if (hasEntries) View.GONE else View.VISIBLE
        emptyState.setText(if (hasEntries) R.string.ssaid_list_empty else R.string.ssaid_no_entries)
        container.visibility = if (hasEntries) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.ssaidRootStatus).text = getString(
            R.string.root_granted,
            sortedEntries.size
        )
        findViewById<Button>(R.id.manageSsaidButton).setText(R.string.refresh_ssaid)
    }

    private fun applicationLabel(packageName: String): String = try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        packageManager.getApplicationLabel(applicationInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun openEditDialog(entry: SsaidEntry) {
        val input = EditText(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.new_ssaid_label)
            setText(entry.value)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_ssaid_title, applicationLabel(entry.packageName)))
            .setMessage(entry.packageName)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (!isValidSsaid(value)) {
                    input.error = getString(R.string.invalid_ssaid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitSsaidChange(entry, value)
            }
        }
        dialog.show()
    }

    private fun openRandomDialog(entry: SsaidEntry) {
        val newValue = randomSsaid()
        AlertDialog.Builder(this)
            .setTitle(R.string.random_ssaid_title)
            .setMessage(getString(R.string.random_ssaid_message, applicationLabel(entry.packageName), newValue))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply_change) { _, _ -> submitSsaidChange(entry, newValue) }
            .show()
    }

    private fun submitSsaidChange(entry: SsaidEntry, newValue: String) {
        val suExecutable = activeSuExecutable
        if (suExecutable == null) {
            openRootRequestDialog()
            return
        }
        if (!isValidSsaid(newValue)) {
            showMessage(getString(R.string.invalid_ssaid))
            return
        }
        if (entry.value.equals(newValue, ignoreCase = true)) {
            showMessage(getString(R.string.ssaid_unchanged))
            return
        }

        setOperationRunning(true, R.string.ssaid_saving)
        findViewById<TextView>(R.id.ssaidRootStatus).setText(R.string.ssaid_saving)
        backgroundExecutor.execute {
            try {
                val result = rootRepository.updateEntry(suExecutable, entry.packageName, newValue)
                historyStore.append(
                    entry.packageName,
                    SsaidHistoryRecord(result.previousValue, newValue, System.currentTimeMillis())
                )
                runOnUiThread {
                    setOperationRunning(false)
                    renderSsaidEntries(result.entries)
                    findViewById<TextView>(R.id.currentSsaidValue).text = currentSsaid()
                    showMessage(
                        getString(
                            R.string.ssaid_changed,
                            applicationLabel(entry.packageName),
                            newValue
                        )
                    )
                    showSsaidRestartNotice()
                }
            } catch (error: RootOperationException) {
                runOnUiThread {
                    setOperationRunning(false)
                    findViewById<TextView>(R.id.ssaidRootStatus).text = error.message
                        ?: getString(R.string.ssaid_change_failed)
                    showMessage(getString(R.string.ssaid_change_failed))
                }
            }
        }
    }

    private fun showHistory(entry: SsaidEntry) {
        val records = historyStore.records(entry.packageName)
        if (records.isEmpty()) {
            showMessage(getString(R.string.ssaid_history_empty))
            return
        }

        val historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val scrollView = ScrollView(this).apply {
            addView(historyContainer)
        }

        lateinit var historyDialog: AlertDialog
        records.asReversed().forEach { record ->
            val item = LayoutInflater.from(this)
                .inflate(R.layout.item_ssaid_history, historyContainer, false)
            item.findViewById<TextView>(R.id.historyTime).text = formatTime(record.timestamp)
            item.findViewById<TextView>(R.id.historyValues).text = getString(
                R.string.history_values,
                record.oldValue,
                record.newValue
            )
            item.findViewById<Button>(R.id.restoreHistoryButton).setOnClickListener {
                historyDialog.dismiss()
                confirmRestore(entry, record)
            }
            historyContainer.addView(item)
        }

        historyDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_title, applicationLabel(entry.packageName)))
            .setView(scrollView)
            .setPositiveButton(R.string.close, null)
            .create()
        historyDialog.show()
    }

    private fun confirmRestore(entry: SsaidEntry, record: SsaidHistoryRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_ssaid_title)
            .setMessage(getString(R.string.restore_ssaid_message, record.oldValue))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ -> submitSsaidChange(entry, record.oldValue) }
            .show()
    }

    private fun setOperationRunning(running: Boolean, messageRes: Int = R.string.refresh_ssaid) {
        if (!operationRunning.compareAndSet(!running, running)) return
        findViewById<Button>(R.id.manageSsaidButton).apply {
            isEnabled = !running
            if (running) setText(messageRes) else setText(R.string.refresh_ssaid)
        }
        findViewById<ImageButton>(R.id.refreshButton).isEnabled = !running
    }

    private fun copyIdentifier(labelRes: Int, valueViewId: Int) {
        val value = findViewById<TextView>(valueViewId).text.toString()
        if (value == getString(R.string.unavailable)) {
            showMessage(getString(R.string.nothing_to_copy))
            return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(labelRes), value)
        )
        showMessage(getString(R.string.copied, getString(labelRes)))
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSsaidRestartNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssaid_change_notice_title)
            .setMessage(R.string.ssaid_change_notice_message)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun isValidSsaid(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{16}"))

    private fun randomSsaid(): String =
        UUID.randomUUID().toString().replace("-", "").take(16).lowercase(Locale.US)

    private companion object {
        const val PREFERENCES_NAME = "device_identifiers"
        const val SU_EXECUTABLE_KEY = "su_executable"
        const val DEFAULT_SU_EXECUTABLE = "su"
    }
}
