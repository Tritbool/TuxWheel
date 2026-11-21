package io.github.tuxwheel

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.DialogInterface.OnShowListener
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.AnimationDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.DialogHelper.checkBatteryOptimizationsAndShowAlert
import io.github.tuxwheel.databinding.ActivityMainBinding
import io.github.tuxwheel.settings.mainScreen
import io.github.tuxwheel.ui.theme.AppTheme
import io.github.tuxwheel.utils.*
import io.github.tuxwheel.utils.Alarms.checkAlarm
import io.github.tuxwheel.utils.PermissionsUtil.checkBlePermissions
import io.github.tuxwheel.utils.PermissionsUtil.checkExternalFilePermission
import io.github.tuxwheel.utils.PermissionsUtil.checkNotificationsPermissions
import io.github.tuxwheel.utils.PermissionsUtil.isMaxBleReq
import io.github.tuxwheel.utils.SomeUtil.playBeep
import io.github.tuxwheel.utils.SomeUtil.playPhoneBeep
import io.github.tuxwheel.views.PiPView
import com.google.android.material.snackbar.Snackbar
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val appConfig: AppConfig by inject()
    private val notifications: NotificationUtil by inject()
    private val volumeKeyController: VolumeKeyController by inject()
    private var eventsLoggingTree: EventsLoggingTree? = null
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.setLocale(base))
    }

    private fun startConnectionLostBeeps() {
        if (appConfig.noConnectionSound <= 0 || connectionLostBeepJob?.isActive == true) return
        connectionLostBeepJob = lifecycleScope.launch {
            while (true) {
                val period = appConfig.noConnectionSound
                if (period <= 0) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                kotlinx.coroutines.delay(period * 1_000L)
                val state = viewModel.sessionState.value
                if (!state.isConnected && appConfig.noConnectionSound > 0) {
                    playPhoneBeep()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateAutoMute(state: BleSessionState) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val shouldMute = appConfig.useStopMusic && state.isConnected && state.currentSpeed < 3.5
        if (shouldMute && !mutedByAutoMute) {
            musicWasMutedBeforeAutoMute = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            if (!musicWasMutedBeforeAutoMute) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0
                )
                mutedByAutoMute = true
            }
        } else if (!shouldMute && mutedByAutoMute) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0
            )
            mutedByAutoMute = false
        }
    }

    //region private variables
    private lateinit var binding: ActivityMainBinding
    lateinit var pagerAdapter: MainPageAdapter
    private var containerRenderer: MainContainerRenderer? = null
    lateinit var pipView: ComposeView
    var mMenu: Menu? = null
    private var miSearch: MenuItem? = null
    private var miWheel: MenuItem? = null
    private var miLogging: MenuItem? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var doubleBackToExitPressedOnce = false
    private var snackbar: Snackbar? = null
    private val speedModel: PiPView.SpeedModel by lazy { PiPView.SpeedModel() }

    // Shared app-wide singleton (see bleModule); must use `inject()`, not `viewModel()`.
    private val viewModel: BleSessionViewModel by inject()
    private var settingsNavHostController: NavHostController? = null

    // Current connection state, derived from sessionState observation
    private var mConnectionState: BLEConstants.ConnectionState =
        BLEConstants.ConnectionState.DISCONNECTED

    // Dialog shown when protocol auto-detection fails
    private var protocolSelectionDialog: AlertDialog? = null
    private var wheelPasswordDialog: AlertDialog? = null
    private var githubUpdates: GithubUpdatePrompt? = null

    // Last BLE error already reported to the user, to avoid repeating the same snackbar
    private var lastShownError: String? = null
    private var connectionLostBeepJob: Job? = null
    private var connectionWasLost = false
    private var mutedByAutoMute = false
    private var musicWasMutedBeforeAutoMute = false
    private val audioPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (appConfig.getResId(key)) {
            R.string.no_connection_sound -> {
                connectionLostBeepJob?.cancel()
                connectionLostBeepJob = null
                if (connectionWasLost && appConfig.noConnectionSound > 0) startConnectionLostBeeps()
            }
            R.string.use_stop_music -> updateAutoMute(viewModel.sessionState.value)
        }
    }

    // Logging service
    private var loggingService: LoggingService? = null
    private var isLoggingServiceBound: Boolean = false
    private var bmsDisplaySignature: String = ""
    private var paramsDisplaySignature: String = ""
    private val mLoggingServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            loggingService = (service as LoggingService.LocalBinder).getService()
            isLoggingServiceBound = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            loggingService = null
            isLoggingServiceBound = false
            Timber.e("LoggingService disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            loggingService = null
            isLoggingServiceBound = false
        }
    }
    //endregion

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
            togglePipView(show = isInPictureInPictureMode)
            githubUpdates?.refreshVisibility()
        }
    }

    private fun togglePipView(show: Boolean) {
        if (show) {
            try {
                pipView.setContent {
                    PiPView().SpeedWidget(modifier = Modifier.fillMaxSize(), model = speedModel)
                }
                pipView.visibility = View.VISIBLE
            } catch (_: Exception) {
                // ignore
            }
        } else {
            pipView.visibility = View.GONE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (appConfig.usePipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !this.isInPictureInPictureMode) {
            speedModel.title = getString(R.string.speed)
            try {
                this.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (e: RuntimeException) {
                Toast.makeText(this, R.string.pip_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var wasReconnecting = false
    private var wasAutoConnecting = false
    private var startupLookupHandled = false

    private fun setConnectionState(connectionState: BLEConstants.ConnectionState) {
        when (connectionState) {
            BLEConstants.ConnectionState.CONNECTED -> {
                pagerAdapter.configureSecondDisplay()
                paramsDisplaySignature = viewModel.wheelType.toString()
                bmsDisplaySignature = ""
                val mac = viewModel.getMac
                if (mac.isNotEmpty()) {
                    appConfig.lastMac = mac
                    val name = viewModel.sessionState.value.selectedDevice?.name.orEmpty()
                    if (name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)) {
                        appConfig.lastName = name
                    }
                }
                if (appConfig.useBeepOnVolumeUp) {
                    volumeKeyController.setActive(true)
                }
                hideSnackBar()
            }

            BLEConstants.ConnectionState.DISCONNECTED -> {
                bmsDisplaySignature = ""
                paramsDisplaySignature = ""
                if (mConnectionState == BLEConstants.ConnectionState.CONNECTED) {
                    showSnackBar(getString(R.string.connection_lost_at), Snackbar.LENGTH_INDEFINITE)
                }
                if (appConfig.useBeepOnVolumeUp) {
                    volumeKeyController.setActive(false)
                }
            }

            else -> {}
        }
        mConnectionState = connectionState
        setMenuIconStates()
    }

    /**
     * Observes BleSessionState and updates the UI accordingly.
     * Replaces the legacy broadcast receivers for WHEEL_DATA_AVAILABLE and BLUETOOTH_CONNECTION_STATE.
     */
    private fun handleSessionStateChange(state: BleSessionState) {
        // NOTE: do not gate this on `isPaused`. The collector already runs inside
        // repeatOnLifecycle(STARTED), which re-emits the current state at ON_START while
        // `isPaused` is still true; dropping that emission left `mConnectionState` stale
        // (wrong menu icon and an inverted connect/disconnect action after backgrounding).

        val wasConnected = mConnectionState == BLEConstants.ConnectionState.CONNECTED

        // Connection state changes
        if (state.isReconnecting != wasReconnecting || state.isAutoConnecting != wasAutoConnecting) {
            wasReconnecting = state.isReconnecting
            wasAutoConnecting = state.isAutoConnecting
            setMenuIconStates()
        }
        if (state.connectionState != mConnectionState) {
            setConnectionState(state.connectionState)

            // Notify logging service of connection state change
            val blessedState = if (state.isConnected) BLEConstants.ConnectionState.CONNECTED
            else BLEConstants.ConnectionState.DISCONNECTED
            loggingService?.updateConnectionState(blessedState)

            when (state.connectionState) {
                BLEConstants.ConnectionState.CONNECTED -> {
                    if ((!appConfig.backgroundSessionEnabled || appConfig.backgroundSessionPaused) &&
                        !LoggingService.isInstanceCreated() && appConfig.autoLog && appConfig.startAutoLoggingWhenIsMovingMore == 0f) {
                        toggleLoggingService()
                    }
                    notifications.notificationMessageId = R.string.connected
                }

                BLEConstants.ConnectionState.DISCONNECTED -> {
                    notifications.notificationMessageId = R.string.disconnected
                }

                else -> {}
            }
            notifications.update()
        }
        when {
            state.isConnected -> {
                connectionWasLost = false
                connectionLostBeepJob?.cancel()
            }
            wasConnected && state.connectionState == BLEConstants.ConnectionState.DISCONNECTED -> {
                connectionWasLost = true
                startConnectionLostBeeps()
            }
        }
        updateAutoMute(state)

        // Protocol auto-detection failed — show picker so the user can select manually
        if (state.protocolSelectionRequired && protocolSelectionDialog?.isShowing != true) {
            showProtocolSelectionDialog(state)
        } else if (!state.protocolSelectionRequired && protocolSelectionDialog?.isShowing == true) {
            protocolSelectionDialog?.dismiss()
            protocolSelectionDialog = null
        }

        if (state.passwordPromptRequired && wheelPasswordDialog == null && !isPaused) {
            showWheelPasswordDialog(state.passwordPromptError)
        } else if (!state.passwordPromptRequired) {
            wheelPasswordDialog?.dismiss()
            wheelPasswordDialog = null
        }

        // Data updates
        if (state.lastDataTimestamp != null) {
            val currentBmsSignature = buildBmsDisplaySignature()
            if (currentBmsSignature != bmsDisplaySignature) {
                pagerAdapter.configureSmartBmsDisplay()
                bmsDisplaySignature = currentBmsSignature
            }

            // The wheel type/manufacturer is only known once telemetry data starts
            // flowing (it is "Unknown" right at CONNECTED time), so the params page
            // (page 2) needs to be (re)configured once it becomes available.
            val currentParamsSignature = viewModel.wheelType.toString()
            if (currentParamsSignature != paramsDisplaySignature) {
                pagerAdapter.configureSecondDisplay()
                paramsDisplaySignature = currentParamsSignature
            }
            // Update logging
            notifications.update()

            // Auto-start logging when moving
            if ((!appConfig.backgroundSessionEnabled || appConfig.backgroundSessionPaused) &&
                !LoggingService.isInstanceCreated() && appConfig.startAutoLoggingWhenIsMovingMore != 0f && appConfig.autoLog && viewModel.speedDouble > appConfig.startAutoLoggingWhenIsMovingMore) {
                toggleLoggingService()
            }

            // Check alarms
            if (appConfig.alarmsEnabled) {
                checkAlarm(viewModel.calculatedPwm / 100, applicationContext)
            }

            // Update PiP if active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                speedModel.value.floatValue = viewModel.speedDouble.toFloat()
                pipView.invalidate()
            }

            // Update the pager
            pagerAdapter.updateScreen(false)
        }

        // Surface BLE errors (e.g. "Connection timeout", "Already connecting or connected")
        // so a seemingly unresponsive connect button explains itself.
        val error = state.lastError
        if (error != null && error != lastShownError && !isPaused) {
            showSnackBar(error)
            lastShownError = error
        } else if (error == null) {
            lastShownError = null
        }
    }

    @Suppress("MissingPermission")
    private fun showWheelPasswordDialog(retry: Boolean) {

        val builder = AlertDialog.Builder(this, R.style.OriginalTheme_Dialog_Alert)

        val input = android.widget.EditText(builder.context).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            hint = getString(R.string.password)
        }
        val dialog = builder.setTitle(R.string.wheel_password_title)
            .setMessage(if (retry) R.string.wheel_password_retry else R.string.wheel_password_message)
            .setView(input).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.submitWheelPassword("") }
            .setCancelable(false).create()
        dialog.setOnDismissListener {
            input.text.clear()
            wheelPasswordDialog = null
        }
        wheelPasswordDialog = dialog
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text.toString()
            if (password.isNotEmpty() && (password.length != 4 || password.any { it !in '0'..'9' })) {
                input.error = getString(R.string.wheel_password_invalid)
            } else {
                input.text.clear()
                viewModel.submitWheelPassword(password)
                dialog.dismiss()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun showProtocolSelectionDialog(state: BleSessionState) {
        // Dismiss any previously shown protocol dialog to avoid stacking
        protocolSelectionDialog?.dismiss()

        val candidates = state.protocolCandidates.ifEmpty { viewModel.getAvailableProtocols() }
        if (candidates.isEmpty()) {
            Timber.w("Protocol selection requested but no protocol candidates available")
            viewModel.dismissProtocolSelection()
            return
        }

        val labels = buildProtocolLabels(candidates)

        protocolSelectionDialog = AlertDialog.Builder(this, R.style.OriginalTheme_Dialog_Alert)
            .setCustomTitle(layoutInflater.inflate(R.layout.dialog_protocol_title, null))
            .setItems(labels) { _, which ->
                val selectedId = candidates[which].javaClass.simpleName
                Timber.i("User manually selected protocol: %s", selectedId)
                viewModel.selectProtocol(selectedId)
                protocolSelectionDialog = null
            }.setNegativeButton(R.string.protocol_cancel) { _, _ ->
                viewModel.dismissProtocolSelection()
                protocolSelectionDialog = null
            }.setCancelable(false).show()
    }

    private fun buildProtocolLabels(candidates: List<EUCProtocol>): Array<String> {
        val baseLabels = candidates.map { it.manufacturer }
        val counts = baseLabels.groupingBy { it }.eachCount()
        return candidates.mapIndexed { index, candidate ->
            val label = baseLabels[index]
            if ((counts[label] ?: 0) > 1) "$label (${candidate.javaClass.simpleName})" else label
        }.toTypedArray()
    }

    private fun setMenuIconStates() {
        if (mMenu == null) return
        val state = viewModel.sessionState.value
        miWheel!!.setTitle(R.string.my_wheels)
        miWheel!!.setIcon(ThemeManager.getId(
            if (state.isConnected) ThemeIconEnum.MenuWheelOn else ThemeIconEnum.MenuWheelOff
        ))
        miSearch!!.apply {
            isEnabled = !state.isConnected
            setTitle(if (state.isAutoConnecting) R.string.auto_connect_searching else R.string.search_for_wheel)
            if (state.isAutoConnecting != (icon is AnimationDrawable)) {
                (icon as? AnimationDrawable)?.stop()
                setIcon(if (state.isAutoConnecting) R.drawable.bluetooth_lookup_animation
                    else R.drawable.ic_action_bluetooth_searching_white)
                (icon as? AnimationDrawable)?.start()
            }
        }
        if (LoggingService.isInstanceCreated()) {
            miLogging!!.setTitle(R.string.stop_data_service)
            miLogging!!.setIcon(ThemeManager.getId(ThemeIconEnum.MenuLogOn))
        } else {
            miLogging!!.setTitle(R.string.start_data_service)
            miLogging!!.setIcon(ThemeManager.getId(ThemeIconEnum.MenuLogOff))
        }
        val menuState = if (state.isReconnecting) BLEConstants.ConnectionState.CONNECTING else state.connectionState
        when (menuState) {
            BLEConstants.ConnectionState.CONNECTED -> {
                miLogging!!.isEnabled = true
                miLogging!!.icon!!.alpha = 255
            }

            BLEConstants.ConnectionState.DISCONNECTED -> {
                if (LoggingService.isInstanceCreated()) {
                    miLogging!!.isEnabled = true
                    miLogging!!.icon!!.alpha = 255
                } else {
                    miLogging!!.isEnabled = false
                    miLogging!!.icon!!.alpha = 64
                }
            }

            else -> Unit
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pagerAdapter.updateScreen(true)
    }

    private fun createPager(savedInstanceState: Bundle?) {
        val pages = ArrayList<Int>()
        pages.add(R.layout.main_view_main)
        pages.add(R.layout.main_view_params_list)
        if (appConfig.pageGraph) {
            pages.add(R.layout.main_view_graph)
        }
        if (appConfig.pageTrips) {
            pages.add(R.layout.main_view_trips)
        }
        if (appConfig.pageEvents) {
            pages.add(R.layout.main_view_events)
        }
        pagerAdapter = MainPageAdapter(pages, this)
        if (eventsLoggingTree == null) {
            eventsLoggingTree = EventsLoggingTree(applicationContext)
            Timber.plant(eventsLoggingTree!!)
        }
        containerRenderer = MainContainerRenderer(
            binding,
            pagerAdapter,
            savedInstanceState?.takeIf { it.containsKey("mainSelectedPage") }
                ?.getInt("mainSelectedPage"))
        containerRenderer?.render()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.settingsView.visibility == View.VISIBLE) {
                    if (settingsNavHostController?.previousBackStackEntry == null) {
                        toggleSettings()
                    } else {
                        settingsNavHostController?.navigateUp()
                    }
                } else if (doubleBackToExitPressedOnce) {
                    finish()
                } else {
                    doubleBackToExitPressedOnce = true
                    showSnackBar(R.string.back_to_exit)
                    Handler(Looper.getMainLooper()).postDelayed(
                        { doubleBackToExitPressedOnce = false }, 2000
                    )
                }
            }
        })

        if (onDestroyProcess) {
            Process.killProcess(Process.myPid())
            return
        }

        AppCompatDelegate.setDefaultNightMode(appConfig.dayNightThemeMode)
        super.onCreate(savedInstanceState)
        startupLookupHandled = savedInstanceState?.getBoolean("startupLookupHandled") ?: false
        volumeControlStream = AudioManager.STREAM_MUSIC

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        githubUpdates = GithubUpdatePrompt(this).also { it.start() }
        PreferenceManager.getDefaultSharedPreferences(application)
            .registerOnSharedPreferenceChangeListener(audioPreferencesListener)
        binding.settingsView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.pipView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

        createPager(savedInstanceState)
        pipView = binding.pipView

        binding.textClock.typeface = ThemeManager.getTypeface(applicationContext)
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
        }

        val bluetoothManager = this.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
        } else if (!mBluetoothAdapter!!.isEnabled) {
            if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                enableBleLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            tryStartupLookup()
        }

        // Observe notification button broadcasts from the system notification
        ContextCompat.registerReceiver(
            this,
            mNotificationButtonReceiver,
            makeNotificationIntentFilter(),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mNotificationButtonReceiver,
            makeInternalIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        notifications.update()

        binding.settingsView.apply {
            setContent {
                AppTheme {
                    settingsNavHostController = rememberNavController()
                    mainScreen(navController = settingsNavHostController!!)
                }
            }
        }

        checkBatteryOptimizationsAndShowAlert(this)

        // Observe sessionState for the lifecycle of this activity
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { state ->
                    handleSessionStateChange(state)
                }
            }
        }
    }

    private fun checkClockVisible() {
        if (appConfig.showClock) {
            binding.textClock.visibility = View.VISIBLE
        } else {
            binding.textClock.visibility = View.GONE
        }
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    public override fun onResume() {
        super.onResume()
        isPaused = false
        val state = viewModel.sessionState.value
        if (state.passwordPromptRequired && wheelPasswordDialog == null) {
            showWheelPasswordDialog(state.passwordPromptError)
        }
        if (checkNotificationsPermissions(this)) {
            notifications.update()
        }
        pagerAdapter.updateScreen(true)
        pagerAdapter.updatePageOfTrips()
        checkClockVisible()
        setMenuIconStates()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setMenuIconStates()
    }

    public override fun onPause() {
        super.onPause()
        isPaused = true
        protocolSelectionDialog?.dismiss()
        protocolSelectionDialog = null
        wheelPasswordDialog?.dismiss()
        wheelPasswordDialog = null
    }

    override fun onStart() {
        super.onStart()
        updateAutoMute(viewModel.sessionState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            togglePipView(show = true)
        }
    }

    override fun onStop() {
        super.onStop()
        updateAutoMute(BleSessionState())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            togglePipView(show = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        containerRenderer?.let { outState.putInt("mainSelectedPage", it.pageToSave) }
        super.onSaveInstanceState(outState)
        outState.putInt("connectionState", mConnectionState.ordinal)
        outState.putBoolean("startupLookupHandled", startupLookupHandled)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val ordinal = savedInstanceState.getInt(
            "connectionState", BLEConstants.ConnectionState.DISCONNECTED.ordinal
        )
        mConnectionState =
            BLEConstants.ConnectionState.entries.getOrElse(ordinal) { BLEConstants.ConnectionState.DISCONNECTED }
        setMenuIconStates()
    }

    override fun onDestroy() {
        connectionLostBeepJob?.cancel()
        updateAutoMute(BleSessionState())
        PreferenceManager.getDefaultSharedPreferences(application)
            .unregisterOnSharedPreferenceChangeListener(audioPreferencesListener)
        containerRenderer?.dispose()
        containerRenderer = null
        super.onDestroy()
        eventsLoggingTree?.let {
            Timber.uproot(it)
            it.close()
        }
        eventsLoggingTree = null
        if (!this.isFinishing) {
            Timber.wtf("Recreate main activity")
            return
        }
        Timber.wtf("-=[ finish ]=-")
        if (appConfig.backgroundSessionEnabled) {
            if (isLoggingServiceBound) {
                unbindService(mLoggingServiceConnection)
                isLoggingServiceBound = false
            }
            unregisterReceiver(mNotificationButtonReceiver)
            return
        }
        onDestroyProcess = true
        stopLoggingService()
        viewModel.fullReset()
        if (isLoggingServiceBound) {
            try {
                unbindService(mLoggingServiceConnection)
                isLoggingServiceBound = false
            } catch (_: Exception) {
                // ignored
            }
        }
        try {
            unregisterReceiver(mNotificationButtonReceiver)
        } catch (_: Exception) {
            // ignore
        }
        object : CountDownTimer((2 * 60 * 1000L), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (!LoggingService.isInstanceCreated()) {
                    onFinish()
                }
            }

            override fun onFinish() {
                notifications.close()
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val runningProcesses = am.runningAppProcesses
                for (process in runningProcesses) {
                    if (Process.myPid() != process.pid) {
                        Process.killProcess(process.pid)
                    }
                }
                Process.killProcess(Process.myPid())
            }
        }.start()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Timber.wtf("[Warning] Low memory")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        mMenu = menu.apply {
            miSearch = findItem(R.id.miSearch)
            miWheel = findItem(R.id.miWheel)
            miLogging = findItem(R.id.miLogging)
        }

        setMenuIconStates()
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Alarms.vibrate(this, longArrayOf(0, 30, 40))
        return when (item.itemId) {
            R.id.miSearch -> {
                startScanActivity()
                true
            }

            R.id.miWheel -> {
                showRememberedWheels()
                true
            }

            R.id.miLogging -> {
                if (LoggingService.isInstanceCreated() && appConfig.continueThisDayLog) {
                    val dialog = AlertDialog.Builder(this)
                        .setTitle(R.string.continue_this_day_log_alert_title)
                        .setMessage(R.string.continue_this_day_log_alert_description)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            appConfig.continueThisDayLogMacException = appConfig.lastMac
                            toggleLogging()
                        }
                        .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int -> toggleLogging() }
                        .create()
                    dialog.setOnShowListener(object : OnShowListener {
                        val AUTO_DISMISS_MILLIS = 5000
                        override fun onShow(dialog: DialogInterface) {
                            val defaultButton =
                                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
                            val negativeButtonText = defaultButton.text
                            object : CountDownTimer(AUTO_DISMISS_MILLIS.toLong(), 100) {
                                override fun onTick(millisUntilFinished: Long) {
                                    defaultButton.text = String.format(
                                        Locale.getDefault(),
                                        "%s (%d)",
                                        negativeButtonText,
                                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1
                                    )
                                }

                                override fun onFinish() {
                                    if (dialog.isShowing) {
                                        appConfig.continueThisDayLogMacException = appConfig.lastMac
                                        toggleLogging()
                                        dialog.dismiss()
                                    }
                                }
                            }.start()
                        }
                    })
                    dialog.show()
                } else {
                    toggleLogging()
                }
                true
            }

            R.id.miReset -> {
                viewModel.resetMaxValues()
                showSnackBar(getString(R.string.reset_extremum_values_title))
                true
            }

            R.id.miSettings -> {
                toggleSettings()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    fun toggleSettings() {
        if (binding.settingsView.visibility != View.VISIBLE) {
            binding.settingsView.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(300).setListener(null)
            }
        } else {
            binding.settingsView.animate().alpha(0f).setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.settingsView.visibility = View.GONE
                    }
                })
            checkClockVisible()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {/*            KeyEvent.KEYCODE_BACK -> {
                            if (binding.settingsView.visibility == View.VISIBLE) {
                                if (settingsNavHostController != null) {
                                    if (settingsNavHostController?.previousBackStackEntry == null) {
                                        toggleSettings()
                                    } else {
                                        settingsNavHostController?.navigateUp()
                                    }
                                } else {
                                    toggleSettings()
                                }
                                return true
                            }
                            if (doubleBackToExitPressedOnce) {
                                finish()
                                return true
                            }
                            doubleBackToExitPressedOnce = true
                            showSnackBar(R.string.back_to_exit)
                            Handler(Looper.getMainLooper()).postDelayed(
                                { doubleBackToExitPressedOnce = false },
                                2000
                            )
                            true
                        }*/

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showSnackBar(msg: Int) {
        showSnackBar(getString(msg))
    }

    private fun showSnackBar(msg: String?, timeout: Int = 2000) {
        if (!isPaused) {
            if (snackbar == null) {
                snackbar = Snackbar.make(binding.mainView, "", Snackbar.LENGTH_LONG).apply {
                        view.setBackgroundResource(R.color.primary_dark)
                        setAction(android.R.string.ok) { }
                    }
            }
            snackbar?.apply {
                duration = timeout
                setText(msg!!)
                show()
            }
        }
        Timber.wtf(msg)
    }

    private fun hideSnackBar() {
        snackbar?.dismiss()
    }

    //region services
    private fun stopLoggingService() {
        if (LoggingService.isInstanceCreated()) {
            toggleLoggingService()
        }
    }

    private fun toggleLogging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            toggleLoggingService()
        } else {
            checkExternalFilePermission(this, RESULT_REQUEST_PERMISSIONS_IO)
        }
    }

    /** Toggles the wheel's headlight from the notification quick-action button. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun toggleLight() {
        io.github.tuxwheel.feature.dashboard.DashboardActions.toggleLight(viewModel, appConfig)
    }

    fun toggleLoggingService() {
        val dataLoggerServiceIntent = Intent(applicationContext, LoggingService::class.java)
        if (LoggingService.isInstanceCreated()) {
            if (isLoggingServiceBound) {
                unbindService(mLoggingServiceConnection)
                isLoggingServiceBound = false
            }
            loggingService = null
            stopService(dataLoggerServiceIntent)
            if (!onDestroyProcess) {
                CoroutineScope(Dispatchers.Main + Job()).launch {
                    pagerAdapter.updatePageOfTrips()
                }
            }
        } else if (viewModel.sessionState.value.connectionState == BLEConstants.ConnectionState.CONNECTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(dataLoggerServiceIntent)
            } else {
                startService(dataLoggerServiceIntent)
            }
            bindService(dataLoggerServiceIntent, mLoggingServiceConnection, BIND_AUTO_CREATE)
        }
        setMenuIconStates()
        notifications.update()
    }

    private fun buildBmsDisplaySignature(): String {
        val snapshot = viewModel.bmsDisplay.value
        val packsCellsNums:String = snapshot.packs.map{ p-> p.cellNum}.joinToString(separator = " : ")
        return "${snapshot.wheelType}:${snapshot.model}:${packsCellsNums}"
    }
    //endregion

    @SuppressLint("MissingPermission") // checkBlePermissions covers scan and connect.
    private fun tryStartupLookup() {
        if (startupLookupHandled || mBluetoothAdapter?.isEnabled != true) return
        val wheels = appConfig.rememberedWheels
        if (!checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) return
        startupLookupHandled = true
        if (appConfig.backgroundSessionEnabled) {
            val manager = org.koin.core.context.GlobalContext.get()
                .get<io.github.tuxwheel.background.BackgroundWheelManager>()
            manager.restore()
            if (!appConfig.backgroundSessionPaused) manager.startService()
            return
        }
        viewModel.autoConnect(wheels)
    }

    @SuppressLint("MissingPermission") // checkBlePermissions covers scan and connect.
    private fun toggleConnectToWheel() {
        // Any non-disconnected state (CONNECTED, but also CONNECTING/DISCONNECTING and the
        // library's automatic reconnection attempts) must be cancellable, otherwise the button
        // just re-issues connect() and the library rejects it with "Already connecting or
        // connected", making the button look dead. disconnect() also clears the library's
        // auto-reconnect loop.
        val state = viewModel.sessionState.value
        if (state.isReconnecting || state.connectionState != BLEConstants.ConnectionState.DISCONNECTED) {
            if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                viewModel.disconnect()
            }
        } else {
            startupLookupHandled = true
            if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                viewModel.autoConnect(appConfig.rememberedWheels)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showRememberedWheels() {
        startupLookupHandled = true
        viewModel.cancelAutoConnect()
        RememberedWheelsDialog(this, appConfig).show(
            scope = lifecycleScope,
            session = viewModel.sessionState,
            onDisconnect = {
                if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                    viewModel.disconnect()
                }
            },
            onConnect = { wheel ->
                if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                    viewModel.clearForcedProtocol()
                    viewModel.connectToRememberedWheel(wheel.address, wheel.name)
                }
            },
            onScan = ::startScanActivity,
        )
    }

    @androidx.annotation.RequiresPermission(
        android.Manifest.permission.BLUETOOTH_CONNECT
    )
    private fun startScanActivity() {
        startupLookupHandled = true
        // Migrate the legacy target before ScanActivity updates it with a new selection.
        appConfig.migrateRememberedWheels()
        if (checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_MANUAL_SCAN)) {
            if (viewModel.cancelAutoConnect(keepScanning = true)) {
                Toast.makeText(this, R.string.auto_connect_cancelled, Toast.LENGTH_SHORT).show()
            }
            scanLauncher.launch(Intent(this@MainActivity, ScanActivity::class.java))
        } else if (isMaxBleReq) {
            showSnackBar(R.string.bluetooth_required)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RESULT_REQUEST_PERMISSIONS_BT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (mBluetoothAdapter?.isEnabled == true) tryStartupLookup()
                else if (mBluetoothAdapter != null) enableBleLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else if (requestCode == RESULT_REQUEST_PERMISSIONS_MANUAL_SCAN) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScanActivity()
            }
        } else if (requestCode == RESULT_REQUEST_PERMISSIONS_IO) {
            toggleLoggingService()
        }
    }

    /**
     * Broadcast receiver for notification action buttons.
     * Replaces the legacy mCoreBroadcastReceiver for notification button handling.
     */
    private val mNotificationButtonReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.NOTIFICATION_BUTTON_CONNECTION -> {
                    toggleConnectToWheel()
                    notifications.update()
                }

                Constants.NOTIFICATION_BUTTON_LOGGING -> {
                    toggleLogging()
                    notifications.update()
                }

                Constants.NOTIFICATION_BUTTON_BEEP -> playBeep()
                Constants.NOTIFICATION_BUTTON_LIGHT -> toggleLight()
                Constants.ACTION_LOGGING_SERVICE_TOGGLED -> {
                    val isRunning = intent.getBooleanExtra(Constants.INTENT_EXTRA_IS_RUNNING, false)
                    val path = intent.getStringExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION)
                    if (isRunning && !path.isNullOrEmpty()) {
                        showSnackBar("${getString(R.string.started_logging)} $path")
                    }
                    setMenuIconStates()
                    notifications.update()
                    if (!isRunning && !onDestroyProcess) {
                        pagerAdapter.updatePageOfTrips()
                    }
                }

                Constants.ACTION_PREFERENCE_RESET -> {
                    Timber.i("Reset battery lowest")
                    pagerAdapter.resetBatteryLowest()
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private val scanLauncher =

        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            if (result.resultCode == RESULT_OK) {
                val mac = result.data?.getStringExtra("MAC") ?: ""
                val name = result.data?.getStringExtra("NAME") ?: ""
                val protocolId = result.data?.getStringExtra("PROTOCOL_ID")
                Timber.i(
                    "Device selected MAC = %s, Name = %s, Protocol = %s",
                    mac,
                    name,
                    protocolId ?: "auto"
                )
                viewModel.fullReset()
                pagerAdapter.updateScreen(true)
                setMenuIconStates()

                if (mac.isNotEmpty() && checkBlePermissions(this, RESULT_REQUEST_PERMISSIONS_BT)) {
                    // The advertised name feeds the library's protocol auto-detection, so
                    // remember it for later reconnections to the same wheel.
                    appConfig.lastName = name
                    if (protocolId != null) {
                        viewModel.forceProtocol(protocolId)
                    } else {
                        viewModel.clearForcedProtocol()
                    }
                    viewModel.connectByAddress(mac, name)
                }
            } else {
                Timber.i("Scan device selection cancelled.")
            }
        }

    @Suppress("MissingPermission")
    private val enableBleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && mBluetoothAdapter!!.isEnabled) {
                tryStartupLookup()
            } else {
                Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // File import via Android content picker
    val getCsvResults =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
            uris?.takeIf { it.isNotEmpty() }?.let { selectedUris ->
                lifecycleScope.launch {
                    var imported = 0
                    var failed = 0
                    withContext(Dispatchers.IO) {
                        selectedUris.forEach { uri ->
                            try {
                                val displayName = contentResolver.query(
                                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                                )?.use { cursor ->
                                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                                } ?: throw IOException("The selected file has no name")
                                val fileName = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
                                require(fileName.isNotEmpty()) { "The selected file has no usable name" }
                                require(fileName.endsWith(".csv", ignoreCase = true)) {
                                    "The selected file is not a CSV log"
                                }
                                val input = contentResolver.openInputStream(uri)
                                    ?: throw IOException("Unable to open the selected file")
                                input.use { stream ->
                                    val fileUtil = FileUtil(applicationContext)
                                    try {
                                        check(fileUtil.prepareFile(fileName, "manual", append = false)) {
                                            "Unable to create the imported log"
                                        }
                                        fileUtil.writeAllStream(stream)
                                    } finally {
                                        fileUtil.close()
                                    }
                                }
                                imported++
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (exception: Exception) {
                                Timber.w(exception, "Importing selected CSV log")
                                failed++
                            }
                        }
                    }
                    if (imported > 0 && ::pagerAdapter.isInitialized) {
                        pagerAdapter.updatePageOfTrips()
                    }
                    val message = if (failed > 0) {
                        getString(R.string.trip_import_failed, failed)
                    } else {
                        getString(R.string.trip_import_success, imported)
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun makeNotificationIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(Constants.NOTIFICATION_BUTTON_CONNECTION)
            addAction(Constants.NOTIFICATION_BUTTON_LOGGING)
            addAction(Constants.NOTIFICATION_BUTTON_BEEP)
            addAction(Constants.NOTIFICATION_BUTTON_LIGHT)
            addAction(Constants.ACTION_PREFERENCE_RESET)
        }
    }

    /** App-internal broadcasts, deliberately registered as non-exported. */
    private fun makeInternalIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(Constants.ACTION_LOGGING_SERVICE_TOGGLED)
        }
    }

    companion object {
        lateinit var audioManager: AudioManager

        const val RESULT_REQUEST_PERMISSIONS_BT = 40
        private const val RESULT_REQUEST_PERMISSIONS_MANUAL_SCAN = 41
        const val RESULT_REQUEST_PERMISSIONS_IO = 50
        private var onDestroyProcess = false

        var isPaused = true
    }
}