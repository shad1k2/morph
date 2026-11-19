package io.morph.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.morph.core.Protocol
import io.morph.core.ShellId

/**
 * Wi-Fi Direct bootstrap for one NFC session.
 *
 * Both phones advertise a DNS-SD service whose instance name contains the NFC
 * session id. The tag owner asks to be the group owner; the other phone (the
 * NFC reader) becomes the P2P client and dials the group owner's TCP listener.
 * The dialer does not have to be the sender: DROP/1 roles are decided by the
 * line protocol, not by who opened the socket.
 */
@SuppressLint("MissingPermission")
class WifiDirectLink(
    private val context: Context,
    private val sessionId: String,
    private val host: Boolean,
    private val localPort: Int,
    private val onState: (String) -> Unit,
    private val onDial: (hostAddress: String, port: Int) -> Unit,
    private val onGroupOwner: () -> Unit,
) {

    companion object {
        private const val TAG = "MORPH-P2P"
        private const val SERVICE_TYPE = "_morph._tcp"
    }

    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val short = sessionId.take(12)
    private val ownInstance = "MORPH-$short-" + if (host) "H" else "G"
    private val peerInstance = "MORPH-$short-" + if (host) "G" else "H"

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var peerDevice: WifiP2pDevice? = null
    private var peerPort: Int = Protocol.WIFI_PORT
    private var peerName: String? = null
    private var peerShell: ShellId = ShellId.UNKNOWN
    private var connectAttempted = false
    private var connected = false
    private var stopped = false

    fun start() {
        val wifi = manager ?: run {
            onState("unavailable")
            return
        }
        if (!NearbyPermissions.wifiDirectGranted(context)) {
            onState("permission missing")
            return
        }
        val ch = wifi.initialize(context, Looper.getMainLooper(), null) ?: run {
            onState("unavailable")
            return
        }
        channel = ch
        registerReceiver()
        addLocalService()
        installServiceListeners()
        addServiceRequest()
        onState("searching")

        // If the tag owner is slow to discover us, the reader may also start
        // the group negotiation. It is harmless when the owner already did.
        if (!host) {
            handler.postDelayed({
                if (!stopped && !connected && !connectAttempted) {
                    peerDevice?.let { connect(it, 0) }
                }
            }, 4_000)
        }
    }

    fun stop() {
        stopped = true
        connected = false
        handler.removeCallbacksAndMessages(null)
        val wifi = manager
        val ch = channel
        if (wifi != null && ch != null) {
            localService?.let { service ->
                runCatching { wifi.removeLocalService(ch, service, null) }
            }
            serviceRequest?.let { request ->
                runCatching { wifi.removeServiceRequest(ch, request, null) }
            }
            runCatching { wifi.stopPeerDiscovery(ch, null) }
            runCatching { wifi.cancelConnect(ch, null) }
            runCatching { wifi.removeGroup(ch, null) }
        }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        localService = null
        serviceRequest = null
        channel = null
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED && !stopped) {
                            onState("off")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        if (info?.isConnected == true) {
                            connected = true
                            requestConnection()
                        } else {
                            connected = false
                        }
                    }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context,
            r,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun addLocalService() {
        val wifi = manager ?: return
        val ch = channel ?: return
        val txt = mapOf(
            "sid" to sessionId,
            "role" to if (host) "host" else "guest",
            "name" to (Build.MODEL ?: "Android"),
            "shell" to ShellDetector.detect().code.toString(),
            "port" to localPort.toString(),
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(ownInstance, SERVICE_TYPE, txt)
        localService = info
        wifi.addLocalService(ch, info, actionListener("advertise"))
    }

    private fun installServiceListeners() {
        val wifi = manager ?: return
        val ch = channel ?: return
        wifi.setDnsSdResponseListeners(
            ch,
            object : WifiP2pManager.DnsSdServiceResponseListener {
                override fun onDnsSdServiceAvailable(
                    instanceName: String,
                    registrationType: String,
                    srcDevice: WifiP2pDevice,
                ) {
                    if (instanceName == peerInstance) {
                        onPeer(srcDevice, peerPort, peerName, peerShell)
                    }
                }
            },
            object : WifiP2pManager.DnsSdTxtRecordListener {
                override fun onDnsSdTxtRecordAvailable(
                    fullDomainName: String,
                    txtRecordMap: Map<String, String>,
                    srcDevice: WifiP2pDevice,
                ) {
                    if (txtRecordMap["sid"] != sessionId) return
                    peerPort = txtRecordMap["port"]?.toIntOrNull()
                        ?.takeIf { it in 1..65535 }
                        ?: Protocol.WIFI_PORT
                    peerName = txtRecordMap["name"]
                    peerShell = ShellId.from(
                        txtRecordMap["shell"]?.toIntOrNull() ?: ShellId.UNKNOWN.code
                    )
                    onPeer(srcDevice, peerPort, peerName, peerShell)
                }
            },
        )
    }

    private fun addServiceRequest() {
        val wifi = manager ?: return
        val ch = channel ?: return
        val request = WifiP2pDnsSdServiceRequest.newInstance(peerInstance, SERVICE_TYPE)
        serviceRequest = request
        wifi.addServiceRequest(ch, request, actionListener("service request"))
        wifi.discoverServices(ch, actionListener("discovery"))
    }

    private fun onPeer(
        device: WifiP2pDevice,
        port: Int,
        name: String?,
        shell: ShellId,
    ) {
        if (stopped || connected) return
        if (peerDevice?.deviceAddress != device.deviceAddress) {
            peerDevice = device
        }
        peerPort = port
        if (name != null) peerName = name
        peerShell = shell
        // The tag owner asks to be the group owner. The reader waits for the
        // invitation, then dials the group owner when the group comes up.
        if (host && !connectAttempted) {
            connect(device, WifiP2pConfig.GROUP_OWNER_INTENT_MAX)
        }
    }

    private fun connect(device: WifiP2pDevice, intent: Int) {
        val wifi = manager ?: return
        val ch = channel ?: return
        if (connectAttempted || stopped) return
        connectAttempted = true
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = intent
        }
        onState("connecting")
        wifi.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!stopped) onState("negotiating")
            }

            override fun onFailure(reason: Int) {
                connectAttempted = false
                if (!stopped) onState("connect failed ($reason)")
            }
        })
    }

    private fun requestConnection() {
        val wifi = manager ?: return
        val ch = channel ?: return
        wifi.requestConnectionInfo(ch) { info ->
            if (stopped || !info.groupFormed) return@requestConnectionInfo
            if (info.isGroupOwner) {
                onState("group ready")
                onGroupOwner()
            } else {
                val address = info.groupOwnerAddress?.hostAddress ?: run {
                    onState("group has no address")
                    return@requestConnectionInfo
                }
                onState("linked")
                onDial(address, peerPort)
            }
        }
    }

    private fun actionListener(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) {
            Log.d(TAG, "$what failed: $reason")
        }
    }
}
