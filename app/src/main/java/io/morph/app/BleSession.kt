package io.morph.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Bluetooth Low Energy control channel for one NFC session.
 *
 * The tag owner (host) is the GATT server and advertiser; the reader (guest)
 * scans for the session-specific service UUID, connects, and writes control
 * lines. The host answers with notifications. The channel carries presence and
 * role hints; the actual transfer is always done over Wi-Fi Direct / TCP.
 *
 * Service UUIDs are derived from the NFC session id, so two phones only pair
 * with the counterpart of the tap they just performed.
 */
@SuppressLint("MissingPermission")
class BleSession(
    private val context: Context,
    private val sessionId: String,
    private val host: Boolean,
    private val onState: (String) -> Unit,
    private val onMessage: (String) -> Unit,
) {

    companion object {
        private const val TAG = "MORPH-BLE"
        private val CTRL_UUID: UUID = UUID.fromString("6d6f7270-682d-6374-726c-000000000001")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun serviceUuid(sessionId: String): UUID =
            UUID.nameUUIDFromBytes("morph-ble:$sessionId".toByteArray(Charsets.UTF_8))
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val serviceUuid = serviceUuid(sessionId)
    private val handler = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var serverDevice: BluetoothDevice? = null
    private var scanning = false
    private var advertising = false
    private var stopped = false

    fun start() {
        if (!NearbyPermissions.bluetoothGranted(context)) {
            onState("Bluetooth permission missing")
            return
        }
        val bt = adapter ?: run {
            onState("Bluetooth unavailable")
            return
        }
        if (!bt.isEnabled) {
            onState("Bluetooth is off")
            return
        }
        stopped = false
        if (host) startServer(bt) else startScan(bt)
    }

    fun stop() {
        stopped = true
        scanning = false
        val wasAdvertising = advertising
        advertising = false
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (wasAdvertising) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        runCatching { server?.close() }
        server = null
        serverDevice = null
        characteristic = null
    }

    /** Send one newline-terminated control line to the peer. */
    fun send(line: String) {
        val payload = (line.trimEnd() + "\n").toByteArray(Charsets.UTF_8)
        if (host) {
            val device = serverDevice ?: return
            val ctrl = characteristic ?: return
            ctrl.value = payload
            runCatching { server?.notifyCharacteristicChanged(device, ctrl, false) }
                .onFailure { Log.w(TAG, "notify failed", it) }
        } else {
            val connection = gatt ?: return
            val ctrl = characteristic ?: return
            ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ctrl.value = payload
            runCatching { connection.writeCharacteristic(ctrl) }
                .onFailure { Log.w(TAG, "write failed", it) }
        }
    }

    // ------------------------------------------------------------------- host

    private fun startServer(bt: BluetoothAdapter) {
        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val ctrl = BluetoothGattCharacteristic(
            CTRL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ctrl.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(ctrl)
        characteristic = ctrl

        val manager = context.getSystemService(BluetoothManager::class.java)
        server = manager?.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        serverDevice = device
                        onState("Bluetooth linked")
                        // The guest may not have subscribed yet; this is just a
                        // hint. It also re-sends after the guest's HELLO below.
                        send("HELLO $sessionId host")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (serverDevice?.address == device.address) {
                            serverDevice = null
                        }
                        onState("Bluetooth disconnected")
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null,
                    )
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val ok = characteristic.uuid == CTRL_UUID
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null,
                    )
                }
                if (ok) dispatch(value)
                if (ok && String(value, Charsets.UTF_8).trimStart().startsWith("HELLO")) {
                    send("HELLO $sessionId host")
                }
            }
        })

        server?.addService(service)
        val advertiser = bt.bluetoothLeAdvertiser
        if (advertiser == null) {
            onState("Bluetooth advertising unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeDeviceName(false)
            .build()
        advertising = true
        advertiser.startAdvertising(settings, data, advertiseCallback)
        onState("Bluetooth advertising")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (!stopped) onState("Bluetooth advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (!stopped) onState("Bluetooth advertising failed ($errorCode)")
        }
    }

    // ------------------------------------------------------------------ guest

    private fun startScan(bt: BluetoothAdapter) {
        val scanner = bt.bluetoothLeScanner ?: run {
            onState("Bluetooth scanner unavailable")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        onState("Bluetooth scanning")
        handler.postDelayed({
            if (scanning && !stopped) {
                scanning = false
                runCatching { scanner.stopScan(scanCallback) }
                onState("Bluetooth not found")
            }
        }, 15_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stopped || gatt != null) return
            scanning = false
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            gatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            onState("Bluetooth connecting")
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (!stopped) onState("Bluetooth scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            connection: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connection.requestMtu(517)
                    connection.discoverServices()
                    onState("Bluetooth linked")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!stopped) onState("Bluetooth disconnected")
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = connection.getService(serviceUuid) ?: return
            val ctrl = service.getCharacteristic(CTRL_UUID) ?: return
            characteristic = ctrl
            connection.setCharacteristicNotification(ctrl, true)
            ctrl.getDescriptor(CCCD_UUID)?.let { descriptor ->
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                connection.writeDescriptor(descriptor)
            }
            onState("Bluetooth ready")
            send("HELLO $sessionId guest")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == CTRL_UUID) {
                characteristic.value?.let { dispatch(it) }
            }
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == CTRL_UUID) dispatch(value)
        }

        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) = Unit
    }

    private fun dispatch(value: ByteArray) {
        val text = String(value, Charsets.UTF_8)
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(onMessage)
    }
}
