package io.github.tuxwheel.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tuxwheel.R
import io.github.tuxwheel.utils.Calculator
import io.github.tuxwheel.utils.Constants
import io.github.tuxwheel.utils.Constants.wheel_type_from_string
import io.github.tuxwheel.utils.SmartBms
import io.github.tuxwheel.bms.BmsMapper
import io.github.tuxwheel.bms.BmsSnapshot
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.ConnectionCallback
import io.github.tritbool.euc.ble.core.DataCallback
import io.github.tritbool.euc.ble.core.ErrorCallback
import io.github.tritbool.euc.ble.core.ProtocolSelection
import io.github.tritbool.euc.ble.core.ProtocolSelectionMode
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.PasswordOperation
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import java.io.IOException
import timber.log.Timber
import java.util.ArrayList
import java.util.Calendar
import java.util.Timer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.utils.SomeUtil.playBeep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel that manages the BLE session state and provides a reactive interface
 * for the UI to observe BLE connection, device discovery, and telemetry data.
 * 
 * This ViewModel encapsulates the EucBleClient and transforms its callbacks into
 * StateFlow streams that can be safely consumed by the UI on the main thread.
 * 
 * REPLACES: WheelData.java (legacy singleton)
 */
class BleSessionViewModel internal constructor(
    application: Application,
    clientOverride: EucBleClient?,
) : AndroidViewModel(application), KoinComponent {
    constructor(application: Application) : this(application, null)

    private val appConfig: AppConfig by inject()

    private val _sessionState = MutableStateFlow(BleSessionState.EMPTY)
    val sessionState: StateFlow<BleSessionState> = _sessionState.asStateFlow()

    private val _manualDisconnects = MutableSharedFlow<String>(extraBufferCapacity = 8)
    internal val manualDisconnects: SharedFlow<String> = _manualDisconnects.asSharedFlow()

    internal val rawTraffic = RawTrafficCapture()
    internal val bmsLabCapture = BmsLabCapture()
    private var rawTrafficAddress = ""

    private val _bmsSnapshots = MutableSharedFlow<List<BMSData>>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(emptyList()) }
    val bmsSnapshots: SharedFlow<List<BMSData>> = _bmsSnapshots.asSharedFlow()

    // EucBleClient instance - the single source of truth for BLE operations
    // Renamed to _eucBleClient to avoid JVM clash with public fun getEucBleClient()
    private val _eucBleClient: EucBleClient by lazy {
        val handler = CoroutineExceptionHandler { _, throwable ->
            viewModelScope.launch { handleClientError(throwable.message ?: "BLE internal error") }
        }
        val client = clientOverride ?: EucBleClient(
            application,
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        )
        client.initialize()
        setupCallbacks(client)

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appConfig.rawLoggingPreferences().collect { enabled ->
                if (!enabled) rawTraffic.discardPending()
            }
        }

        // Subscribe before the lazy client can be used to connect: trafficFlow has no replay.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.trafficFlow.collect { event ->
                rawTraffic.record(rawTrafficAddress, event, appConfig.enableRawData)
            }
        }

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.securityStateFlow.collect { security ->
                updateWheelSecurity(security, client)
            }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.securityEventFlow.collect { event ->
                handleWheelSecurityEvent(event)
            }
        }

        client
    }

    // ========== SESSION STATISTICS ==========
    private var sessionTopSpeed: Double = 0.0
    private var sessionMaxPower: Double = 0.0
    private var sessionMaxCurrent: Double = 0.0
    private var sessionMaxPhaseCurrent: Double = 0.0
    private var sessionMaxPwm: Double = 0.0
    private var sessionMaxTemperature: Double = 0.0
    private var sessionStartTime: Long = 0
    private var sessionStartDistance: Double = 0.0
    private var sessionStartTotalDistance: Double = 0.0
    private var rideStartTime: Long = 0
    private var ridingTime: Int = 0
    private var lastRideTime: Int = 0
    private var batteryStart: Int = -1
    private var batteryLowest: Int = 101
    private var voltageSag: Int = 20000
    private var wheelIsReady: Boolean = false
    private var wheelAlarm: Boolean = false
    var bmsView: Boolean = false

    // BMS data
    private class BmsFieldPresence {
        var voltage = false
        var current = false
        val temperatures = BooleanArray(6)
    }
    private class BmsPackState {
        val bms = SmartBms()
        val fields = BmsFieldPresence()
    }
    private val bmsPacks = sortedMapOf<Int, BmsPackState>()
    private var bmsWheelIdentity: Pair<Constants.WHEEL_TYPE, String>? = null
    private val _bmsDisplay = MutableStateFlow(captureBmsDisplay())
    val bmsDisplay: StateFlow<BmsSnapshot> = _bmsDisplay.asStateFlow()

    // Mutation and deep-copy publication run without suspension on the main thread.
    // Consumers never read the mutable packs, including when StateFlow coalesces updates.
    @MainThread
    private fun captureBmsDisplay(state: BleSessionState = _sessionState.value): BmsSnapshot {
        val type = wheel_type_from_string(state.deviceManufacturer).takeUnless { it == Constants.WHEEL_TYPE.Unknown }
            ?: bmsWheelIdentity?.first ?: Constants.WHEEL_TYPE.Unknown
        val model = state.deviceModel.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
            ?: bmsWheelIdentity?.second.orEmpty()
        return BmsSnapshot(
            type, model, protoVer, java.util.Collections.unmodifiableList(bmsPacks.map { (number, pack) ->
                val snapshot = BmsMapper.pack(pack.bms, number)
                if (type == Constants.WHEEL_TYPE.INMOTION || type == Constants.WHEEL_TYPE.INMOTION_V2) {
                    val available = buildSet {
                        if (pack.bms.serialNumber.isNotBlank()) add(R.string.bmsSn)
                        if (pack.bms.versionNumber.isNotBlank()) add(R.string.bmsFw)
                        if (pack.fields.voltage) add(R.string.bmsVoltage)
                        if (pack.fields.current) add(R.string.bmsCurrent)
                        val temperatures = listOf(
                            R.string.bmsTemp1, R.string.bmsTemp2, R.string.bmsTemp3,
                            R.string.bmsTemp4, R.string.bmsTemp5, R.string.bmsTemp6
                        )
                        temperatures.forEachIndexed { index, field ->
                            if (pack.fields.temperatures[index]) add(field)
                        }
                    }
                    snapshot.copy(
                        fields = java.util.Collections.unmodifiableMap(snapshot.fields.filterKeys {
                            it in available || it == R.string.bmsAvgCell || it == R.string.bmsMaxCell ||
                                it == R.string.bmsMinCell || it == R.string.bmsCellDiff
                        }),
                        hasMeasurements = available.isNotEmpty()
                    )
                } else if (type != Constants.WHEEL_TYPE.GOTWAY) snapshot else {
                    val unavailable = buildSet {
                        if (!pack.fields.voltage) add(R.string.bmsVoltage)
                        if (!pack.fields.current) add(R.string.bmsCurrent)
                        if (!pack.fields.temperatures[0]) add(R.string.bmsTemp1)
                        if (!pack.fields.temperatures[1]) add(R.string.bmsTemp2)
                    }
                    snapshot.copy(
                        fields = java.util.Collections.unmodifiableMap(snapshot.fields - unavailable),
                        hasMeasurements = pack.fields.voltage || pack.fields.current ||
                            pack.fields.temperatures.any { it }
                    )
                }
            }),
            state.batteryLevel, state.currentVoltage, state.currentCurrent, state.currentTemperature,
            ((state.motorTemperature ?: 0.0) * 100).toInt() / 100.0
        )
    }

    @MainThread
    private fun clearBmsPacks() {
        bmsPacks.clear()
        bmsWheelIdentity = null
        _bmsSnapshots.tryEmit(emptyList())
    }

    // ========== GRAPH DATA (for charts) ==========
    private val graphUpdateInterval = 1000L // milliseconds
    private var graphLastUpdateTime: Long = 0
    val xAxis = ArrayList<String>()
    val currentAxis = ArrayList<Float>()
    val speedAxis = ArrayList<Float>()

    // ========== RIDING TIMER ==========
    private var ridingTimerControl: Timer? = null
    private val ridingSpeedThreshold = 2

    // ========== PROTOCOL SELECTION ==========
    // True once the library reported an active protocol for the current connection.
    private var protocolActive: Boolean = false
    private var protocolWatchdogJob: Job? = null
    private var passwordPromptOffered = false
    private var passwordResponseJob: Job? = null
    private var diagnosticLogJob: Job? = null

    internal fun refreshCommandDescriptors(
        client: EucBleClient = _eucBleClient,
        forceContextRefresh: Boolean = false,
    ): List<CommandDescriptor> {
        val descriptors = client.getCommandDescriptors()
        _sessionState.update {
            if (!forceContextRefresh && it.commandDescriptors == descriptors) it
            else it.copy(
                commandDescriptors = descriptors,
                commandContextRevision = it.commandContextRevision + 1,
            )
        }
        return descriptors
    }

    internal fun refreshSecurityCapabilities(
        client: EucBleClient = _eucBleClient,
        forceContextRefresh: Boolean = false,
    ) {
        val descriptors = refreshCommandDescriptors(client, forceContextRefresh)
        val commands = setOf(
            CommandType.LOCK, CommandType.UNLOCK, CommandType.REQUEST_LOCK_STATE,
            CommandType.AUTHENTICATE, CommandType.REQUEST_DIAGNOSTIC_LOG, CommandType.CANCEL_DIAGNOSTIC_LOG,
        ).filterTo(mutableSetOf()) { commandId ->
            descriptors.any { it.id == commandId.name && it.isActionableContract() }
        }
        _sessionState.update { it.copy(supportedSecurityCommands = commands) }
    }

    internal fun updateWheelSecurity(security: WheelSecurityState?, client: EucBleClient = _eucBleClient) {
        refreshSecurityCapabilities(client)
        _sessionState.update {
            it.copy(
                wheelLockState = security?.lock ?: WheelLockState.UNKNOWN,
                wheelAuthentication = security?.authentication ?: AuthenticationState.UNKNOWN,
            )
        }
        if (security == null || !isCommandSupported(CommandType.AUTHENTICATE)) {
            resetPasswordPrompt()
        } else if (!passwordPromptOffered) {
            passwordPromptOffered = true
            _sessionState.update { it.copy(passwordPromptRequired = true) }
        }
        if (security?.authentication == AuthenticationState.AUTHENTICATED) {
            passwordResponseJob?.cancel()
            passwordResponseJob = null
            _sessionState.update { it.copy(passwordPromptRequired = false, passwordPromptError = false) }
        }
    }

    internal fun handleWheelSecurityEvent(event: WheelSecurityEvent) {
        if (event is WheelSecurityEvent.LockStatus) {
            _sessionState.update {
                it.copy(
                    wheelLockState = event.state,
                    wheelLockStateRevision = it.wheelLockStateRevision + 1,
                )
            }
        }
        if (event is WheelSecurityEvent.PasswordOperationResult) {
            _sessionState.update {
                if (it.passwordOperationPending && it.passwordOperation == event.operation) {
                    it.copy(
                        passwordOperationPending = false,
                        passwordOperationStatus = event.status,
                        passwordOperationError = false,
                    )
                } else {
                    it
                }
            }
        }
        if (event is WheelSecurityEvent.AuthenticationStatus &&
            event.state == AuthenticationState.REQUIRED && passwordResponseJob != null
        ) {
            retryPasswordPrompt()
        }
    }

    private fun resetPasswordPrompt() {
        passwordPromptOffered = false
        passwordResponseJob?.cancel()
        passwordResponseJob = null
        _sessionState.update {
            it.copy(
                passwordPromptRequired = false,
                passwordPromptError = false,
                passwordOperation = null,
                passwordOperationPending = false,
                passwordOperationStatus = null,
                passwordOperationError = false,
            )
        }
    }

    private fun retryPasswordPrompt() {
        passwordResponseJob?.cancel()
        passwordResponseJob = null
        _sessionState.update { it.copy(passwordPromptRequired = true, passwordPromptError = true) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun submitWheelPassword(password: String) {
        if (!_sessionState.value.passwordPromptRequired) return
        if (password.isNotEmpty() && (password.length != 4 || password.any { it !in '0'..'9' })) return
        _sessionState.update { it.copy(passwordPromptRequired = false, passwordPromptError = false) }
        if (password.isEmpty()) return
        if (!isCommandSupported(CommandType.AUTHENTICATE)) return
        passwordResponseJob = viewModelScope.launch {
            delay(10_000.milliseconds)
            retryPasswordPrompt()
        }
        sendCommand(CommandType.AUTHENTICATE.name, CommandValue.Text(password))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setWheelPassword(newPassword: String): Boolean =
        submitWheelPasswordOperation(PasswordOperation.SET, CommandType.SET_PASSWORD, newPassword) {
            sendCommand(CommandType.SET_PASSWORD.name, CommandValue.Text(newPassword))
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun changeWheelPassword(oldPassword: String, newPassword: String): Boolean =
        submitWheelPasswordOperation(PasswordOperation.CHANGE, CommandType.CHANGE_PASSWORD, oldPassword, newPassword) {
            sendCommand(
                CommandType.CHANGE_PASSWORD.name,
                PasswordChangeRequest(oldPassword, newPassword),
            )
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun removeWheelPassword(currentPassword: String): Boolean =
        submitWheelPasswordOperation(PasswordOperation.REMOVE, CommandType.REMOVE_PASSWORD, currentPassword) {
            sendCommand(CommandType.REMOVE_PASSWORD.name, CommandValue.Text(currentPassword))
        }

    private fun submitWheelPasswordOperation(
        operation: PasswordOperation,
        command: CommandType,
        currentPassword: String,
        newPassword: String = "",
        submit: () -> Unit,
    ): Boolean {
        fun valid(password: String) = password.length == 4 && password.all { it in '0'..'9' }
        if (!valid(currentPassword) || (operation == PasswordOperation.CHANGE && !valid(newPassword)) ||
            !isCommandSupported(command) ||
            _sessionState.value.passwordOperationPending
        ) return false

        _sessionState.update {
            it.copy(
                passwordOperation = operation,
                passwordOperationPending = true,
                passwordOperationStatus = null,
                passwordOperationError = false,
            )
        }
        return try {
            submit()
            true
        } catch (_: Exception) {
            _sessionState.update {
                it.copy(
                    passwordOperationPending = false,
                    passwordOperationError = true,
                )
            }
            false
        }
    }

    fun clearWheelPasswordOperationResult() {
        _sessionState.update {
            if (it.passwordOperationPending) it else it.copy(
                passwordOperation = null,
                passwordOperationStatus = null,
                passwordOperationError = false,
            )
        }
    }

    // Grace period given to the library to auto-detect the wheel protocol after the
    // GATT connection is established, before the user is asked to pick one manually.
    private val protocolDetectionTimeoutMs = 6000L

    private class WheelLookup(
        val wheels: List<RememberedWheel>,
        val automatic: Boolean,
        val rememberOnConnect: Boolean,
    )
    @Volatile
    private var wheelLookup: WheelLookup? = null
    private var handedOffLookup: WheelLookup? = null
    private var wheelLookupTimeout: Job? = null
    private var wheelPriorityTimeout: Job? = null
    @Volatile
    private var manualScanRequested = false
    @Volatile
    private var ignoredScanCompletions = 0
    // The client owns retries (including its backoff while reporting DISCONNECTED).
    @Volatile
    private var connectionRequested = false
    private var wheelToRemember: EUCDevice? = null

    init {
        Timber.i("BleSessionViewModel initialized - REPLACES WheelData")
        startRidingTimerControl()
    }

    private fun setupCallbacks(client: EucBleClient) {
        client.setConnectionCallback(object : ConnectionCallback() {
            override fun onConnecting() {
                viewModelScope.launch {
                    if (connectionRequested) updateConnectionState(BLEConstants.ConnectionState.CONNECTING)
                }
            }

            override fun onConnected() {
                viewModelScope.launch {
                    if (connectionRequested) {
                        val device = client.getConnectedDevice()
                        val target = wheelToRemember
                        if (target != null) {
                            val name = device?.takeIf { it.address.equals(target.address, ignoreCase = true) }
                                ?.name ?: target.name
                            appConfig.rememberWheel(target.address, name)
                        } else if (device != null) {
                            // Refresh the BLE name (e.g. a manual "wheelN" placeholder) of saved wheels only.
                            appConfig.rememberWheel(device.address, device.name, addIfMissing = false)
                        }
                        wheelToRemember = null
                        updateConnectedDevice(device)
                    }
                }
            }

            override fun onDisconnected() {
                viewModelScope.launch {
                    if (wheelLookup != null) return@launch
                    // The client retains the target on link loss, but clears it on a
                    // deliberate disconnect (including some protocol setup failures).
                    connectionRequested = connectionRequested && client.getConnectedDevice() != null
                    if (!connectionRequested) wheelToRemember = null
                    updateDisconnectedState()
                }
            }

            override fun onConnectionFailed(error: BLEException) {
                viewModelScope.launch {
                    connectionRequested = false
                    wheelToRemember = null
                    updateDisconnectedState()
                    updateError(error.message ?: "Connection failed")
                }
            }

            override fun onScanStarted() {
                viewModelScope.launch {
                    if (wheelLookup != null || manualScanRequested) {
                        _sessionState.update { it.copy(isScanning = true) }
                    } else {
                        // The library starts the platform scan asynchronously; it may have
                        // started just as a lookup was cancelled.
                        stopClientScan()
                    }
                }
            }

            override fun onDeviceDiscovered(device: EUCDevice) {
                val lookup = wheelLookup
                viewModelScope.launch {
                    if (lookup != null && lookup === wheelLookup) {
                        mergeScanResult(device)
                        if (lookup.wheels.first().address.equals(device.address, ignoreCase = true)) {
                            finishWheelLookup(device = device)
                        } else if (lookup.automatic && wheelPriorityTimeout == null &&
                            lookup.wheels.any { it.address.equals(device.address, ignoreCase = true) }
                        ) {
                            val waitSeconds = appConfig.wheelPriorityWaitSeconds
                            if (waitSeconds == 0) {
                                completeWheelLookup(lookup)
                            } else {
                                wheelPriorityTimeout = viewModelScope.launch {
                                    delay(waitSeconds * 1_000L)
                                    if (wheelLookup === lookup) completeWheelLookup(lookup)
                                }
                            }
                        }
                    } else if (manualScanRequested && (lookup == null || lookup === handedOffLookup)) {
                        mergeScanResult(device)
                    }
                }
            }

            override fun onScanCompleted(devices: List<EUCDevice>) {
                if (ignoredScanCompletions > 0) {
                    ignoredScanCompletions--
                    return
                }
                val lookup = wheelLookup
                viewModelScope.launch {
                    // A queued completion from an earlier scan must not end a new lookup.
                    if (lookup !== wheelLookup && (!manualScanRequested || lookup !== handedOffLookup)) return@launch
                    if (lookup != null && lookup === wheelLookup) {
                        _sessionState.update {
                            it.copy(scanResults = ScanResultMerger.merge(it.scanResults, devices))
                        }
                        completeWheelLookup(lookup, scanAlreadyStopped = true)
                        return@launch
                    }
                    manualScanRequested = false
                    handedOffLookup = null
                    _sessionState.value = _sessionState.value.copy(
                        isScanning = false,
                        // The library keeps its results in a hash map, so this list is in
                        // arbitrary order. Merge it into the discovery-ordered list built
                        // from onDeviceDiscovered instead of replacing it, otherwise the
                        // device list visibly reshuffles when the scan ends.
                        scanResults = ScanResultMerger.merge(
                            _sessionState.value.scanResults,
                            devices
                        )
                    )
                }
            }

            override fun onProtocolSelectionRequired(protocols: List<EUCProtocol>) {
                viewModelScope.launch {
                    // The library also emits this callback as soon as
                    // AUTO_WITH_MANUAL_FALLBACK is enabled while no protocol is active,
                    // i.e. before the wheel is even connected. Auto-detection has not run
                    // at that point, so prompting the user would be premature (and would
                    // fail, since a protocol can only be selected on a connected device).
                    if (!_sessionState.value.isConnected) {
                        Timber.d("Ignoring protocol selection request received before connection")
                        return@launch
                    }
                    requestProtocolSelection(protocols)
                }
            }

            @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            override fun onProtocolSelected(selection: ProtocolSelection) {
                viewModelScope.launch {
                    protocolActive = true
                    cancelProtocolWatchdog()
                    _sessionState.value = _sessionState.value.copy(
                        protocolSelectionRequired = false,
                        protocolCandidates = emptyList()
                    )
                    Timber.i(
                        "Protocol selected: %s (reason: %s)",
                        selection.manufacturer,
                        selection.reason
                    )
                    refreshSecurityCapabilities(forceContextRefresh = true)
                    sendConnectBeepIfEnabled()
                }
            }
        })

        client.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: EUCData) {
                val receivedAt = System.currentTimeMillis()
                val labSession = bmsLabCapture.session()
                if (appConfig.enableBmsLabData && labSession != null) {
                    val address = mac
                    val packs = runCatching {
                        client.getBMSData()?.map { pack ->
                            pack.copy(
                                temperatures = pack.temperatures?.toList(),
                                cellVoltages = pack.cellVoltages?.toList(),
                            )
                        }
                    }.onFailure { Timber.w(it, "Unable to capture BMS lab snapshot") }.getOrNull()
                    if (address.isNotBlank() && !packs.isNullOrEmpty() &&
                        !bmsLabCapture.record(labSession, receivedAt, address, packs)
                    ) {
                        Timber.e("BMS lab capture queue is full; frame dropped")
                    }
                }
                viewModelScope.launch {
                    try {
                        updateTelemetryData(data)
                    } catch (e: Exception) {
                        // A single malformed packet must not tear down the update pipeline
                        Timber.e(e, "Failed to process telemetry packet")
                    }
                }
            }
        })

        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) {
                val lookup = wheelLookup
                viewModelScope.launch {
                    if (lookup === wheelLookup) handleClientError(error.message ?: "Unknown BLE error")
                }
            }
        })
    }

    internal fun surfaceBleError(message: String) {
        viewModelScope.launch { updateError(message) }
    }

    private fun startRidingTimerControl() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000L.milliseconds)
                if (_sessionState.value.isConnected && (speed > ridingSpeedThreshold)) {
                    ridingTime++
                    _sessionState.value = _sessionState.value.copy(
                        sessionRidingTimeSec = ridingTime.toLong()
                    )
                }
            }
        }
    }

    private fun updateConnectionState(state: BLEConstants.ConnectionState) {
        _sessionState.value = _sessionState.value.copy(
            connectionState = state,
            lastError = if (state == BLEConstants.ConnectionState.DISCONNECTED) {
                if (_sessionState.value.connectionState == BLEConstants.ConnectionState.CONNECTED) {
                    _sessionState.value.lastError
                } else {
                    null
                }
            } else {
                null
            }
        )

        Timber.i("Connection state updated to: %s", state)
    }

    private fun updateConnectedDevice(device: EUCDevice?) {
        // Reset session statistics
        resetSessionStatistics()
        clearBmsPacks()

        _sessionState.value = _sessionState.value.copy(
            connectionState = BLEConstants.ConnectionState.CONNECTED,
            isReconnecting = false,
            selectedDevice = device ?: _sessionState.value.selectedDevice,
            lastData = null,
            lastDataTimestamp = null,
            lastError = null,
            isScanning = false,
            wheelLockState = WheelLockState.UNKNOWN,
            wheelAuthentication = AuthenticationState.UNKNOWN,
            supportedSecurityCommands = emptySet(),
            commandDescriptors = emptyList(),
        )
        refreshCommandDescriptors(forceContextRefresh = true)
        _bmsDisplay.value = captureBmsDisplay()

        if (device != null) {
            Timber.i("Device connected: %s (%s)", device.name, device.address)
        } else {
            Timber.i("Device connected; device metadata is not available yet")
        }

        startProtocolWatchdog()
    }

    private fun updateDisconnectedState() {
        diagnosticLogJob?.cancel()
        diagnosticLogJob = null
        resetPasswordPrompt()
        wheelIsReady = false
        wheelAlarm = false
        clearBmsPacks()
        protocolActive = false
        cancelProtocolWatchdog()

        _sessionState.value = _sessionState.value.copy(
            connectionState = BLEConstants.ConnectionState.DISCONNECTED,
            isReconnecting = connectionRequested,
            selectedDevice = null,
            lastData = null,
            // Telemetry is stale from now on; consumers (logging service, UI) must not
            // keep treating the last received sample as fresh.
            lastDataTimestamp = null,
            protocolSelectionRequired = false,
            protocolCandidates = emptyList(),
            wheelLockState = WheelLockState.UNKNOWN,
            wheelAuthentication = AuthenticationState.UNKNOWN,
            supportedSecurityCommands = emptySet(),
            commandDescriptors = emptyList(),
            commandContextRevision = _sessionState.value.commandContextRevision + 1,
            diagnosticLogBusy = false,
            diagnosticLogError = if (_sessionState.value.diagnosticLogBusy)
                getApplication<Application>().getString(R.string.wheel_diagnostic_disconnected)
            else _sessionState.value.diagnosticLogError,
        )
        _bmsDisplay.value = captureBmsDisplay()

        Timber.i("Device disconnected")
    }

    /**
     * Publishes a manual protocol selection request to the UI.
     */
    private fun requestProtocolSelection(protocols: List<EUCProtocol>) {
        if (protocols.isEmpty() || protocolActive) return
        cancelProtocolWatchdog()
        _sessionState.value = _sessionState.value.copy(
            protocolSelectionRequired = true,
            protocolCandidates = protocols
        )
        Timber.i("Protocol auto-detection failed, %d candidates available", protocols.size)
    }

    /**
     * Watches the connection after it is established: if the library has not activated any
     * protocol within [protocolDetectionTimeoutMs], the wheel would stay connected without
     * ever producing telemetry, so the user is asked to pick a protocol manually.
     *
     * This is required because the library only emits `onProtocolSelectionRequired` once per
     * selection mode change: the notification it raises when AUTO_WITH_MANUAL_FALLBACK is
     * enabled (before connecting) latches the request and suppresses the one that would
     * otherwise be raised when auto-detection actually fails.
     */
    private fun startProtocolWatchdog() {
        cancelProtocolWatchdog()
        if (protocolActive) return
        protocolWatchdogJob = viewModelScope.launch {
            delay(protocolDetectionTimeoutMs.milliseconds)
            val state = _sessionState.value
            if (protocolActive || !state.isConnected || state.lastDataTimestamp != null) {
                return@launch
            }
            Timber.w("No protocol activated %d ms after connecting", protocolDetectionTimeoutMs)
            requestProtocolSelection(_eucBleClient.getRegisteredProtocols())
        }
    }

    private fun cancelProtocolWatchdog() {
        protocolWatchdogJob?.cancel()
        protocolWatchdogJob = null
    }

    @MainThread
    private fun updateTelemetryData(data: EUCData) {
        val previousData = _sessionState.value.lastData
        val identityChanged = previousData == null ||
            previousData.manufacturer != data.manufacturer || previousData.model != data.model
        refreshSecurityCapabilities(forceContextRefresh = identityChanged)
        val type = wheel_type_from_string(data.manufacturer)
        val model = data.model.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
        val previous = bmsWheelIdentity
        if (previous != null && (
            type != Constants.WHEEL_TYPE.Unknown && type != previous.first ||
            model != null && previous.second.isNotEmpty() && model != previous.second
        )) {
            clearBmsPacks()
        }
        if (type != Constants.WHEEL_TYPE.Unknown) {
            bmsWheelIdentity = type to (model ?: bmsWheelIdentity?.second.orEmpty())
        }
        updateBmsData(data)

        // Update session statistics
        data.speed.let { speed ->
            if (speed > sessionTopSpeed) {
                sessionTopSpeed = speed
            }
        }

        data.power.let { power ->
            if (power > sessionMaxPower) {
                sessionMaxPower = power
            }
        }

        data.current.let { current ->
            if (current > sessionMaxCurrent) {
                sessionMaxCurrent = current
            }
        }

        data.phaseCurrent?.let { phaseCurrent ->
            if (phaseCurrent > sessionMaxPhaseCurrent) {
                sessionMaxPhaseCurrent = phaseCurrent
            }
        }

        data.pwm?.let { pwm ->
            if (pwm > sessionMaxPwm) {
                sessionMaxPwm = pwm
            }
        }

        // Track peak board temperature. Some protocols occasionally emit a packet
        // without a valid temperature reading (reported as 0), so only the running
        // maximum is kept — a momentary 0 reading never lowers it and therefore
        // never causes the displayed value to flicker.
        if (data.temperature > sessionMaxTemperature) {
            sessionMaxTemperature = data.temperature
        }

        // Feed the rolling power/distance window used to compute Wh/km
        // consumption (see Calculator.whByKm). Distance is tracked in km
        // elsewhere in this app, but Calculator expects metres.
        data.power.let { power ->
            val totalDistanceKm = data.totalDistance ?: data.wheelDistance
            totalDistanceKm?.let { Calculator.pushPower(power, (it * 1000).toInt()) }
        }

        // Update battery statistics
        val batteryLevel = data.batteryLevel
        if (batteryStart == -1) {
            batteryStart = batteryLevel
        }
        batteryLowest = Math.min(batteryLowest, batteryLevel)

        // Update voltage sag
        val voltage = data.voltage
        if (voltage < voltageSag && voltage > 0) {
            voltageSag = voltage.toInt()
        }

        // Calculate session distance
        val sessionDistance = data.totalDistance?.let { total ->
            if (sessionStartDistance == 0.0) {
                sessionStartDistance = total
            }
            max(total - sessionStartDistance, 0.0)
        } ?: data.wheelDistance?.let { max(it, 0.0) }

        if (sessionStartTotalDistance == 0.0) {
            data.totalDistance?.takeIf { it > 0.0 }?.let { sessionStartTotalDistance = it }
        }

        // Calculate session ride time
        val sessionRideTime = if (sessionStartTime > 0) {
            (System.currentTimeMillis() - sessionStartTime) / 1000
        } else {
            null
        }

        // Update graph data
        val currentTime = Calendar.getInstance().timeInMillis
        if (graphLastUpdateTime + graphUpdateInterval < currentTime) {
            graphLastUpdateTime = currentTime
            currentAxis.add(data.current.toFloat() ?: 0f)
            speedAxis.add(data.speed.toFloat() ?: 0f)
            xAxis.add(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(Calendar.getInstance().time)
            )

            // Limit graph data size
            if (speedAxis.size > (3600000 / graphUpdateInterval)) {
                speedAxis.removeAt(0)
                currentAxis.removeAt(0)
                xAxis.removeAt(0)
            }
        }

        // Check if wheel is ready
        if (!wheelIsReady && data.manufacturer.isNotEmpty()) {
            wheelIsReady = true
        }

        // Update wheel alarm based on data
        wheelAlarm = data.wheelAlarm ?: false

        val mergedData = carryForwardSettingsFields(data)

        val previousFirmwareVersion = _sessionState.value.firmwareVersion

        val nextState = _sessionState.value.copy(
            lastData = mergedData,
            lastDataTimestamp = System.currentTimeMillis(),
            sessionTopSpeed = sessionTopSpeed.takeIf { it > 0 },
            sessionMaxPower = sessionMaxPower.takeIf { it > 0 },
            sessionMaxCurrent = sessionMaxCurrent.takeIf { it > 0 },
            sessionMaxPhaseCurrent = sessionMaxPhaseCurrent.takeIf { it > 0 },
            sessionMaxPwm = sessionMaxPwm.takeIf { it > 0 },
            sessionMaxTemperature = sessionMaxTemperature.takeIf { it > 0 },
            sessionBatteryLowest = batteryLowest.takeIf { it < 101 },
            sessionDistance = sessionDistance?.takeIf { it > 0 },
            sessionRideTime = sessionRideTime
        )
        _bmsDisplay.value = captureBmsDisplay(nextState)
        _sessionState.value = nextState

        // Some wheels only advertise which firmware they run after the connection is already up,
        // and that banner decides which options the command contract offers. Re-read the contract
        // whenever the identification changes so late-arriving variants are picked up.
        if (nextState.firmwareVersion != previousFirmwareVersion) {
            refreshCommandDescriptors(forceContextRefresh = true)
        }

        Timber.d(
            "Telemetry updated: manufacturer=%s, model =%s, speed=%.2f, voltage=%.2f, current=%.2f, pwm=%.2f",
            data.manufacturer, data.model, data.speed, data.voltage, data.current, data.pwm
        )
    }

    /**
     * Some protocols (e.g. Gotway/Begode) split their settings fields across multiple
     * frame types: a frequent "telemetry" frame that never carries them (always null)
     * and a much rarer "settings" frame that does. Since every incoming [EUCData] fully
     * replaces [BleSessionState.lastData], these fields would otherwise flicker
     * null <-> value in the UI at the telemetry frame rate.
     *
     * Carry forward the last known non-null value for each of these fields instead of
     * letting a null in the current packet overwrite a previously known value. Only a
     * genuine disconnect (which clears lastData entirely) should reset them.
     */
    private fun carryForwardSettingsFields(data: EUCData): EUCData {
        val previous = _sessionState.value.lastData ?: return data
        if (previous.manufacturer != data.manufacturer) return data
        val kingsong = wheel_type_from_string(data.manufacturer) == Constants.WHEEL_TYPE.KINGSONG

        return data.copy(
            pedalsMode = data.pedalsMode ?: previous.pedalsMode,
            alarmMode = data.alarmMode ?: previous.alarmMode,
            rollAngleMode = data.rollAngleMode ?: previous.rollAngleMode,
            usesMiles = data.usesMiles ?: previous.usesMiles,
            autoPowerOffMinutes = data.autoPowerOffMinutes ?: previous.autoPowerOffMinutes,
            tiltBackSpeed = data.tiltBackSpeed ?: previous.tiltBackSpeed,
            speedLimit = data.speedLimit ?: previous.speedLimit.takeIf { kingsong },
            dynamicSpeedLimit = data.dynamicSpeedLimit ?: previous.dynamicSpeedLimit.takeIf { kingsong },
            wheelMaxSpeed = data.wheelMaxSpeed ?: previous.wheelMaxSpeed.takeIf { kingsong },
            alarm1Speed = data.alarm1Speed ?: previous.alarm1Speed.takeIf { kingsong },
            alarm2Speed = data.alarm2Speed ?: previous.alarm2Speed.takeIf { kingsong },
            alarm3Speed = data.alarm3Speed ?: previous.alarm3Speed.takeIf { kingsong },
            ledMode = data.ledMode ?: previous.ledMode,
            lightMode = data.lightMode ?: previous.lightMode,
            alertFlags = data.alertFlags ?: previous.alertFlags,
        )
    }

    @MainThread
    private fun updateBmsData(data: EUCData) {
        // EUCData.cellVoltages can concatenate packs or groups; only the per-pack
        // API provides a reliable mapping to each battery.
        val bmsPacks = runCatching {
            _eucBleClient.getBMSData()
                ?.map { it.copy(
                    cellVoltages = it.cellVoltages?.let { cells -> java.util.Collections.unmodifiableList(cells.toList()) },
                    temperatures = it.temperatures?.let { temps -> java.util.Collections.unmodifiableList(temps.toList()) }
                ) }
                ?.sortedBy { it.bmsIndex }
        }.onFailure {
            // getBMSData() reads decoder state that is mutated on the BLE thread, so a
            // transient failure here must never break the telemetry update pipeline.
            Timber.w(it, "Unable to read per-pack BMS data")
        }.getOrNull()

        if (bmsPacks != null) {
            _bmsSnapshots.tryEmit(java.util.Collections.unmodifiableList(bmsPacks))
        }
        if (!bmsPacks.isNullOrEmpty()) {
            // Gotway uses zero-based group indices; other decoders use one-based indices.
            val type = bmsWheelIdentity?.first ?: wheel_type_from_string(data.manufacturer)
            bmsPacks.forEach { pack ->
                val number = if (type == Constants.WHEEL_TYPE.GOTWAY) pack.bmsIndex + 1 else pack.bmsIndex
                if (number > 0) {
                    val target = this.bmsPacks.getOrPut(number) { BmsPackState() }
                    applyBmsPackData(target.bms, target.fields, pack)
                } else {
                    Timber.w("Ignoring invalid BMS index %d for %s", pack.bmsIndex, type)
                }
            }
        }

        // EUCData cells can concatenate multiple packs. Never assign them to a
        // pack; retain authoritative per-pack data across partial packets.
        this.bmsPacks.values.forEach { refreshBmsFallbacks(it.bms, it.fields, data) }
    }

    @MainThread
    private fun refreshBmsFallbacks(bms: SmartBms, fields: BmsFieldPresence, data: EUCData) {
        // Cells do not imply that the decoder measures voltage, current or every
        // temperature probe. Refresh only fields never supplied by this pack.
        if (!fields.voltage) bms.voltage = data.voltage
        if (!fields.current) bms.current = data.current
        if (!fields.temperatures[0]) bms.temp1 = data.temperature
        if (!fields.temperatures[1]) bms.temp2 = data.motorTemperature ?: 0.0
        bms.remPerc = data.batteryLevel
    }

    /**
     * Populates a BMS pack from a [BMSData] snapshot, retaining its measurements
     * across partial updates.
     */
    @MainThread
    private fun applyBmsPackData(bms: SmartBms, fields: BmsFieldPresence, pack: BMSData) {
        pack.serialNumber?.takeIf { it.isNotBlank() }?.let { bms.serialNumber = it }
        pack.firmwareVersion?.takeIf { it.isNotBlank() }?.let { bms.versionNumber = it }
        pack.voltage?.let { bms.voltage = it; fields.voltage = true }
        pack.current?.let { bms.current = it; fields.current = true }

        val temperatures = pack.temperatures
        temperatures.orEmpty().take(6).forEachIndexed { index, temperature ->
            when (index) {
                0 -> bms.temp1 = temperature
                1 -> bms.temp2 = temperature
                2 -> bms.temp3 = temperature
                3 -> bms.temp4 = temperature
                4 -> bms.temp5 = temperature
                5 -> bms.temp6 = temperature
            }
            fields.temperatures[index] = true
        }

        pack.remainingCapacity?.let { bms.remCap = it }
        pack.factoryCapacity?.let { bms.factoryCap = it }
        pack.cycles?.let { bms.fullCycles = it }
        pack.isCharging?.let { bms.isCharging = it }

        val cellVoltages = pack.cellVoltages.orEmpty()
        if (cellVoltages.none { it > 0.0 }) {
            // Keep last known cell data instead of clearing it, mirroring the
            // single-pack fallback behaviour above.
            return
        }

        val packCount = minOf(cellVoltages.size, bms.cells.size)
        bms.cellNum = maxOf(bms.cellNum, packCount)
        for (i in 0 until packCount) {
            if (cellVoltages[i] > 0.0) bms.cells[i] = cellVoltages[i]
        }

        val measured = bms.cells.take(bms.cellNum).filter { it > 0.0 }
        val minCell = measured.minOrNull() ?: 0.0
        val maxCell = measured.maxOrNull() ?: 0.0
        bms.minCell = minCell
        bms.maxCell = maxCell
        bms.avgCell = if (measured.isEmpty()) 0.0 else measured.average()
        bms.cellDiff = maxCell - minCell
        bms.minCellNum = (bms.cells.indexOf(minCell) + 1).coerceAtLeast(1)
        bms.maxCellNum = (bms.cells.indexOf(maxCell) + 1).coerceAtLeast(1)
    }

    private fun updateError(error: String) {
        _sessionState.update { it.copy(lastError = error) }
        Timber.e("BLE error: %s", error)
    }

    @SuppressLint("MissingPermission") // The scan was started after the activity checked permissions.
    private fun handleClientError(error: String) {
        if (wheelLookup != null) {
            finishWheelLookup(error = error)
        } else {
            if (manualScanRequested) {
                manualScanRequested = false
                handedOffLookup = null
                stopClientScan()
            }
            updateError(error)
        }
    }

    // ========== PUBLIC API ==========

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        viewModelScope.launch {
            finishWheelLookup()
            if (manualScanRequested) return@launch
            if (connectionRequested) {
                connectionRequested = false
                wheelToRemember = null
                _sessionState.update { it.copy(isReconnecting = false) }
                try {
                    // A manual scan supersedes both the remembered-wheel lookup and the
                    // library's reconnect loop before starting its own scan.
                    _eucBleClient.disconnect()
                    updateDisconnectedState()
                } catch (e: Exception) {
                    updateError(e.message ?: "Failed to cancel wheel reconnection")
                    return@launch
                }
            }
            manualScanRequested = true
            try {
                // `isScanning` is only raised once the library reports the platform scan has
                // actually started (onScanStarted); otherwise a refused scan would leave the
                // flag stuck at true forever.
                _sessionState.value = _sessionState.value.copy(
                    scanResults = emptyList(),
                    lastError = null
                )
                _eucBleClient.startScan()
                Timber.i("BLE scan requested")
            } catch (e: Exception) {
                handleClientError(e.message ?: "Failed to start scan")
            }
        }
    }

    /**
     * Stops an ongoing scan. Safe (and cheap) to call when no scan is running.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        viewModelScope.launch {
            if (!manualScanRequested) return@launch
            manualScanRequested = false
            handedOffLookup = null
            stopClientScan()
        }
    }

    @SuppressLint("MissingPermission") // Also used for cleanup after a permission or radio error.
    private fun stopClientScan(ignoreCompletion: Boolean = false): Boolean {
        if (ignoreCompletion) ignoredScanCompletions++
        return try {
            _eucBleClient.stopScan()
            true
        } catch (e: Exception) {
            if (ignoreCompletion) ignoredScanCompletions--
            Timber.w(e, "Failed to stop BLE scan")
            false
        } finally {
            _sessionState.update { it.copy(isScanning = false) }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connect(device: EUCDevice) {
        viewModelScope.launch {
            finishWheelLookup()
            if (!connectionRequested) connectDiscoveredDevice(device)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun disconnect(userInitiated: Boolean = true) {
        if (userInitiated) {
            _manualDisconnects.tryEmit(
                _sessionState.value.selectedDevice?.address ?: rawTrafficAddress
            )
        }
        viewModelScope.launch {
            if (wheelLookup != null) {
                finishWheelLookup()
                return@launch
            }
            connectionRequested = false
            wheelToRemember = null
            _sessionState.update { it.copy(isReconnecting = false) }
            try {
                _eucBleClient.disconnect()
                Timber.i("Disconnecting from device")
            } catch (e: Exception) {
                updateDisconnectedState()
                updateError(e.message ?: "Failed to disconnect")
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connectToRememberedWheel(mac: String, name: String) {
        viewModelScope.launch {
            if (!connectionRequested && wheelLookup == null && !manualScanRequested) {
                startWheelLookup(listOf(RememberedWheel(mac, name)), automatic = false)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun autoConnect(wheels: List<RememberedWheel>) {
        if (wheels.isEmpty()) return
        viewModelScope.launch {
            if (!connectionRequested && wheelLookup == null && !manualScanRequested) {
                startWheelLookup(wheels.toList(), automatic = true)
            }
        }
    }

    @MainThread
    fun cancelAutoConnect(keepScanning: Boolean = false): Boolean {
        val lookup = wheelLookup?.takeIf { it.automatic } ?: return false
        if (keepScanning) {
            // Transfer ownership without stopping the radio or dropping discoveries.
            manualScanRequested = true
            handedOffLookup = lookup
            wheelLookup = null
            cancelWheelLookupTimeouts()
            _sessionState.update {
                it.copy(isAutoConnecting = false, connectionState = BLEConstants.ConnectionState.DISCONNECTED)
            }
        } else {
            finishWheelLookup()
        }
        return true
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connectByAddress(mac: String, name: String = "") {
        viewModelScope.launch {
            if (connectionRequested) return@launch
            finishWheelLookup()
            if (manualScanRequested) {
                manualScanRequested = false
                handedOffLookup = null
                stopClientScan()
            }
            val address = RememberedWheel.normalizeAddress(mac) ?: mac
            val discovered = _sessionState.value.scanResults.firstOrNull {
                it.address.equals(address, ignoreCase = true)
            }
            if (discovered != null) connectDiscoveredDevice(discovered, name, rememberOnConnect = true)
            else startWheelLookup(listOf(RememberedWheel(address, name)), automatic = false, rememberOnConnect = true)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startWheelLookup(
        wheels: List<RememberedWheel>,
        automatic: Boolean,
        rememberOnConnect: Boolean = false,
    ) {
        val lookup = WheelLookup(wheels, automatic, rememberOnConnect)
        wheelLookup = lookup
        _sessionState.update {
            it.copy(
                connectionState = BLEConstants.ConnectionState.CONNECTING,
                isAutoConnecting = automatic,
                lastError = null,
                scanResults = emptyList(),
            )
        }
        wheelLookupTimeout = viewModelScope.launch {
            delay(AppConfig.AUTO_CONNECT_SCAN_SECONDS * 1_000L)
            if (wheelLookup === lookup) completeWheelLookup(lookup)
        }
        try {
            _eucBleClient.startScan()
        } catch (e: Exception) {
            finishWheelLookup(error = e.message ?: "Failed to start scan")
        }
    }

    private fun completeWheelLookup(lookup: WheelLookup, scanAlreadyStopped: Boolean = false) {
        val device = RememberedWheelMatcher.preferredAvailable(lookup.wheels, _sessionState.value.scanResults)
        finishWheelLookup(device, notFound = device == null, scanAlreadyStopped = scanAlreadyStopped)
    }

    private fun cancelWheelLookupTimeouts() {
        wheelLookupTimeout?.cancel()
        wheelLookupTimeout = null
        wheelPriorityTimeout?.cancel()
        wheelPriorityTimeout = null
    }

    @SuppressLint("MissingPermission") // Permissions were checked before the lookup; failures are handled below.
    private fun finishWheelLookup(
        device: EUCDevice? = null,
        error: String? = null,
        notFound: Boolean = false,
        scanAlreadyStopped: Boolean = false,
    ) {
        val lookup = wheelLookup ?: return
        // Consume before stopping: stopScan() can synchronously call onScanCompleted().
        wheelLookup = null
        cancelWheelLookupTimeouts()
        val stopped = scanAlreadyStopped || stopClientScan(ignoreCompletion = true)
        _sessionState.update { it.copy(isAutoConnecting = false, isScanning = false) }
        if (device != null && stopped) {
            val name = lookup.wheels.first { it.address.equals(device.address, ignoreCase = true) }.name
            connectDiscoveredDevice(
                device, name, resetProtocol = lookup.automatic, rememberOnConnect = lookup.rememberOnConnect
            )
        } else {
            _sessionState.update {
                it.copy(connectionState = BLEConstants.ConnectionState.DISCONNECTED, isScanning = false)
            }
            val message = error ?: when {
                !stopped && device != null -> "Failed to stop scan"
                notFound && !lookup.automatic -> getApplication<Application>().getString(
                    R.string.remembered_wheel_not_found, lookup.wheels.first().address
                )
                else -> null
            }
            if (message != null) updateError(message)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectDiscoveredDevice(
        device: EUCDevice,
        fallbackName: String = "",
        resetProtocol: Boolean = false,
        rememberOnConnect: Boolean = false,
        externalDevice: BluetoothDevice? = null,
    ) {
        try {
            prepareForConnection()
            val client = _eucBleClient
            val rememberedProtocol = appConfig.rememberedWheels
                .firstOrNull { it.address.equals(device.address, ignoreCase = true) }?.protocolId.orEmpty()
            if (rememberedProtocol.isNotBlank()) forceProtocol(rememberedProtocol)
            else if (resetProtocol) client.clearForcedProtocol()
            if (client.getProtocolSelectionMode() != ProtocolSelectionMode.FORCED) {
                client.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
            }
            val target = if (fallbackName.isNotBlank() &&
                (device.name.isBlank() || device.name.equals("Unknown", ignoreCase = true))
            ) device.copy(name = fallbackName) else device
            rawTrafficAddress = target.address
            rawTraffic.start()
            wheelToRemember = target.takeIf { rememberOnConnect }
            connectionRequested = true
            updateConnectionState(BLEConstants.ConnectionState.CONNECTING)
            if (externalDevice == null) client.connect(target)
            else client.connectExternalDevice(externalDevice, target.name, target.rssi)
            Timber.i("Connecting to device: %s", target.address)
        } catch (e: Exception) {
            connectionRequested = false
            wheelToRemember = null
            updateDisconnectedState()
            updateError(e.message ?: "Failed to connect to ${device.address}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun connectExternalDevice(device: BluetoothDevice, name: String, rssi: Int = 0) {
        viewModelScope.launch {
            if (connectionRequested || wheelLookup != null || manualScanRequested) return@launch
            connectDiscoveredDevice(
                EUCDevice(name, device.address, 0, rssi = rssi),
                resetProtocol = true,
                externalDevice = device,
            )
        }
    }

    /**
     * Resets the per-connection protocol tracking before a new connection attempt.
     */
    private fun prepareForConnection() {
        protocolActive = false
        cancelProtocolWatchdog()
        _sessionState.value = _sessionState.value.copy(
            lastDataTimestamp = null,
            protocolSelectionRequired = false,
            protocolCandidates = emptyList(),
            commandDescriptors = emptyList(),
            commandContextRevision = _sessionState.value.commandContextRevision + 1,
            supportedSecurityCommands = emptySet(),
        )
    }

    fun updateScanResults(devices: List<EUCDevice>) {
        viewModelScope.launch {
            _sessionState.value = _sessionState.value.copy(
                scanResults = devices
            )
        }
    }

    fun addScanResult(device: EUCDevice) {
        viewModelScope.launch { mergeScanResult(device) }
    }

    private fun mergeScanResult(device: EUCDevice) {
        _sessionState.update {
            it.copy(scanResults = ScanResultMerger.merge(it.scanResults, listOf(device)))
        }
    }

    fun clearScanResults() {
        viewModelScope.launch {
            _sessionState.value = _sessionState.value.copy(
                scanResults = emptyList()
            )
        }
    }

    fun getEucBleClient(): EucBleClient = _eucBleClient

    // ========== PROTOCOL SELECTION ==========

    /**
     * Returns all protocols registered in the BLE client.
     * Can be called at any time (before or during connection) to populate a protocol picker UI.
     */
    fun getAvailableProtocols(): List<EUCProtocol> = _eucBleClient.getRegisteredProtocols()

    /**
     * Force a specific protocol by class name before connecting.
     * Sets the selection mode to FORCED so the library skips auto-detection entirely.
     * Call [clearForcedProtocol] to revert to automatic detection.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun forceProtocol(protocolId: String): Boolean {
        val protocol =
            _eucBleClient.getRegisteredProtocols().find { it.javaClass.simpleName == protocolId }
                ?: return false
        return _eucBleClient.forceProtocol(protocol)
    }

    /**
     * Clear any previously forced protocol and revert to automatic detection.
     */
    fun clearForcedProtocol() {
        _eucBleClient.clearForcedProtocol()
    }

    /**
     * Manually select a protocol when auto-detection has failed
     * (i.e. after [BleSessionState.protocolSelectionRequired] becomes true).
     * Clears the [BleSessionState.protocolSelectionRequired] flag on success.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun selectProtocol(protocolId: String): Boolean {
        val protocol =
            _sessionState.value.protocolCandidates.find { it.javaClass.simpleName == protocolId }
                ?: _eucBleClient.getRegisteredProtocols()
                    .find { it.javaClass.simpleName == protocolId }
                ?: return false
        val result = _eucBleClient.selectProtocol(protocol)
        if (result) {
            _sessionState.value.deviceAddress.takeIf { it.isNotBlank() }?.let { address ->
                appConfig.setRememberedWheelProtocol(address, protocolId)
            }
            protocolActive = true
            cancelProtocolWatchdog()
            refreshSecurityCapabilities(forceContextRefresh = true)
            viewModelScope.launch {
                _sessionState.value = _sessionState.value.copy(
                    protocolSelectionRequired = false,
                    protocolCandidates = emptyList()
                )
            }
            Timber.i("Protocol manually selected: %s", protocolId)
        } else {
            Timber.w("Manual protocol selection failed: %s", protocolId)
        }
        return result
    }

    /**
     * Dismiss the protocol selection prompt without choosing a protocol.
     * The wheel will remain connected but without an active decoder.
     */
    fun dismissProtocolSelection() {
        viewModelScope.launch {
            cancelProtocolWatchdog()
            _sessionState.value = _sessionState.value.copy(
                protocolSelectionRequired = false,
                protocolCandidates = emptyList()
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(commandId: String, value: CommandValue = CommandValue.None) {
        _eucBleClient.sendCommand(commandId, value)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setWheelLocked(locked: Boolean) {
        val command = if (locked) CommandType.LOCK else CommandType.UNLOCK
        sendCommand(command.name)
        requestWheelLockState()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun toggleWheelLock() {
        when (_sessionState.value.wheelLockState) {
            WheelLockState.LOCKED -> setWheelLocked(false)
            WheelLockState.UNLOCKED -> setWheelLocked(true)
            WheelLockState.UNKNOWN -> requestWheelLockState()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestWheelLockState() {
        if (isCommandSupported(CommandType.REQUEST_LOCK_STATE)) {
            sendCommand(CommandType.REQUEST_LOCK_STATE.name)
        }
    }

    fun isCommandSupported(commandType: CommandType): Boolean {
        return _sessionState.value.commandDescriptors.any {
            it.id == commandType.name && it.isActionableContract()
        }
    }

    fun isCommandValueSupported(commandId: String, value: CommandValue): Boolean =
        _sessionState.value.commandDescriptors.firstOrNull { it.id == commandId }
            ?.takeIf { it.isActionableContract() && it.input.isSupportedCommandInput() }
            ?.let { it.input.validationError(value) == null } == true

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestDiagnosticLog() {
        val state = _sessionState.value
        if (state.diagnosticLogBusy || state.diagnosticLogSaving) return
        if (!state.isConnected || !isCommandSupported(CommandType.REQUEST_DIAGNOSTIC_LOG)) {
            _sessionState.update {
                it.copy(diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_unavailable))
            }
            return
        }
        _sessionState.update {
            it.copy(diagnosticLogTransfer = null, diagnosticLogBusy = true,
                diagnosticLogError = null, diagnosticLogSaved = false)
        }
        diagnosticLogJob = viewModelScope.launch {
            sendCommand(CommandType.REQUEST_DIAGNOSTIC_LOG.name)
            // Encoding starts synchronously, but a rejected command may produce no snapshot.
            val transfer = withTimeoutOrNull(5_000.milliseconds) {
                while (_eucBleClient.getDiagnosticLogTransfer() == null) delay(100.milliseconds)
                _eucBleClient.getDiagnosticLogTransfer()
            }
            if (transfer == null) {
                _sessionState.update {
                    it.copy(diagnosticLogBusy = false,
                        diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_not_started))
                }
                return@launch
            }
            while (isActive) {
                val snapshot = _eucBleClient.getDiagnosticLogTransfer()
                if (snapshot == null) {
                    _sessionState.update {
                        it.copy(diagnosticLogBusy = false,
                            diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_disconnected))
                    }
                    return@launch
                }
                _sessionState.update {
                    it.copy(diagnosticLogTransfer = snapshot, diagnosticLogBusy = !snapshot.state.isTerminal)
                }
                if (snapshot.state.isTerminal) return@launch
                delay(250.milliseconds)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cancelDiagnosticLog() {
        if (!_sessionState.value.diagnosticLogBusy) return
        sendCommand(CommandType.CANCEL_DIAGNOSTIC_LOG.name)
        diagnosticLogJob?.cancel()
        diagnosticLogJob = null
        _sessionState.update {
            it.copy(diagnosticLogTransfer = _eucBleClient.getDiagnosticLogTransfer(), diagnosticLogBusy = false)
        }
    }

    fun saveDiagnosticLog(uri: Uri) {
        val state = _sessionState.value
        if (state.diagnosticLogSaving) return
        val data = state.diagnosticLogTransfer?.takeIf { it.state == DiagnosticLogState.COMPLETED }?.copyData()
        if (data == null) {
            _sessionState.update {
                it.copy(diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_not_verified))
            }
            return
        }
        _sessionState.update { it.copy(diagnosticLogSaving = true, diagnosticLogSaved = false, diagnosticLogError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                    stream.use { it.write(data) }
                }
                _sessionState.update { it.copy(diagnosticLogSaved = true) }
            } catch (e: IOException) {
                Timber.e(e, "Unable to save wheel diagnostic log")
                _sessionState.update {
                    it.copy(diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_save_failed))
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Access denied saving wheel diagnostic log")
                _sessionState.update {
                    it.copy(diagnosticLogError = getApplication<Application>().getString(R.string.wheel_diagnostic_save_failed))
                }
            } finally {
                _sessionState.update { it.copy(diagnosticLogSaving = false) }
            }
        }
    }

    /**
     * Sends a confirmation beep right after a protocol is selected (a Gotway/Begode wheel
     * double-beeps to confirm
     * it accepted the BLE connection). Only fires when the active protocol supports
     * [CommandType.BEEP] and the user hasn't disabled it via [AppConfig.connectBeep].
     * The BLE connection callback that triggers this only runs once GATT is already
     * connected, so BLUETOOTH_CONNECT is guaranteed to have been granted.
     */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendConnectBeepIfEnabled() {

        if (isCommandSupported(CommandType.BEEP)) {
            Timber.i(
                "Beeping at connect time"
            )
            playBeep()
            sendCommand(CommandType.BEEP.name)
        }
    }


    fun isWheelReady(): Boolean {
        return wheelIsReady
    }

    fun getWheelAlarm(): Boolean {
        return wheelAlarm
    }

    // ========== SESSION MANAGEMENT ==========

    fun resetSessionStatistics() {
        viewModelScope.launch {
            sessionTopSpeed = 0.0
            sessionMaxPower = 0.0
            sessionMaxCurrent = 0.0
            sessionMaxPhaseCurrent = 0.0
            sessionMaxPwm = 0.0
            sessionMaxTemperature = 0.0
            sessionStartTime = System.currentTimeMillis()
            sessionStartDistance = 0.0
            sessionStartTotalDistance = 0.0
            batteryStart = -1
            batteryLowest = 101
            voltageSag = 20000
            ridingTime = 0
            lastRideTime = 0

            _sessionState.update { state ->
                state.copy(
                    sessionTopSpeed = null,
                    sessionMaxPower = null,
                    sessionMaxCurrent = null,
                    sessionMaxPhaseCurrent = null,
                    sessionMaxPwm = null,
                    sessionMaxTemperature = null,
                    sessionBatteryLowest = null,
                    sessionRidingTimeSec = null,
                    sessionDistance = null,
                    sessionRideTime = null,
                    sessionStatisticsRevision = state.sessionStatisticsRevision + 1
                )
            }
        }
    }

    fun resetMaxValues() {
        sessionTopSpeed = 0.0
        sessionMaxPower = 0.0
        sessionMaxCurrent = 0.0
        sessionMaxPhaseCurrent = 0.0
        sessionMaxPwm = 0.0
        sessionMaxTemperature = 0.0
        _sessionState.update { state ->
            state.copy(
                sessionTopSpeed = null,
                sessionMaxPower = null,
                sessionMaxCurrent = null,
                sessionMaxPhaseCurrent = null,
                sessionMaxPwm = null,
                sessionMaxTemperature = null,
                sessionStatisticsRevision = state.sessionStatisticsRevision + 1
            )
        }
    }

    fun resetVoltageSag() {
        voltageSag = 20000
        publishCounterChange()
    }

    fun resetUserDistance() {
        sessionStartTotalDistance = _sessionState.value.totalDistance ?: 0.0
        publishCounterChange()
    }

    private fun publishCounterChange() {
        _sessionState.update { it.copy(sessionStatisticsRevision = it.sessionStatisticsRevision + 1) }
    }

    fun resetBmsData() {
        viewModelScope.launch {
            clearBmsPacks()
            _bmsDisplay.value = captureBmsDisplay()
        }
    }

    fun fullReset() {
        resetSessionStatistics()
        resetBmsData()
        xAxis.clear()
        currentAxis.clear()
        speedAxis.clear()
        wheelIsReady = false
        wheelAlarm = false
    }

    // ========== PUBLIC PROPERTIES FOR DIRECT ACCESS (Migration from WheelDataLegacy) ==========

    // Basic telemetry - Double values
    val speed: Double get() = speedDouble
    val speedDouble: Double get() = _sessionState.value.currentSpeed
    val voltageDouble: Double get() = _sessionState.value.currentVoltage
    val currentDouble: Double get() = _sessionState.value.currentCurrent
    val temperature: Int get() = (_sessionState.value.currentTemperature * 100).toInt()
    val temperatureDouble: Double get() = _sessionState.value.currentTemperature
    val powerDouble: Double get() = _sessionState.value.currentPower
    val phaseCurrentDouble: Double get() = _sessionState.value.lastData?.phaseCurrent ?: 0.0
    val torque: Double get() = _sessionState.value.lastData?.torque ?: 0.0
    val angle: Double get() = _sessionState.value.lastData?.angle ?: 0.0
    val roll: Double get() = _sessionState.value.lastData?.roll ?: 0.0

    // Battery and voltage
    val batteryLevel: Int get() = _sessionState.value.batteryLevel
    val batteryLowestLevel: Int get() = batteryLowest
    val voltageSagDouble: Double get() = voltageSag.toDouble() / 100

    // Distances
    val distanceDouble: Double
        get() = _sessionState.value.sessionDistance
            ?: _sessionState.value.wheelDistance
            ?: 0.0
    val totalDistanceDouble: Double get() = _sessionState.value.totalDistance ?: 0.0
    val wheelDistanceDouble: Double get() = _sessionState.value.wheelDistance ?: 0.0
    val userDistanceDouble: Double
        get() {
            val totalDistance = _sessionState.value.totalDistance ?: return distanceDouble
            if (sessionStartTotalDistance == 0.0) return 0.0
            return max(totalDistance - sessionStartTotalDistance, 0.0)
        }

    // Speeds
    val topSpeedDouble: Double get() = sessionTopSpeed
    val averageSpeedDouble: Double
        get() = calculateAverageSpeed(distanceDouble, _sessionState.value.sessionRideTime)
    val averageRidingSpeedDouble: Double
        get() = calculateAverageSpeed(distanceDouble, ridingTime.toLong())
    val speedLimit: Double get() = _sessionState.value.speedLimit ?: 0.0

    // PWM
    val calculatedPwm: Double get() = _sessionState.value.pwm ?: 0.0
    val maxPwm: Double get() = sessionMaxPwm

    // Temperatures
    // Highest board temperature reached this session (previously this getter
    // returned the *current* motor/board temperature via a duplicated
    // Elvis-chain, so it never actually tracked a running maximum).
    val maxTemp: Double
        get() = _sessionState.value.sessionMaxTemperature ?: _sessionState.value.currentTemperature
    val cpuTemp: Int get() = _sessionState.value.cpuLoad ?: 0
    val imuTemp: Int get() = (_sessionState.value.lastData?.imuTemperature ?: 0.0).toInt()

    // NOTE: scaled ×100 for legacy CSV-logging compatibility (see LoggingService,
    // which divides by 100.0 again). Any new UI code should use
    // [motorTemperatureDouble] instead to avoid displaying/alarming on the
    // raw scaled integer.
    val motorTemperature: Int
        get() = ((_sessionState.value.lastData?.motorTemperature ?: 0.0) * 100).toInt()
    val motorTemperatureDouble: Double get() = _sessionState.value.motorTemperature ?: 0.0

    // Device info
    val name: String get() = _sessionState.value.deviceName
    val model: String get() = _sessionState.value.deviceModel
    val version: String get() = _sessionState.value.firmwareVersion ?: "Unknown"
    val serial: String get() = _sessionState.value.serialNumber ?: "Unknown"
    val mac: String get() = _sessionState.value.deviceAddress
    val manufacturer: String get() = _sessionState.value.deviceManufacturer
    val getMac: String get() = _sessionState.value.deviceAddress


    // Status
    val isConnected: Boolean get() = _sessionState.value.isConnected
    val fanStatus: Int get() = _sessionState.value.fanStatus ?: 0
    val chargingStatus: Int get() = _sessionState.value.chargingStatus ?: 0
    val output: Int get() = normalizePwm(calculatedPwm).roundToInt()

    val error: String get() = _sessionState.value.lastError ?: ""

    // Time
    val rideTimeString: String get() = formatRideTime(_sessionState.value.rideTime ?: 0)
    val ridingTimeString: String get() = formatRideTime(ridingTime.toLong())
    val sleepTimerString: String
        get() = _sessionState.value.autoPowerOffMinutes
            ?.takeIf { it >= 0 }
            ?.let { String.format("%02d:%02d", it / 60, it % 60) }
            ?: "00:00"

    // Mode and protocol
    val modeStr: String get() = _sessionState.value.lastData?.mode ?: ""
    val protoVer: String get() = "" // TODO: Implement protocol version
    val chargeTime: String get() = "00:00" // TODO: Implement charge time

    // Wheel type (computed from manufacturer)
    val wheelType: io.github.tuxwheel.utils.Constants.WHEEL_TYPE
        get() = wheel_type_from_string(
            manufacturer
        )

    // BMS data
    val bms: Any? get() = bmsPacks.values.firstOrNull()?.bms

    // Current limit
    val currentLimit: Double get() = _sessionState.value.lastData?.current ?: 0.0

    // Motor power
    val motorPower: Double get() = _sessionState.value.currentPower

    // Max values (for display)
    val maxCurrentDouble: Double get() = sessionMaxCurrent
    val maxPhaseCurrentDouble: Double get() = sessionMaxPhaseCurrent
    val maxPowerDouble: Double get() = sessionMaxPower

    // Stats placeholders (computed by other components)
    val remainingDistance: Double
        get() = if (batteryPerKm > 0.0) batteryLevel / batteryPerKm else 0.0
    val batteryPerKm: Double
        get() {
            if (batteryStart < 0 || distanceDouble <= 0.0) return 0.0
            return max((batteryStart - batteryLevel) / distanceDouble, 0.0)
        }
    val avgVoltagePerCell: Double
        get() = when {
            (bmsPacks[1]?.bms?.cellNum ?: 0) > 0 -> bmsPacks.getValue(1).bms.avgCell
            else -> 0.0
        }

    // Legacy adapter placeholder (flashlight etc.)
    val adapter: Any? get() = null

    // Wheel alarm (public accessor)
    val wheelAlarmState: Boolean get() = wheelAlarm

    // CPU load
    val cpuLoad: Int get() = _sessionState.value.cpuLoad ?: 0

    // Beep via wheel
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun wheelBeep() {
        sendCommand(CommandType.BEEP.name)
    }

    private fun formatRideTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    private fun calculateAverageSpeed(distanceKm: Double, durationSeconds: Long?): Double {
        val seconds = durationSeconds ?: return 0.0
        if (distanceKm <= 0.0 || seconds <= 0L) return 0.0
        return distanceKm / (seconds / 3600.0)
    }

    private fun normalizePwm(pwm: Double): Double {
        if (!pwm.isFinite()) return 0.0
        var normalized = abs(pwm)
        while (normalized > 100.0) {
            normalized /= 10.0
        }
        return normalized.coerceIn(0.0, 100.0)
    }

    // ========== CLEANUP ==========
    @androidx.annotation.RequiresPermission(allOf = [android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCleared() {
        finishWheelLookup()
        super.onCleared()
        ridingTimerControl?.cancel()
        ridingTimerControl = null

        viewModelScope.launch {
            try {
                _eucBleClient.cleanup()
                Timber.i("BleSessionViewModel cleanup completed")
            } catch (e: Exception) {
                Timber.e("Error during cleanup: %s", e.message)
            }
        }
    }

    companion object {
        private const val TAG = "BleSessionViewModel"
    }
}
