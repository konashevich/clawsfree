package io.openclaw.telegramhandsfree

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import io.openclaw.telegramhandsfree.config.ClawsfreeConfig
import io.openclaw.telegramhandsfree.voice.ClawsfreeForegroundService
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private data class AssistantState(
        val roleHeld: Boolean,
        val assistantSetting: String?,
        val voiceInteractionService: String?
    ) {
        val isConfigured: Boolean
            get() = roleHeld || assistantSetting != null || voiceInteractionService != null
    }

    private lateinit var inputApiId: TextInputEditText
    private lateinit var inputApiHash: TextInputEditText
    private lateinit var inputPhone: TextInputEditText
    private lateinit var inputGroupId: TextInputEditText
    private lateinit var inputTopicId: TextInputEditText
    private lateinit var connectButton: Button
    private lateinit var connectChatButton: Button
    private lateinit var switchBluetoothMic: SwitchMaterial
    private lateinit var mainRoot: View
    private lateinit var topBar: View
    private lateinit var setupContainer: View
    private lateinit var dashboardContainer: View
    private lateinit var topBarTitle: TextView
    private lateinit var topBarSubtitle: TextView
    private lateinit var topActionButton: ImageButton

    private lateinit var authGroupHeader: LinearLayout
    private lateinit var authGroupContent: LinearLayout
    private lateinit var authGroupChevron: TextView
    private lateinit var chatGroupHeader: LinearLayout
    private lateinit var chatGroupContent: LinearLayout
    private lateinit var chatGroupChevron: TextView
    private lateinit var otherSettingsHeader: LinearLayout
    private lateinit var otherSettingsContent: LinearLayout
    private lateinit var otherSettingsChevron: TextView
    private lateinit var otherSettingsCard: View

    private lateinit var authSection: LinearLayout
    private lateinit var authHintText: TextView
    private lateinit var inputAuthCode: TextInputEditText
    private lateinit var input2faPassword: TextInputEditText
    private lateinit var submitCodeButton: Button

    private lateinit var statusText: TextView
    private lateinit var activityText: TextView
    private lateinit var activityHintText: TextView
    private lateinit var btnSetAssistant: Button
    private lateinit var assistantHintText: TextView
    private lateinit var btnFinishSetup: Button
    private lateinit var btnRecordToggle: MaterialCardView
    private lateinit var recordButtonTitle: TextView
    private lateinit var recordButtonHint: TextView
    private lateinit var connectionPill: TextView
    private lateinit var assistantPill: TextView

    private var isAuthConnected: Boolean = false
    private var isChatConnected: Boolean = false
    private var authPhase: String = "idle"
    private var currentActivityState: String = "idle"
    private var isSettingsMenuVisible: Boolean = false

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                statusText.text = getString(R.string.status_connecting)
                beginAuthHandshake()
            } else {
                statusText.text = getString(R.string.status_error, "Permissions denied")
            }
        }

    private val requestAssistantRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val assistantState = readAssistantState()
            logAssistantState("Role request returned resultCode=${result.resultCode}", assistantState)
            updateAssistantButton(assistantState)

            if (assistantState.isConfigured) {
                Toast.makeText(this, "Clawsfree is now the default assistant", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            if (result.resultCode == Activity.RESULT_OK) {
                openManualAssistantSettings(
                    "Clawsfree is still not active as the assistant. Open assistant settings and select Clawsfree."
                )
                return@registerForActivityResult
            }

            openManualAssistantSettings(manualAssistantSetupMessage(includeFailurePrefix = true))
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ClawsfreeForegroundService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(ClawsfreeForegroundService.EXTRA_STATUS) ?: return
                    runOnUiThread { handleServiceStatus(status) }
                }
                ClawsfreeForegroundService.ACTION_ACTIVITY_UPDATE -> {
                    val activity = intent.getStringExtra(ClawsfreeForegroundService.EXTRA_ACTIVITY) ?: return
                    runOnUiThread { handleActivityState(activity) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainRoot = findViewById(R.id.main_root)
        topBar = findViewById(R.id.top_bar)
        setupContainer = findViewById(R.id.setup_container)
        dashboardContainer = findViewById(R.id.dashboard_container)
        topBarTitle = findViewById(R.id.top_bar_title)
        topBarSubtitle = findViewById(R.id.top_bar_subtitle)
        topActionButton = findViewById(R.id.top_action_button)
        inputApiId = findViewById(R.id.input_api_id)
        inputApiHash = findViewById(R.id.input_api_hash)
        inputPhone = findViewById(R.id.input_phone)
        inputGroupId = findViewById(R.id.input_group_id)
        inputTopicId = findViewById(R.id.input_topic_id)
        connectButton = findViewById(R.id.connect_button)
        connectChatButton = findViewById(R.id.connect_chat_button)
        switchBluetoothMic = findViewById(R.id.switch_bluetooth_mic)

        authGroupHeader = findViewById(R.id.auth_group_header)
        authGroupContent = findViewById(R.id.auth_group_content)
        authGroupChevron = findViewById(R.id.auth_group_chevron)
        chatGroupHeader = findViewById(R.id.chat_group_header)
        chatGroupContent = findViewById(R.id.chat_group_content)
        chatGroupChevron = findViewById(R.id.chat_group_chevron)
        otherSettingsHeader = findViewById(R.id.other_settings_header)
        otherSettingsContent = findViewById(R.id.other_settings_content)
        otherSettingsChevron = findViewById(R.id.other_settings_chevron)
        otherSettingsCard = findViewById(R.id.other_settings_card)

        authSection = findViewById(R.id.auth_section)
        authHintText = findViewById(R.id.auth_hint_text)
        inputAuthCode = findViewById(R.id.input_auth_code)
        input2faPassword = findViewById(R.id.input_2fa_password)
        submitCodeButton = findViewById(R.id.submit_code_button)

        statusText = findViewById(R.id.status_text)
        activityText = findViewById(R.id.activity_text)
        activityHintText = findViewById(R.id.activity_hint_text)
        btnSetAssistant = findViewById(R.id.btn_set_assistant)
        assistantHintText = findViewById(R.id.assistant_hint_text)
        btnFinishSetup = findViewById(R.id.btn_finish_setup)
        btnRecordToggle = findViewById(R.id.btn_record_toggle)
        recordButtonTitle = findViewById(R.id.record_button_title)
        recordButtonHint = findViewById(R.id.record_button_hint)
        connectionPill = findViewById(R.id.connection_pill)
        assistantPill = findViewById(R.id.assistant_pill)
        applyWindowInsets()
        setupRecordButtonSurface()

        loadSettingsIntoFields()
        isChatConnected = isChatConnectionConfirmed()
        migrateLegacySetupCompletionIfNeeded()
        statusText.text = if (isChatConnected) {
            getString(R.string.status_chat_connected)
        } else {
            getString(R.string.status_setup_needed)
        }
        setAuthGroupCollapsed(collapsed = false)
        setChatGroupCollapsed(collapsed = true)
        setOtherSettingsVisible(isChatConnected)
        setOtherSettingsCollapsed(collapsed = true)
        isSettingsMenuVisible = !isSetupCompleted()
        updateUiMode()

        switchBluetoothMic.isChecked = ClawsfreeConfig.USE_BLUETOOTH_MIC
        switchBluetoothMic.setOnCheckedChangeListener { _, isChecked ->
            ClawsfreeConfig.setBluetoothMic(isChecked)
        }

        authGroupHeader.setOnClickListener {
            setAuthGroupCollapsed(collapsed = authGroupContent.visibility == View.VISIBLE)
        }
        chatGroupHeader.setOnClickListener {
            setChatGroupCollapsed(collapsed = chatGroupContent.visibility == View.VISIBLE)
        }
        otherSettingsHeader.setOnClickListener {
            setOtherSettingsCollapsed(collapsed = otherSettingsContent.visibility == View.VISIBLE)
        }

        connectButton.setOnClickListener { onAuthConnectTapped() }
        connectChatButton.setOnClickListener { onChatConnectTapped() }
        submitCodeButton.setOnClickListener { onSubmitCodeTapped() }
        btnSetAssistant.setOnClickListener { openAssistantSettings() }
        btnFinishSetup.setOnClickListener { onFinishSetupTapped() }
        topActionButton.setOnClickListener {
            isSettingsMenuVisible = !isSettingsMenuVisible
            updateUiMode()
        }
        updateAssistantButton()
        handleActivityState("idle")
    }

    override fun onResume() {
        super.onResume()
        val statusFilter = IntentFilter().apply {
            addAction(ClawsfreeForegroundService.ACTION_STATUS_UPDATE)
            addAction(ClawsfreeForegroundService.ACTION_ACTIVITY_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }
        // Read last persisted status in case broadcasts were missed (e.g. during permission dialog)
        val prefs = getSharedPreferences(ClawsfreeForegroundService.PREFS_NAME, MODE_PRIVATE)
        prefs.getString(ClawsfreeForegroundService.KEY_LAST_STATUS, null)?.let {
            handleServiceStatus(it)
        }
        prefs.getString(ClawsfreeForegroundService.KEY_LAST_ACTIVITY, null)?.let {
            handleActivityState(it)
        }
        // Refresh assistant button state (user may have just returned from settings)
        updateAssistantButton()
        updateUiMode()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun applyWindowInsets() {
        val topBarTopPadding = topBar.paddingTop
        val setupBottomPadding = setupContainer.paddingBottom
        val dashboardBottomPadding = dashboardContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, windowInsets ->
            val topInset = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            topBar.setPadding(
                topBar.paddingLeft,
                topBarTopPadding + topInset,
                topBar.paddingRight,
                topBar.paddingBottom
            )
            setupContainer.setPadding(
                setupContainer.paddingLeft,
                setupContainer.paddingTop,
                setupContainer.paddingRight,
                setupBottomPadding + bottomInset
            )
            dashboardContainer.setPadding(
                dashboardContainer.paddingLeft,
                dashboardContainer.paddingTop,
                dashboardContainer.paddingRight,
                dashboardBottomPadding + bottomInset
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(mainRoot)
    }

    private fun migrateLegacySetupCompletionIfNeeded() {
        val prefs = onboardingPrefs()
        if (!prefs.contains(KEY_SETUP_COMPLETED) && isChatConnected) {
            setSetupCompleted(true)
        }
    }

    private fun setupRecordButtonSurface() {
        btnRecordToggle.doOnPreDraw { updateRecordButtonSize(it.width) }
        btnRecordToggle.doOnLayout { updateRecordButtonSize(it.width) }
        btnRecordToggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }
            }
            false
        }
        btnRecordToggle.setOnClickListener { toggleRecording() }
    }

    private fun updateRecordButtonSize(availableWidth: Int) {
        if (availableWidth <= 0) return

        val targetSize = min(
            availableWidth,
            resources.getDimensionPixelSize(R.dimen.dashboard_record_button_size)
        )
        val params = btnRecordToggle.layoutParams
        if (params.width != targetSize || params.height != targetSize) {
            params.width = targetSize
            params.height = targetSize
            btnRecordToggle.layoutParams = params
        }
        btnRecordToggle.radius = targetSize / 2f
    }

    private fun toggleRecording() {
        val intent = Intent(this, ClawsfreeForegroundService::class.java).apply {
            action = ClawsfreeForegroundService.ACTION_TOGGLE_RECORDING
        }
        startService(intent)
    }

    private fun updateUiMode() {
        val showSetup = isSettingsMenuVisible || !isSetupCompleted()
        setupContainer.visibility = if (showSetup) View.VISIBLE else View.GONE
        dashboardContainer.visibility = if (showSetup) View.GONE else View.VISIBLE

        topBarTitle.text = if (showSetup && !isSetupCompleted()) {
            getString(R.string.top_title_setup)
        } else {
            getString(R.string.app_name)
        }
        topBarSubtitle.text = if (showSetup) {
            getString(R.string.top_subtitle_setup)
        } else {
            getString(R.string.top_subtitle_ready)
        }

        if (isSetupCompleted()) {
            topActionButton.visibility = View.VISIBLE
            topActionButton.setImageResource(
                if (showSetup) R.drawable.ic_arrow_back_24 else R.drawable.ic_settings_24
            )
            topActionButton.contentDescription = getString(
                if (showSetup) R.string.cd_close_settings else R.string.cd_open_settings
            )
        } else {
            topActionButton.visibility = View.GONE
        }

        btnFinishSetup.text = getString(
            if (isSetupCompleted()) R.string.btn_back_dashboard else R.string.btn_open_dashboard
        )
        val canLeaveSetup = isSetupCompleted() || isChatConnected
        btnFinishSetup.isEnabled = canLeaveSetup
        btnFinishSetup.alpha = if (canLeaveSetup) 1f else 0.5f

        updateRecordButtonVisibility()
        updateDashboardPills()
    }

    private fun updateDashboardPills() {
        styleStatusPill(
            pill = connectionPill,
            label = if (isChatConnected) {
                getString(R.string.dashboard_connection_ready)
            } else {
                getString(R.string.dashboard_connection_pending)
            },
            backgroundColorRes = if (isChatConnected) {
                R.color.clawsfree_success_bg
            } else {
                R.color.clawsfree_warning_bg
            },
            textColorRes = if (isChatConnected) {
                R.color.clawsfree_success_text
            } else {
                R.color.clawsfree_warning_text
            }
        )

        val assistantReady = readAssistantState().isConfigured
        styleStatusPill(
            pill = assistantPill,
            label = if (assistantReady) {
                getString(R.string.dashboard_assistant_ready)
            } else {
                getString(R.string.dashboard_assistant_optional)
            },
            backgroundColorRes = if (assistantReady) {
                R.color.clawsfree_info_bg
            } else {
                R.color.clawsfree_warning_bg
            },
            textColorRes = if (assistantReady) {
                R.color.clawsfree_info_text
            } else {
                R.color.clawsfree_warning_text
            }
        )
    }

    private fun styleStatusPill(
        pill: TextView,
        label: String,
        backgroundColorRes: Int,
        textColorRes: Int
    ) {
        pill.text = label
        pill.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, backgroundColorRes)
        )
        pill.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun updateRecordButtonAppearance(state: String) {
        val backgroundColor = when {
            !isChatConnected -> R.color.clawsfree_warning_text
            state == "recording" -> R.color.clawsfree_record_recording
            state == "sending" -> R.color.clawsfree_record_sending
            else -> R.color.clawsfree_record_idle
        }
        btnRecordToggle.setCardBackgroundColor(ContextCompat.getColor(this, backgroundColor))
        btnRecordToggle.alpha = if (isChatConnected) 1f else 0.72f
        btnRecordToggle.isEnabled = isChatConnected
    }

    private fun showTransientMessage(message: String) {
        Snackbar.make(mainRoot, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun loadSettingsIntoFields() {
        val apiId = ClawsfreeConfig.TELEGRAM_API_ID
        if (apiId > 0) inputApiId.setText(apiId.toString())
        inputApiHash.setText(ClawsfreeConfig.TELEGRAM_API_HASH)
        inputPhone.setText(ClawsfreeConfig.TELEGRAM_PHONE_NUMBER)
        val groupId = ClawsfreeConfig.TELEGRAM_GROUP_ID
        if (groupId != 0L) inputGroupId.setText(groupId.toString())
        val topicId = ClawsfreeConfig.TELEGRAM_TOPIC_ID
        if (topicId != 0L) inputTopicId.setText(topicId.toString())
        inputAuthCode.setText(ClawsfreeConfig.TELEGRAM_AUTH_CODE)
        input2faPassword.setText(ClawsfreeConfig.TELEGRAM_2FA_PASSWORD)
    }

    private fun handleServiceStatus(status: String) {
        val previousStatusText = statusText.text?.toString()
        when {
            status == "connected" -> {
                authPhase = "connected"
                isAuthConnected = true
                isChatConnected = isChatConnectionConfirmed()
                statusText.text = getString(R.string.status_connected)
                authSection.visibility = View.GONE
                setAuthGroupCollapsed(collapsed = true)
                setChatGroupCollapsed(collapsed = isChatConnected)
                setOtherSettingsVisible(isChatConnected)
                updateRecordButtonVisibility()
                updateAssistantButton()
                updateUiMode()
                // Show idle activity when first connected
                if (activityText.text.isNullOrBlank()) {
                    handleActivityState("idle")
                }
            }
            status == "connecting" -> {
                authPhase = "connecting"
                statusText.text = getString(R.string.status_connecting)
            }
            status == "waiting_code" -> {
                authPhase = "waiting_code"
                isAuthConnected = false
                statusText.text = getString(R.string.status_waiting_code)
                showAuthSection(getString(R.string.step2_subtitle))
            }
            status == "waiting_password" -> {
                authPhase = "waiting_password"
                isAuthConnected = false
                statusText.text = getString(R.string.status_waiting_password)
                showAuthSection(getString(R.string.step2_subtitle_2fa))
            }
            status.startsWith("error:") -> {
                authPhase = "error"
                val reason = status.removePrefix("error:")
                statusText.text = getString(R.string.status_error, reason)
                if (previousStatusText != statusText.text.toString()) {
                    showTransientMessage(getString(R.string.status_error, reason))
                }
            }
            status.startsWith("needs_config:") -> {
                authPhase = "needs_config"
                statusText.text = getString(R.string.status_error, status.removePrefix("needs_config:"))
            }
        }
        updateDashboardPills()
    }

    private fun handleActivityState(state: String) {
        val previousState = currentActivityState
        currentActivityState = state
        val assistantState = readAssistantState()

        activityText.text = when (state) {
            "recording" -> {
                recordButtonTitle.text = getString(R.string.record_button_title_recording)
                recordButtonHint.text = getString(R.string.record_button_hint_recording)
                activityHintText.text = getString(R.string.activity_recording_hint)
                getString(R.string.activity_recording)
            }
            "sending" -> {
                recordButtonTitle.text = getString(R.string.record_button_title_sending)
                recordButtonHint.text = getString(R.string.record_button_hint_sending)
                activityHintText.text = getString(R.string.activity_sending_hint)
                getString(R.string.activity_sending)
            }
            "sent" -> {
                recordButtonTitle.text = getString(R.string.record_button_title_idle)
                recordButtonHint.text = getString(R.string.record_button_hint_idle)
                activityHintText.text = getString(R.string.activity_sent_hint)
                getString(R.string.activity_sent)
            }
            else -> {
                recordButtonTitle.text = if (isChatConnected) {
                    getString(R.string.record_button_title_idle)
                } else {
                    getString(R.string.record_button_title_disabled)
                }
                recordButtonHint.text = if (isChatConnected) {
                    getString(R.string.record_button_hint_idle)
                } else {
                    getString(R.string.record_button_hint_disabled)
                }
                activityHintText.text = idleActivityHint(assistantState)
                getString(R.string.activity_idle)
            }
        }
        updateRecordButtonAppearance(state)

        if (state != previousState) {
            when (state) {
                "recording" -> btnRecordToggle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                "sent" -> {
                    btnRecordToggle.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    showTransientMessage(getString(R.string.activity_sent))
                }
            }
        }
    }

    private fun openAssistantSettings() {
        val assistantState = readAssistantState()
        logAssistantState("Set assistant tapped", assistantState)
        updateAssistantButton(assistantState)

        if (assistantState.isConfigured) {
            openManualAssistantSettings(
                "Clawsfree is already selected as the assistant. You can review or change it here."
            )
            return
        }

        try {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                requestAssistantRoleLauncher.launch(intent)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Assistant role request failed", e)
        }

        openManualAssistantSettings(manualAssistantSetupMessage(includeFailurePrefix = false))
    }

    private fun updateAssistantButton(assistantState: AssistantState = readAssistantState()) {
        btnSetAssistant.visibility = View.VISIBLE
        if (assistantState.isConfigured) {
            btnSetAssistant.text = getString(R.string.btn_assistant_active)
            btnSetAssistant.isEnabled = true
            assistantHintText.text = getString(R.string.assistant_hint_active)
        } else {
            btnSetAssistant.text = getString(R.string.btn_set_assistant)
            btnSetAssistant.isEnabled = true
            assistantHintText.text = getString(R.string.assistant_hint_inactive)
        }
        updateDashboardPills()
        handleActivityState(currentActivityState)
    }

    private fun isDefaultAssistant(): Boolean {
        return readAssistantState().isConfigured
    }

    private fun readAssistantState(): AssistantState {
        val roleHeld = runCatching {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)

        val assistantSetting = readSecureComponentSetting(SETTING_ASSISTANT)
        val voiceInteractionService = readSecureComponentSetting(SETTING_VOICE_INTERACTION_SERVICE)

        return AssistantState(
            roleHeld = roleHeld,
            assistantSetting = assistantSetting,
            voiceInteractionService = voiceInteractionService
        )
    }

    private fun readSecureComponentSetting(settingName: String): String? {
        val rawValue = runCatching {
            Settings.Secure.getString(contentResolver, settingName)
        }.getOrNull()

        if (rawValue.isNullOrBlank()) return null

        val component = ComponentName.unflattenFromString(rawValue)
        return when {
            component != null && component.packageName == packageName -> rawValue
            component == null && rawValue.startsWith("$packageName/") -> rawValue
            else -> null
        }
    }

    private fun openManualAssistantSettings(message: String) {
        logAssistantState("Opening manual assistant settings")

        val opened = tryStartActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
            tryStartActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) ||
            tryStartActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )

        val finalMessage = if (opened) {
            message
        } else {
            "$message Could not open settings automatically."
        }
        Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show()
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return runCatching {
            startActivity(intent)
            true
        }.getOrElse { false }
    }

    private fun manualAssistantSetupMessage(includeFailurePrefix: Boolean): String {
        val prefix = if (includeFailurePrefix) {
            "Assistant role was not granted. "
        } else {
            ""
        }

        val instructions = if (isXiaomiFamilyDevice()) {
            "On Xiaomi/Redmi/Poco, open Settings > Apps > Default apps > Assist & voice input and select Clawsfree."
        } else {
            "Open Settings > Apps > Default apps > Digital assistant app and select Clawsfree."
        }

        return prefix + instructions
    }

    private fun isXiaomiFamilyDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }

    private fun logAssistantState(prefix: String, assistantState: AssistantState = readAssistantState()) {
        Log.i(
            TAG,
            "$prefix roleHeld=${assistantState.roleHeld} assistant=${assistantState.assistantSetting} voiceInteraction=${assistantState.voiceInteractionService}"
        )
    }

    /**
     * Connect / Save: saves settings and starts (or keeps) the service.
     * If already authenticated, TDLib will go straight to Ready —
     * no SMS code is needed. Auth section only appears when TDLib asks for it.
     * NEVER stops the service —avoids TDLib DB lock.
     */
    private fun onAuthConnectTapped() {
        saveAuthSettingsForNewCycle()
        authPhase = "connecting"
        isAuthConnected = false
        isChatConnected = false
        setChatConnectionConfirmed(false)
        setAuthGroupCollapsed(collapsed = false)
        setChatGroupCollapsed(collapsed = true)
        setOtherSettingsVisible(false)
        authSection.visibility = View.GONE
        isSettingsMenuVisible = true
        updateRecordButtonVisibility()
        updateUiMode()

        // Clear stale persisted status so old errors don't flash on resume
        getSharedPreferences(ClawsfreeForegroundService.PREFS_NAME, MODE_PRIVATE)
            .edit().remove(ClawsfreeForegroundService.KEY_LAST_STATUS).apply()

        val missing = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Permissions dialog will appear; service starts in the callback after grant
            statusText.text = "Requesting permissions…"
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            // All permissions already granted — start service immediately
            statusText.text = getString(R.string.status_connecting)
            beginAuthHandshake()
        }
    }

    private fun onChatConnectTapped() {
        saveChatConnectionSettings()
        isChatConnected = isChatConfigured()
        if (isChatConnected) {
            setChatConnectionConfirmed(true)
            statusText.text = getString(R.string.status_chat_connected)
            setChatGroupCollapsed(collapsed = true)
            setOtherSettingsVisible(true)
            setOtherSettingsCollapsed(collapsed = false)
            ensureForegroundServiceRunning()
            startService(
                Intent(this, ClawsfreeForegroundService::class.java).apply {
                    action = ClawsfreeForegroundService.ACTION_REFRESH_CHAT_BINDING
                }
            )
        } else {
            setChatConnectionConfirmed(false)
            setChatGroupCollapsed(collapsed = false)
            setOtherSettingsVisible(false)
        }
        updateRecordButtonVisibility()
        updateUiMode()
    }

    private fun onFinishSetupTapped() {
        if (!isSetupCompleted() && !isChatConnected) {
            showTransientMessage(getString(R.string.setup_finish_requires_chat))
            return
        }

        if (!isSetupCompleted()) {
            setSetupCompleted(true)
            showTransientMessage(getString(R.string.setup_complete_message))
        }

        isSettingsMenuVisible = false
        setAuthGroupCollapsed(collapsed = true)
        setChatGroupCollapsed(collapsed = true)
        setOtherSettingsCollapsed(collapsed = true)
        updateUiMode()
    }

    /**
     * Step 2: User received the code, entered it, taps "Submit Code".
     * Saves the code (and optional 2FA), restarts service so TDLib picks it up.
     */
    private fun onSubmitCodeTapped() {
        saveAuthCodeSettings()
        statusText.text = getString(R.string.status_connecting)
        val action = if (authPhase == "waiting_password") {
            ClawsfreeForegroundService.ACTION_SUBMIT_PASSWORD
        } else {
            ClawsfreeForegroundService.ACTION_SUBMIT_AUTH
        }
        startService(
            Intent(this, ClawsfreeForegroundService::class.java).apply {
                this.action = action
            }
        )
    }

    private fun showAuthSection(hint: String) {
        setAuthGroupCollapsed(collapsed = false)
        authSection.visibility = View.VISIBLE
        authHintText.text = hint
    }

    private fun saveAuthSettingsForNewCycle() {
        val apiId = inputApiId.text.toString().toIntOrNull() ?: 0
        val apiHash = inputApiHash.text.toString().trim()
        val phone = inputPhone.text.toString().trim()
        val groupId = inputGroupId.text.toString().toLongOrNull() ?: 0L
        val topicId = inputTopicId.text.toString().toLongOrNull() ?: 0L

        ClawsfreeConfig.save(
            apiId = apiId,
            apiHash = apiHash,
            phoneNumber = phone,
            authCode = "",
            twoFaPassword = "",
            groupId = groupId,
            topicId = topicId
        )
    }

    private fun saveAuthCodeSettings() {
        val apiId = inputApiId.text.toString().toIntOrNull() ?: 0
        val apiHash = inputApiHash.text.toString().trim()
        val phone = inputPhone.text.toString().trim()
        val groupId = ClawsfreeConfig.TELEGRAM_GROUP_ID
        val topicId = ClawsfreeConfig.TELEGRAM_TOPIC_ID
        val authCode = inputAuthCode.text.toString().trim()
        val twoFa = input2faPassword.text.toString()

        ClawsfreeConfig.save(
            apiId = apiId,
            apiHash = apiHash,
            phoneNumber = phone,
            authCode = authCode,
            twoFaPassword = twoFa,
            groupId = groupId,
            topicId = topicId
        )
    }

    private fun saveChatConnectionSettings() {
        val apiId = ClawsfreeConfig.TELEGRAM_API_ID
        val apiHash = ClawsfreeConfig.TELEGRAM_API_HASH
        val phone = ClawsfreeConfig.TELEGRAM_PHONE_NUMBER
        val authCode = ClawsfreeConfig.TELEGRAM_AUTH_CODE
        val twoFa = ClawsfreeConfig.TELEGRAM_2FA_PASSWORD
        val groupId = inputGroupId.text.toString().toLongOrNull() ?: 0L
        val topicId = inputTopicId.text.toString().toLongOrNull() ?: 0L

        ClawsfreeConfig.save(
            apiId = apiId,
            apiHash = apiHash,
            phoneNumber = phone,
            authCode = authCode,
            twoFaPassword = twoFa,
            groupId = groupId,
            topicId = topicId
        )
    }

    private fun isChatConfigured(): Boolean {
        return (inputGroupId.text?.toString()?.toLongOrNull() ?: 0L) != 0L
    }

    private fun setAuthGroupCollapsed(collapsed: Boolean) {
        authGroupContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        authGroupChevron.text = if (collapsed) "▸" else "▾"
    }

    private fun setChatGroupCollapsed(collapsed: Boolean) {
        chatGroupContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        chatGroupChevron.text = if (collapsed) "▸" else "▾"
        updateRecordButtonVisibility()
    }

    private fun setOtherSettingsVisible(visible: Boolean) {
        otherSettingsCard.visibility = if (visible) View.VISIBLE else View.GONE
        otherSettingsHeader.visibility = if (visible) View.VISIBLE else View.GONE
        otherSettingsContent.visibility = if (visible) otherSettingsContent.visibility else View.GONE
    }

    private fun setOtherSettingsCollapsed(collapsed: Boolean) {
        otherSettingsContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        otherSettingsChevron.text = if (collapsed) "▸" else "▾"
        updateRecordButtonVisibility()
    }

    private fun updateRecordButtonVisibility() {
        val dashboardVisible = dashboardContainer.visibility == View.VISIBLE
        btnRecordToggle.visibility = if (dashboardVisible) View.VISIBLE else View.GONE
        if (dashboardVisible) {
            btnRecordToggle.post { updateRecordButtonSize(btnRecordToggle.width) }
        }
        handleActivityState(currentActivityState)
    }

    private fun idleActivityHint(assistantState: AssistantState): String {
        return when {
            !isChatConnected -> getString(R.string.activity_idle_hint_setup)
            !assistantState.isConfigured -> getString(R.string.activity_idle_hint_no_assistant)
            else -> getString(R.string.activity_idle_hint_ready)
        }
    }

    private fun isChatConnectionConfirmed(): Boolean {
        return onboardingPrefs().getBoolean(KEY_CHAT_CONNECTED, false)
    }

    private fun setChatConnectionConfirmed(connected: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_CHAT_CONNECTED, connected).apply()
    }

    private fun isSetupCompleted(): Boolean {
        return onboardingPrefs().getBoolean(KEY_SETUP_COMPLETED, false)
    }

    private fun setSetupCompleted(completed: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    private fun onboardingPrefs() = getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE)

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun ensureForegroundServiceRunning() {
        val intent = Intent(this, ClawsfreeForegroundService::class.java).apply {
            action = ClawsfreeForegroundService.ACTION_ENSURE_RUNNING
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun beginAuthHandshake() {
        ensureForegroundServiceRunning()
        startService(
            Intent(this, ClawsfreeForegroundService::class.java).apply {
                action = ClawsfreeForegroundService.ACTION_BEGIN_AUTH
            }
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val ONBOARDING_PREFS = "onboarding_state"
        private const val KEY_CHAT_CONNECTED = "chat_connected"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val SETTING_ASSISTANT = "assistant"
        private const val SETTING_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    }
}
