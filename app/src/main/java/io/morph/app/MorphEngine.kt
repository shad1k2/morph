package io.morph.app

import android.content.ContentValues
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.morph.core.Capabilities
import io.morph.core.CapsFlag
import io.morph.core.Digest
import io.morph.core.DropLink
import io.morph.core.FileEntry
import io.morph.core.Manifest
import io.morph.core.Protocol
import io.morph.core.Role
import io.morph.core.ShellId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/** A discovered MORPH peer on the local network. */
data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val shell: ShellId,
)

enum class DropKind { INCOMING, OUTGOING, READY }

/** Everything both the in-app card and the system overlay need to render. */
data class DropUi(
    val kind: DropKind,
    val peerName: String,
    val shell: ShellId,
    val fileName: String = "",
    val sizeLabel: String = "",
    val phase: TransferPhase = TransferPhase.PROMPT,
    val progress: Float = 0f,
    val message: String? = null,
    val ok: Boolean = true,
    /** Optional action shown on a READY card (e.g. "SEND FILES" after NFC tap). */
    val actionLabel: String? = null,
    /** SEND for a direct-send tap, RECEIVE for the NFC role-choice card. */
    val mode: String? = null,
)

/**
 * Real DROP/1 transport over the local network:
 * NSD/mDNS discovery + TCP + X25519 handshake + AES-256-GCM frames.
 *
 * After an NFC tap both phones open a short-lived session:
 *
 *  - the tag owner runs a BLE GATT server/advertiser, the reader scans and
 *    connects (Bluetooth control channel, keyed by the NFC session id);
 *  - both phones start Wi-Fi Direct + DNS-SD; the P2P client dials the group
 *    owner, so the bulk path never depends on the old LAN address;
 *  - the dialer also speaks a tiny `MORPH/1` line protocol on TCP before
 *    DROP/1, which is what actually decides who sends and who receives.
 *
 * Whichever user presses SEND FILES becomes the sender. A simultaneous claim
 * is broken deterministically (the NFC reader wins), but in the normal case
 * the first claim is simply accepted by the other phone.
 */
object MorphEngine {

    private fun trace(message: String) { Log.d("MORPH", message) }

    private const val SERVICE_TYPE = "_morph._tcp"
    private const val TAG_NAME = "MORPH-"
    private const val CLAIM_GRACE_MS = 450L
    /** How long the control channel waits for a role claim / file choice. */
    private const val ROLE_TIMEOUT_MS = 180_000L

    /**
     * Two phones of the same model would otherwise advertise the exact same
     * mDNS instance name. Android then reports only one of them, and each
     * phone filters the other out as "self". The random suffix makes the
     * instance unique; the UI still shows the plain model name.
     */
    private val instanceTag: String = "%04X".format(Random().nextInt(0x10000))
    private val ownServiceName: String by lazy { TAG_NAME + deviceName() + "-" + instanceTag }

    /** Per-process id used only to break the extremely rare double-SEND tie. */
    private val deviceId: String by lazy {
        "%04X%04X".format(Random().nextInt(0x10000), Random().nextInt(0x10000))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val peers = mutableStateListOf<Peer>()
    var drop by mutableStateOf<DropUi?>(null)
        private set
    var status by mutableStateOf("Not started")
        private set
    var running by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var selfTest by mutableStateOf<String?>(null)
        private set
    var lastSaved by mutableStateOf<String?>(null)
        private set
    var nfcPeer by mutableStateOf<Peer?>(null)
        private set
    var nfcSessionActive by mutableStateOf(false)
        private set

    /** Local TCP port other phones connect to. */
    val port: Int get() = localPort

    private var includeSelfInDiscovery = false
    private var e2eAutoAccept = false
    private var e2eHost: String? = null
    private var selfPeer: CompletableDeferred<Peer>? = null

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pending: Incoming? = null
    private var localPort = Protocol.WIFI_PORT

    private val resolved = ConcurrentHashMap<String, Peer>()

    @Volatile
    private var session: NfcSession? = null

    private data class Incoming(
        val peerName: String,
        val shell: ShellId,
        val manifest: Manifest,
        val link: DropLink,
        val socket: Socket,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private data class Prepared(
        val file: File,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: ByteArray,
    )

    private enum class NfcRole { NONE, SENDER, RECEIVER }

    private class NfcSession(
        val id: String,
        val host: Boolean,
        var peer: Peer?,
    ) {
        val lock = Any()
        var ended = false
        var role: NfcRole = NfcRole.NONE
        var localClaimed = false
        var peerClaimed = false
        var claimAt = 0L
        var peerDeviceId: String? = null
        var ble: BleSession? = null
        var p2p: WifiDirectLink? = null
        var bleState = "starting"
        var p2pState = "starting"
        var channel: SessionChannel? = null
        var connecting = false
        var dialTarget: Pair<String, Int>? = null
        var preparedFile: Prepared? = null
        /**
         * The sender finished picking a file and told the peer with a `FILE`
         * line. Only then may either side leave the line protocol and start
         * DROP/1 on the same socket.
         */
        var go = false
        val roleReady = CompletableDeferred<NfcRole>()
        val prepared = CompletableDeferred<Prepared>()
    }

    private class SessionChannel(
        val socket: Socket,
        val input: InputStream,
        val reader: ControlReader,
        val output: OutputStream,
    ) {
        private val writeLock = Any()

        fun write(line: String) {
            synchronized(writeLock) {
                output.write((line.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    /** Reads one newline-terminated control line. Uses the socket read timeout. */
    private class ControlReader(private val input: InputStream) {
        private val line = StringBuilder()

        fun poll(): String? {
            while (true) {
                val b = try {
                    input.read()
                } catch (timeout: SocketTimeoutException) {
                    return null
                }
                if (b < 0) throw EOFException("session closed")
                when (b) {
                    '\n'.code -> {
                        val text = line.toString()
                        line.setLength(0)
                        if (text.isNotBlank()) return text
                    }
                    '\r'.code -> Unit
                    else -> {
                        line.append(b.toChar())
                        if (line.length > 2048) throw IOException("control line too long")
                    }
                }
            }
        }
    }

    private data class SessionHello(
        val session: String,
        val deviceId: String,
        val role: String,
        val name: String,
    )

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val ctx = context.applicationContext
        appContext = ctx
        running = true
        status = "Starting…"

        try {
            val socket = try {
                // SO_REUSEADDR must be set before bind, otherwise a quick
                // stop/start cycle can fail with "Address already in use".
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.WIFI_PORT))
                }
            } catch (t: IOException) {
                ServerSocket(0)
            }
            serverSocket = socket
            localPort = socket.localPort
            acquireMulticast(ctx)
            startNsd(ctx)
            scope.launch { acceptLoop(ctx, socket) }
            status = "Visible as ${deviceName()} · port $localPort"
        } catch (t: Throwable) {
            running = false
            lastError = t.message ?: t.javaClass.simpleName
            status = "Failed to start: $lastError"
        }
    }

    fun stop() {
        running = false
        val ctx = appContext
        if (ctx != null) endNfcSession(ctx, "Stopped", silent = true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        peers.clear()
        resolved.clear()
        status = "Stopped"
    }

    fun refreshDiscovery() {
        val ctx = appContext ?: return
        val manager = nsd ?: return
        val old = discovery
        discovery = null
        peers.clear()
        resolved.clear()
        // stopServiceDiscovery() is asynchronous. Starting immediately often
        // fails with FAILURE_ALREADY_ACTIVE and leaves discovery dead until
        // the process restarts, so give the framework a moment to settle.
        scope.launch {
            if (old != null) runCatching { manager.stopServiceDiscovery(old) }
            delay(400)
            if (running) startDiscovery(ctx, manager)
        }
    }

    /** Pop-up after an NFC tap with a pre-1.1 tag: the other phone is known. */
    fun showNfcReady(context: Context, peer: Peer) {
        val ctx = context.applicationContext
        nfcPeer = peer
        status = "NFC tap: ${peer.name} at ${peer.host}"
        publish(
            ctx,
            DropUi(
                kind = DropKind.READY,
                peerName = peer.name,
                shell = peer.shell,
                message = "Legacy NFC tag · ${peer.host}",
                actionLabel = "SEND FILES",
                mode = "SEND",
            )
        )
    }

    // ------------------------------------------------------------ NFC session

    /** The tag owner saw another phone read its emulated tag. */
    fun onHostTap(context: Context) {
        val ctx = context.applicationContext
        startNfcSession(ctx, NfcHandoff.hostSessionId(), host = true, peer = null)
    }

    /** The reader decoded the other phone's tag. */
    fun onGuestTap(context: Context, tap: NfcTap) {
        val ctx = context.applicationContext
        if (tap.sessionId == null) {
            // Old tags have no session id, so there is no BLE/P2P session to
            // bind. Keep the original direct-send flow for them.
            showNfcReady(ctx, tap.peer)
            return
        }
        startNfcSession(ctx, tap.sessionId, host = false, peer = tap.peer)
    }

    fun hasActiveNfcSession(): Boolean = session != null

    private fun startNfcSession(
        ctx: Context,
        id: String,
        host: Boolean,
        peer: Peer?,
    ) {
        val existing = session
        if (existing != null && existing.id == id && !existing.ended) return
        endNfcSession(ctx, "New tap", silent = true)
        start(ctx)

        val s = NfcSession(id, host, peer)
        session = s
        nfcSessionActive = true
        nfcPeer = peer
        status = "NFC tap · session ${id.take(8)}"

        publishReadyCard(ctx, s)

        // Without overlay permission a background tap would be invisible on
        // this side. Bring the app forward so the same Receive card is shown
        // in-app (the guest trampoline does the same).
        if (!MorphApp.isForeground && !Settings.canDrawOverlays(ctx)) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        s.ble = BleSession(
            context = ctx,
            sessionId = id,
            host = host,
            onState = { state -> onBleState(ctx, s, state) },
            onMessage = { line -> onBleLine(ctx, s, line) },
        ).also { it.start() }

        s.p2p = WifiDirectLink(
            context = ctx,
            sessionId = id,
            host = host,
            localPort = localPort,
            onState = { state -> onP2pState(ctx, s, state) },
            onDial = { address, port -> onP2pDial(ctx, s, address, port) },
            onGroupOwner = { onP2pOwner(ctx, s) },
        ).also { it.start() }

        // The reader knows the tag owner's LAN address, so it can try a plain
        // TCP dial while Wi-Fi Direct is still negotiating. The P2P client
        // callback later replaces that with the group owner address.
        if (!host) {
            peer?.let { s.dialTarget = it.host to it.port }
        }
        scope.launch { connectLoop(ctx, s) }
        scope.launch { expireSession(ctx, s) }
    }

    private fun publishReadyCard(ctx: Context, s: NfcSession) {
        publish(
            ctx,
            DropUi(
                kind = DropKind.READY,
                peerName = s.peer?.name ?: "Nearby phone",
                shell = s.peer?.shell ?: ShellId.UNKNOWN,
                message = sessionMessage(s, "Tap SEND FILES to send, or wait to receive"),
                actionLabel = "SEND FILES",
                mode = "RECEIVE",
            )
        )
    }

    private fun sessionMessage(s: NfcSession, extra: String): String {
        val (ble, p2p) = synchronized(s.lock) { s.bleState to s.p2pState }
        return "$extra\nBluetooth: $ble · Wi-Fi Direct: $p2p"
    }

    private fun updateSessionCard(ctx: Context, s: NfcSession, extra: String) {
        if (s.ended) return
        val current = drop ?: return
        if (current.kind != DropKind.READY) return
        publish(
            ctx,
            current.copy(
                peerName = s.peer?.name ?: current.peerName,
                message = sessionMessage(s, extra),
            )
        )
    }

    /** The local user claimed sending: flip the card into send mode. */
    private fun updateSenderCard(ctx: Context, s: NfcSession, extra: String) {
        if (s.ended) return
        val current = drop ?: return
        if (current.kind != DropKind.READY) return
        publish(
            ctx,
            current.copy(
                peerName = s.peer?.name ?: current.peerName,
                mode = "SEND",
                message = sessionMessage(s, extra),
            )
        )
    }

    private fun onBleState(ctx: Context, s: NfcSession, state: String) {
        synchronized(s.lock) { s.bleState = state }
        trace("BLE state: $state")
        updateSessionCard(ctx, s, "Tap SEND FILES to send, or wait to receive")
    }

    private fun onP2pState(ctx: Context, s: NfcSession, state: String) {
        synchronized(s.lock) { s.p2pState = state }
        trace("P2P state: $state")
        updateSessionCard(ctx, s, "Tap SEND FILES to send, or wait to receive")
    }

    private fun onP2pDial(ctx: Context, s: NfcSession, address: String, port: Int) {
        synchronized(s.lock) {
            s.dialTarget = address to port
            s.p2pState = "linked"
        }
        updateSessionCard(ctx, s, "Tap SEND FILES to send, or wait to receive")
        scope.launch {
            if (s.channel == null && !s.ended) tryConnect(ctx, s, address, port)
        }
    }

    private fun onP2pOwner(ctx: Context, s: NfcSession) {
        synchronized(s.lock) {
            s.p2pState = "group ready"
            // We are the group owner, so the other phone dials us. Do not
            // keep trying the stale LAN address from the NFC tag.
            s.dialTarget = null
        }
        updateSessionCard(ctx, s, "Tap SEND FILES to send, or wait to receive")
    }

    private fun onBleLine(ctx: Context, s: NfcSession, line: String) {
        trace("BLE RX: $line")
        handleControlLine(ctx, s, line)
    }

    /** A control line arrived over Bluetooth or the TCP control channel. */
    private fun handleControlLine(ctx: Context, s: NfcSession, line: String) {
        val parts = line.trim().split(Regex("\\s+"))
        when (parts.firstOrNull()?.uppercase()) {
            "SEND", "CLAIM" -> markPeerClaim(ctx, s)
            "ROLE" -> if (parts.getOrNull(1)?.uppercase() == "S") markPeerClaim(ctx, s)
            "FILE", "GO" -> {
                // The peer has a file ready. That also confirms it is the
                // sender, then both sides leave the line protocol and start
                // DROP/1 on this same socket.
                synchronized(s.lock) { s.go = true }
                markPeerClaim(ctx, s)
            }
            "CANCEL", "BYE" -> {
                if (!s.ended) {
                    note("The other phone closed the session")
                    // May arrive from a BLE callback; close the GATT/P2P
                    // resources outside that callback.
                    scope.launch { endSessionIfCurrent(ctx, s, "Cancelled") }
                }
            }
        }
    }

    private fun markPeerClaim(ctx: Context, s: NfcSession) {
        var lateDemote = false
        synchronized(s.lock) {
            s.peerClaimed = true
            if (s.claimAt == 0L) s.claimAt = System.currentTimeMillis()
            // Late tie-break: both sides already decided they were the sender
            // before this line arrived. The NFC reader wins, so the tag owner
            // gives up the sender role if it has not started DROP/1 yet.
            if (s.host && s.localClaimed && s.role == NfcRole.SENDER && !s.go) {
                s.role = NfcRole.RECEIVER
                s.localClaimed = false
                lateDemote = true
            }
        }
        if (lateDemote) {
            s.roleReady.complete(NfcRole.RECEIVER)
            updateReceiverCard(ctx, s)
            return
        }
        updateSessionCard(ctx, s, "Other phone wants to send")
        // No grace is needed once the peer has claimed: the first claim wins,
        // and the receiver must leave the line protocol before the sender
        // starts writing binary DROP/1 frames on the same socket.
        maybeResolve(ctx, s)
    }

    /**
     * The user pressed SEND FILES. This claims the sender role before the file
     * picker opens, so a simultaneous claim can be broken deterministically.
     */
    fun beginSend(context: Context) {
        val ctx = context.applicationContext
        val s = session
        if (s == null || s.ended) {
            val peer = nfcPeer
            if (peer == null) {
                note("Tap the other phone first")
                return
            }
            launchPicker(ctx)
            return
        }

        val canClaim: Boolean
        synchronized(s.lock) {
            // One claim/picker per session. Pressing the button twice must not
            // open two pickers or race the arbitration.
            if (s.localClaimed) return
            canClaim = s.role != NfcRole.RECEIVER && !s.peerClaimed
            if (canClaim) {
                s.localClaimed = true
                s.claimAt = System.currentTimeMillis()
            }
        }

        if (!canClaim) {
            updateSessionCard(ctx, s, "The other phone is sending")
            updateReceiverCard(ctx, s)
            return
        }

        s.channel?.write("SEND $deviceId")
        s.ble?.send("SEND $deviceId")
        updateSenderCard(ctx, s, "Send requested")
        scope.launch {
            delay(CLAIM_GRACE_MS)
            maybeResolve(ctx, s)
            val role = withTimeoutOrNull(1_200) { s.roleReady.await() }
            when (role) {
                NfcRole.RECEIVER -> {
                    updateSessionCard(ctx, s, "The other phone is sending")
                    updateReceiverCard(ctx, s)
                }
                else -> launchPicker(ctx)
            }
        }
    }

    private fun launchPicker(ctx: Context) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, FilePickActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                note("Cannot open the file picker: ${it.message}")
            }
        }
    }

    /** The file picker returned. Send through the active session if there is one. */
    fun sendSession(context: Context, uri: Uri) {
        val ctx = context.applicationContext
        val s = session
        if (s == null || s.ended) {
            val peer = nfcPeer
            if (peer != null) send(context, peer, uri)
            return
        }
        scope.launch {
            try {
                val resolved = withTimeoutOrNull(10_000) { s.roleReady.await() }
                val role = resolved ?: synchronized(s.lock) { s.role }
                if (role == NfcRole.NONE) {
                    // Still no channel/role: release the claim so the user can
                    // retry instead of being stuck with a dead SEND button.
                    synchronized(s.lock) {
                        if (s.role == NfcRole.NONE && !s.peerClaimed) {
                            s.localClaimed = false
                            s.claimAt = 0L
                        }
                    }
                    note("No connection to the other phone yet")
                    publishReadyCard(ctx, s)
                    return@launch
                }
                if (role != NfcRole.SENDER) {
                    note("The other phone is sending")
                    return@launch
                }
                val prepared = prepare(ctx, uri)
                // A simultaneous claim can be resolved while the picker is
                // open. Re-check before handing the file to the transport.
                val roleNow = synchronized(s.lock) { s.role }
                if (roleNow != NfcRole.SENDER) {
                    prepared.file.delete()
                    note("The other phone is sending")
                    return@launch
                }
                if (s.ended) {
                    prepared.file.delete()
                    return@launch
                }
                s.preparedFile = prepared
                s.prepared.complete(prepared)
            } catch (t: Throwable) {
                val message = friendly(t)
                lastError = message
                note(message)
                updateSessionCard(ctx, s, message)
                s.channel?.write("CANCEL")
                s.ble?.send("CANCEL")
                endSessionIfCurrent(ctx, s, message)
            }
        }
    }

    /** The picker was dismissed; release a sender claim instead of hanging. */
    fun cancelSend() {
        val ctx = appContext ?: return
        val s = session ?: return
        if (s.ended) return
        // If the other phone already took the sender role, dismissing our
        // picker must not kill its transfer.
        val receiver = synchronized(s.lock) {
            if (s.role == NfcRole.RECEIVER) true else {
                s.localClaimed = false
                s.claimAt = 0L
                false
            }
        }
        if (receiver) {
            updateReceiverCard(ctx, s)
            return
        }
        s.channel?.write("CANCEL")
        s.ble?.send("CANCEL")
        endSessionIfCurrent(ctx, s, "Cancelled")
    }

    private fun maybeResolve(ctx: Context, s: NfcSession) {
        val decision: NfcRole? = synchronized(s.lock) {
            if (s.role != NfcRole.NONE || s.ended) return
            if (!s.localClaimed && !s.peerClaimed) return
            if (s.claimAt == 0L) s.claimAt = System.currentTimeMillis()
            // A local-only claim gets a short window to catch a simultaneous
            // peer claim. Anything involving the peer is final immediately.
            if (s.localClaimed && !s.peerClaimed &&
                System.currentTimeMillis() - s.claimAt < CLAIM_GRACE_MS
            ) {
                return
            }

            val localWins = when {
                s.localClaimed && !s.peerClaimed -> true
                !s.localClaimed && s.peerClaimed -> false
                // Both pressed SEND at the same time: the NFC reader wins.
                else -> !s.host
            }
            val chosen = if (localWins) NfcRole.SENDER else NfcRole.RECEIVER
            s.role = chosen
            s.roleReady.complete(chosen)
            chosen
        }

        when (decision) {
            NfcRole.SENDER -> {
                s.channel?.write("SEND $deviceId")
                s.ble?.send("SEND $deviceId")
                updateSenderCard(ctx, s, "Choose a file to send")
            }
            NfcRole.RECEIVER -> {
                s.channel?.write("ROLE R $deviceId")
                s.ble?.send("ROLE R")
                updateSessionCard(ctx, s, "The other phone is sending")
                updateReceiverCard(ctx, s)
            }
            else -> Unit
        }
    }

    private fun updateReceiverCard(ctx: Context, s: NfcSession) {
        val current = drop ?: return
        if (current.kind != DropKind.READY) return
        publish(
            ctx,
            current.copy(
                mode = "RECEIVE",
                actionLabel = null,
                message = sessionMessage(
                    s,
                    "Waiting for the incoming file from ${s.peer?.name ?: "the other phone"}",
                ),
            )
        )
    }

    private fun helloLine(s: NfcSession): String {
        val role = synchronized(s.lock) {
            when {
                s.role == NfcRole.SENDER -> "S"
                s.role == NfcRole.RECEIVER -> "R"
                s.localClaimed -> "S"
                else -> "-"
            }
        }
        val name = deviceName().replace(Regex("\\s+"), "_")
        return "MORPH/1 HELLO ${s.id} $deviceId $role $name"
    }

    private fun parseHello(line: String): SessionHello? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null
        if (parts[0] != "MORPH/1" || parts[1] != "HELLO") return null
        return SessionHello(
            session = parts[2],
            deviceId = parts[3],
            role = parts[4],
            name = parts.getOrNull(5) ?: "Nearby phone",
        )
    }

    private fun writeLine(output: OutputStream, line: String) {
        output.write((line.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readControlLine(reader: ControlReader, timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val line = reader.poll()
            if (line != null) return line
        }
        return null
    }

    private suspend fun connectLoop(ctx: Context, s: NfcSession) {
        val deadline = System.currentTimeMillis() + 60_000
        var lastAttempt = 0L
        while (!s.ended && s.channel == null) {
            val target = synchronized(s.lock) { s.dialTarget }
            val now = System.currentTimeMillis()
            if (target != null && now - lastAttempt >= 1_500) {
                lastAttempt = now
                if (tryConnect(ctx, s, target.first, target.second)) return
            }
            if (now > deadline) return
            delay(350)
        }
    }

    private suspend fun tryConnect(
        ctx: Context,
        s: NfcSession,
        host: String,
        port: Int,
    ): Boolean {
        synchronized(s.lock) {
            if (s.ended || s.channel != null || s.connecting) return false
            s.connecting = true
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 6_000)
            socket.soTimeout = 8_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            writeLine(output, helloLine(s))

            val reader = ControlReader(input)
            val line = readControlLine(reader, 8_000)
                ?: throw IOException("no session hello")
            val hello = parseHello(line)
                ?: throw IOException("bad session hello")

            synchronized(s.lock) { s.connecting = false }
            trace("SESSION: dialed $host:$port")
            attachChannel(ctx, s, socket, input, reader, output, hello)
            return true
        } catch (t: Throwable) {
            synchronized(s.lock) { s.connecting = false }
            runCatching { socket.close() }
            trace("SESSION: dial $host:$port failed: ${t.message}")
            return false
        }
    }

    private suspend fun handleSessionIncoming(
        ctx: Context,
        socket: Socket,
        input: InputStream,
    ) {
        try {
            socket.soTimeout = 8_000
            val reader = ControlReader(input)
            val line = readControlLine(reader, 8_000) ?: run {
                socket.close()
                return
            }
            val hello = parseHello(line)
            val s = session
            if (hello == null || s == null || s.ended || s.id != hello.session) {
                runCatching {
                    writeLine(socket.getOutputStream(), "MORPH/1 BUSY")
                }
                socket.close()
                return
            }
            trace("SESSION: accepted ${hello.deviceId} (${hello.role})")

            val output = socket.getOutputStream()
            writeLine(output, helloLine(s))
            attachChannel(ctx, s, socket, input, reader, output, hello)
        } catch (t: Throwable) {
            trace("SESSION: accept failed: ${t.message}")
            runCatching { socket.close() }
        }
    }

    private suspend fun attachChannel(
        ctx: Context,
        s: NfcSession,
        socket: Socket,
        input: InputStream,
        reader: ControlReader,
        output: OutputStream,
        hello: SessionHello,
    ) {
        val channel = SessionChannel(socket, input, reader, output)
        var demoted = false
        synchronized(s.lock) {
            if (s.ended || s.channel != null) {
                runCatching { socket.close() }
                return
            }
            s.channel = channel
            if (s.peerDeviceId == null) s.peerDeviceId = hello.deviceId
            if (hello.role.uppercase() == "S") {
                s.peerClaimed = true
                if (s.claimAt == 0L) s.claimAt = System.currentTimeMillis()
                // Both sides had already decided they are the sender before
                // the socket came up. The reader wins the tie, so the tag
                // owner quietly turns into the receiver.
                if (s.host && s.localClaimed && s.role == NfcRole.SENDER && !s.go) {
                    s.role = NfcRole.RECEIVER
                    s.localClaimed = false
                    demoted = true
                }
            }
            val remote = socket.inetAddress?.hostAddress
            s.peer = s.peer?.copy(name = hello.name) ?: Peer(
                id = "nfc:${hello.deviceId}",
                name = hello.name,
                host = remote ?: "",
                port = localPort,
                shell = ShellId.UNKNOWN,
            )
        }
        trace("SESSION: channel ready with ${hello.name}")
        if (demoted) {
            s.roleReady.complete(NfcRole.RECEIVER)
            updateReceiverCard(ctx, s)
        } else {
            updateSessionCard(ctx, s, "Connected · choose who sends")
        }
        maybeResolve(ctx, s)
        runControl(ctx, s)
    }

    private suspend fun runControl(ctx: Context, s: NfcSession) {
        val channel = s.channel ?: return
        try {
            channel.socket.soTimeout = 250
            val deadline = System.currentTimeMillis() + ROLE_TIMEOUT_MS
            while (!s.ended && System.currentTimeMillis() < deadline) {
                maybeResolve(ctx, s)

                var announceFile = false
                val ready = synchronized(s.lock) {
                    val already = s.go
                    if (s.role == NfcRole.SENDER && s.prepared.isCompleted && !already) {
                        s.go = true
                        announceFile = true
                    }
                    already || announceFile
                }
                // The sender is the only side that writes FILE; the receiver
                // waits for it, so an abandoned picker can still send CANCEL.
                if (announceFile) channel.write("FILE")
                if (ready) break

                val line = channel.reader.poll()
                if (line != null) handleControlLine(ctx, s, line)
            }

            if (s.ended) return
            val (role, ready) = synchronized(s.lock) { s.role to s.go }
            if (!ready) {
                note("NFC session timed out")
                endSessionIfCurrent(ctx, s, "Timed out", silent = true)
                return
            }

            channel.socket.soTimeout = 60_000
            when (role) {
                NfcRole.SENDER -> {
                    // The file is already prepared here (FILE is only sent
                    // once the picker returned); the timeout is a guard.
                    val prepared = withTimeoutOrNull(5_000) { s.prepared.await() }
                        ?: return
                    if (synchronized(s.lock) { s.role } != NfcRole.SENDER) return
                    sendOverChannel(ctx, s, channel, prepared)
                }
                NfcRole.RECEIVER -> receiveOverChannel(ctx, s, channel)
                else -> Unit
            }
        } catch (t: Throwable) {
            if (!s.ended) {
                Log.w("MORPH", "session failed", t)
                val message = friendly(t)
                lastError = message
                status = message
                publish(
                    ctx,
                    drop?.copy(
                        phase = TransferPhase.DONE,
                        message = message,
                        ok = false,
                        actionLabel = null,
                    )
                )
                delay(2_600)
                if (!s.ended) endSessionIfCurrent(ctx, s, message)
            }
        } finally {
            runCatching { channel.socket.close() }
            synchronized(s.lock) { if (s.channel === channel) s.channel = null }
        }
    }

    private suspend fun sendOverChannel(
        ctx: Context,
        s: NfcSession,
        channel: SessionChannel,
        prepared: Prepared,
    ) {
        trace("SESSION: sending ${prepared.name} (${prepared.size} B)")
        publish(
            ctx,
            DropUi(
                kind = DropKind.OUTGOING,
                peerName = s.peer?.name ?: "Nearby phone",
                shell = s.peer?.shell ?: ShellId.UNKNOWN,
                fileName = prepared.name,
                sizeLabel = formatBytes(prepared.size),
                phase = TransferPhase.RUNNING,
            )
        )

        val link = DropLink(channel.input, channel.output, Role.SENDER)
        link.handshake(localCapabilities())

        val entry = FileEntry(1u, prepared.size, prepared.mime, prepared.name, prepared.sha256)
        val manifest = Manifest(listOf(entry))
        link.sendOffer(manifest)
        if (!link.awaitAccept()) throw IOException("declined by ${s.peer?.name ?: "peer"}")

        prepared.file.inputStream().use { input ->
            link.sendFile(entry, input) { sent ->
                progress(sent.toFloat() / prepared.size.coerceAtLeast(1))
            }
        }
        link.sendDone(manifest.manifestHash())
        if (!link.awaitAck()) throw IOException("receiver rejected the file")

        publish(ctx, drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "Delivered"))
        status = "Sent ${prepared.name} to ${s.peer?.name ?: "nearby phone"}"
        delay(1_600)
        prepared.file.delete()
        if (drop?.kind == DropKind.OUTGOING) publish(ctx, null)
        endSessionIfCurrent(ctx, s, "Transfer complete", silent = true)
    }

    private suspend fun receiveOverChannel(
        ctx: Context,
        s: NfcSession,
        channel: SessionChannel,
    ) {
        val peerName = s.peer?.name ?: "Nearby phone"
        publish(
            ctx,
            DropUi(
                kind = DropKind.INCOMING,
                peerName = peerName,
                shell = s.peer?.shell ?: ShellId.UNKNOWN,
                phase = TransferPhase.RUNNING,
                message = "Receiving…",
            )
        )

        var pendingDownload: Uri? = null
        try {
            val link = DropLink(channel.input, channel.output, Role.RECEIVER)
            link.handshake(localCapabilities())
            val manifest = link.receiveOffer()
            val file = manifest.files.firstOrNull() ?: run {
                link.decline()
                throw IOException("no file offered")
            }
            val peerShell = link.peerCapabilities?.shellId ?: s.peer?.shell ?: ShellId.UNKNOWN

            publish(
                ctx,
                DropUi(
                    kind = DropKind.INCOMING,
                    peerName = peerName,
                    shell = peerShell,
                    fileName = file.name,
                    sizeLabel = formatBytes(file.size),
                    phase = TransferPhase.RUNNING,
                )
            )
            status = "Incoming ${file.name} from $peerName"

            // The receiver role was already chosen by not pressing SEND FILES,
            // so the session path auto-accepts immediately.
            link.accept()
            val target = createDownload(ctx, file)
            pendingDownload = target
            val sink: OutputStream = ctx.contentResolver.openOutputStream(target)
                ?: throw IOException("cannot open Downloads")

            val hash = sink.use { out ->
                link.receiveFile(file, out) { written ->
                    progress(written.toFloat() / file.size.coerceAtLeast(1))
                }
            }
            if (!hash.contentEquals(file.sha256)) {
                link.abort()
                throw IOException("hash mismatch")
            }
            if (!link.verifyDone(manifest.manifestHash())) {
                link.abort()
                throw IOException("manifest mismatch")
            }

            finalizeDownload(ctx, target)
            pendingDownload = null
            link.ack()

            lastSaved = "${file.name} · ${formatBytes(file.size)}"
            publish(
                ctx,
                drop?.copy(
                    phase = TransferPhase.DONE,
                    progress = 1f,
                    message = "Saved to Downloads/MORPH",
                )
            )
            status = "Saved ${file.name} to Downloads/MORPH"
            delay(1_800)
            if (drop?.kind == DropKind.INCOMING) publish(ctx, null)
            endSessionIfCurrent(ctx, s, "Transfer complete", silent = true)
        } catch (t: Throwable) {
            pendingDownload?.let { uri ->
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            }
            throw t
        }
    }

    private suspend fun expireSession(ctx: Context, s: NfcSession) {
        delay(45_000)
        if (!s.ended && s.channel == null && drop?.kind == DropKind.READY) {
            note("NFC session timed out")
            endSessionIfCurrent(ctx, s, "Timed out")
            return
        }
        // If a control channel exists but nobody claimed a role, the session
        // is just sitting there. Give the users a minute to press SEND FILES,
        // then release Bluetooth and Wi-Fi Direct.
        delay(45_000)
        val role = synchronized(s.lock) { s.role }
        if (!s.ended && role == NfcRole.NONE) {
            endSessionIfCurrent(ctx, s, "Timed out")
            return
        }
        delay(60_000)
        if (!s.ended && s.channel == null) {
            endSessionIfCurrent(ctx, s, "Timed out")
        }
    }

    private fun endNfcSession(ctx: Context?, reason: String, silent: Boolean = false) {
        val s = session ?: return
        endSession(ctx, s, reason, silent)
    }

    /** End [s] only if it is still the current session. */
    private fun endSessionIfCurrent(
        ctx: Context?,
        s: NfcSession,
        reason: String,
        silent: Boolean = false,
    ) {
        if (session !== s) return
        endSession(ctx, s, reason, silent)
    }

    private fun endSession(
        ctx: Context?,
        s: NfcSession,
        reason: String,
        silent: Boolean,
    ) {
        synchronized(s.lock) {
            if (s.ended) return
            s.ended = true
        }
        session = null
        nfcSessionActive = false

        s.roleReady.cancel()
        s.prepared.cancel()
        runCatching { s.preparedFile?.file?.delete() }
        // Still in the line protocol? Tell the peer this side is gone so its
        // Receive card does not sit there until the timeout.
        val inControl = synchronized(s.lock) { s.role == NfcRole.NONE && !s.go }
        if (inControl) {
            runCatching { s.channel?.write("BYE") }
            runCatching { s.ble?.send("BYE") }
        }
        runCatching { s.channel?.socket?.close() }
        runCatching { s.ble?.stop() }
        runCatching { s.p2p?.stop() }
        if (s.host) NfcHandoff.rotateHostSession()

        if (!silent) {
            status = reason
            if (drop?.kind == DropKind.READY) {
                ctx?.let { publish(it, null) }
            }
        }
        nfcPeer = null
    }

    // ---------------------------------------------------------------- ready

    /** Ready card shown when the QS tile is tapped. */
    fun showReady() {
        val ctx = appContext ?: return
        publish(
            ctx,
            DropUi(
                kind = DropKind.READY,
                peerName = deviceName(),
                shell = ThemePrefs.selected(ctx),
                message = "Listening on port $localPort · same Wi-Fi as the other phone",
                mode = "RECEIVE",
            )
        )
        scope.launch {
            delay(30_000)
            if (drop?.kind == DropKind.READY && !hasActiveNfcSession()) publish(ctx, null)
        }
    }

    /** Sets a human-readable hint shown on the home screen. */
    fun note(message: String) {
        status = message
    }

    fun acceptIncoming() {
        pending?.decision?.complete(true)
    }

    fun declineIncoming() {
        pending?.decision?.complete(false)
    }

    fun dismiss() {
        pending?.decision?.complete(false)
        val ctx = appContext
        if (ctx != null) endNfcSession(ctx, "Dismissed", silent = true)
        drop = null
    }

    // ---------------------------------------------------------------- outgoing

    fun send(context: Context, peer: Peer, uri: Uri) {
        val ctx = context.applicationContext
        scope.launch {
            var prepared: Prepared? = null
            try {
                prepared = prepare(ctx, uri)
                publish(
                    ctx,
                    DropUi(
                        kind = DropKind.OUTGOING,
                        peerName = peer.name,
                        shell = peer.shell,
                        fileName = prepared.name,
                        sizeLabel = formatBytes(prepared.size),
                        phase = TransferPhase.RUNNING,
                    )
                )

                transfer(peer.name, peer.host, peer.port, prepared)

                publish(
                    ctx,
                    drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "Delivered")
                )
                status = "Sent ${prepared.name} to ${peer.name}"
                delay(1600)
                if (drop?.kind == DropKind.OUTGOING) publish(ctx, null)
            } catch (t: Throwable) {
                Log.w("MORPH", "OUT transfer failed", t)
                val message = friendly(t)
                lastError = message
                status = message
                publish(ctx, drop?.copy(phase = TransferPhase.DONE, message = message, ok = false))
                delay(2600)
                if (drop?.kind == DropKind.OUTGOING) publish(ctx, null)
            } finally {
                prepared?.file?.delete()
            }
        }
    }

    /** One SENDER-side DROP/1 conversation over a real TCP socket. */
    private fun transfer(
        peerName: String,
        host: String,
        port: Int,
        prepared: Prepared,
    ) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 8_000)
            socket.soTimeout = 30_000
            trace("OUT: connected to $host:$port")
            val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.SENDER)
            link.handshake(localCapabilities())

            val entry = FileEntry(1u, prepared.size, prepared.mime, prepared.name, prepared.sha256)
            val manifest = Manifest(listOf(entry))
            link.sendOffer(manifest)

            trace("OUT: offer sent, awaiting accept")
            if (!link.awaitAccept()) throw IOException("declined by $peerName")
            trace("OUT: accepted, sending ${prepared.size} bytes")

            prepared.file.inputStream().use { input ->
                link.sendFile(entry, input) { sent ->
                    progress(sent.toFloat() / prepared.size.coerceAtLeast(1))
                }
            }

            trace("OUT: chunks sent")
            link.sendDone(manifest.manifestHash())
            trace("OUT: DONE sent, awaiting ACK")
            if (!link.awaitAck()) throw IOException("receiver rejected the file")
            trace("OUT: ack received")
        }
    }

    /**
     * Full local round trip used by the diagnostics button: advertise over mDNS,
     * discover and resolve our own service, then send a generated file to the
     * receiver through the real TCP + crypto + MediaStore path.
     */
    fun runE2E(context: Context) {
        val ctx = context.applicationContext
        start(ctx)
        scope.launch {
            var prepared: Prepared? = null
            try {
                selfTest = "E2E: advertising and discovering…"
                lastSaved = null
                val waiter = CompletableDeferred<Peer>()
                selfPeer = waiter
                includeSelfInDiscovery = true
                refreshDiscovery()

                val peer = withTimeoutOrNull(15_000) { waiter.await() }
                if (peer == null) {
                    selfTest = "E2E failed: mDNS did not resolve this device"
                    return@launch
                }

                trace("E2E: resolved ${peer.host}:${peer.port}")
                selfTest = "E2E: resolved ${peer.host}:${peer.port}, sending…"

                val size = 256 * 1024
                val bytes = ByteArray(size).also { Random(21).nextBytes(it) }
                val name = "morph-e2e.bin"
                val temp = File(ctx.cacheDir, "e2e-${System.currentTimeMillis()}.bin")
                temp.writeBytes(bytes)
                prepared = Prepared(temp, name, "application/octet-stream", size.toLong(), Digest.sha256(bytes))

                e2eAutoAccept = true
                e2eHost = peer.host
                publish(
                    ctx,
                    DropUi(
                        kind = DropKind.OUTGOING,
                        peerName = deviceName() + " (self)",
                        shell = ThemePrefs.selected(ctx),
                        fileName = name,
                        sizeLabel = formatBytes(size.toLong()),
                        phase = TransferPhase.RUNNING,
                    )
                )

                transfer(peer.name, peer.host, peer.port, prepared)

                trace("E2E: transfer returned")
                val saved = withTimeoutOrNull(8_000) {
                    while (lastSaved == null) delay(100)
                    lastSaved
                }

                trace("E2E: saved=$saved")
                selfTest = if (saved != null) {
                    "E2E OK · mDNS + TCP + AES-256-GCM · saved ${saved}"
                } else {
                    "E2E: sent, but no saved-file confirmation"
                }
                status = "E2E transfer complete"
                publish(ctx, drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "E2E OK"))
                delay(1800)
                publish(ctx, null)
            } catch (t: Throwable) {
                val message = friendly(t)
                lastError = message
                selfTest = "E2E failed: $message"
                publish(ctx, drop?.copy(phase = TransferPhase.DONE, message = message, ok = false))
                delay(2200)
                publish(ctx, null)
            } finally {
                prepared?.file?.delete()
                e2eAutoAccept = false
                e2eHost = null
                includeSelfInDiscovery = false
                selfPeer = null
            }
        }
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun acceptLoop(ctx: Context, server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                if (!running) break else continue
            }
            scope.launch { handleIncoming(ctx, socket) }
        }
    }

    private suspend fun handleIncoming(ctx: Context, socket: Socket) {
        try {
            socket.soTimeout = 30_000
            // Session connections start with the ASCII line "MORPH/1". Legacy
            // DROP/1 connections start with the binary magic "DROP". Peek at
            // four bytes and put them back for the chosen handler.
            val pushback = PushbackInputStream(socket.getInputStream(), 8)
            val magic = ByteArray(4)
            var read = 0
            while (read < magic.size) {
                val n = pushback.read(magic, read, magic.size - read)
                if (n < 0) break
                read += n
            }
            when {
                read == 4 && magic.contentEquals(Protocol.MAGIC) -> {
                    pushback.unread(magic, 0, 4)
                    handleLegacyIncoming(ctx, socket, pushback)
                }
                read > 0 -> {
                    pushback.unread(magic, 0, read)
                    handleSessionIncoming(ctx, socket, pushback)
                }
                else -> socket.close()
            }
        } catch (t: Throwable) {
            Log.w("MORPH", "IN transfer failed", t)
            runCatching { socket.close() }
        }
    }

    private suspend fun handleLegacyIncoming(
        ctx: Context,
        socket: Socket,
        input: InputStream,
    ) {
        // A MediaStore entry created with IS_PENDING=1 stays invisible until it
        // is finalized; if the transfer dies halfway it must be removed.
        var pendingDownload: Uri? = null
        try {
            socket.soTimeout = 30_000
            val link = DropLink(input, socket.getOutputStream(), Role.RECEIVER)
            link.handshake(localCapabilities())
            val manifest = link.receiveOffer()
            trace("IN: offer ${manifest.files.size} file(s)")
            val file = manifest.files.firstOrNull() ?: run {
                link.decline()
                return
            }

            val peerCaps = link.peerCapabilities
            val peerShell = peerCaps?.shellId ?: ShellId.UNKNOWN
            val host = socket.inetAddress?.hostAddress
            val peerName = resolved.values.firstOrNull { it.host == host }?.name
                ?: host
                ?: "Nearby device"
            if (pending != null) {
                link.decline()
                return
            }
            trace("IN: peer=$peerName host=$host e2eHost=$e2eHost auto=$e2eAutoAccept")
            val incoming = Incoming(peerName, peerShell, manifest, link, socket)
            pending = incoming

            publish(
                ctx,
                DropUi(
                    kind = DropKind.INCOMING,
                    peerName = peerName,
                    shell = peerShell,
                    fileName = file.name,
                    sizeLabel = formatBytes(file.size),
                    phase = TransferPhase.PROMPT,
                )
            )
            status = "Incoming ${file.name} from $peerName"

            if (e2eAutoAccept &&
                (socket.inetAddress?.hostAddress == e2eHost ||
                    socket.inetAddress?.isLoopbackAddress == true)
            ) {
                incoming.decision.complete(true)
            }

            trace("IN: waiting decision")
            val accepted = withTimeoutOrNull(Protocol.ACCEPT_TIMEOUT_MS) {
                incoming.decision.await()
            } ?: false

            if (!accepted) {
                link.decline()
                status = "Declined ${file.name}"
                publish(ctx, null)
                return
            }

            // The sender is blocked in awaitAccept(): actually send the frame.
            link.accept()
            trace("IN: ACCEPT frame sent")

            trace("IN: accepted=$accepted")
            publish(ctx, drop?.copy(phase = TransferPhase.RUNNING, progress = 0f))

            trace("IN: creating Downloads entry")
            val target = createDownload(ctx, file)
            pendingDownload = target
            val sink: OutputStream = ctx.contentResolver.openOutputStream(target)
                ?: throw IOException("cannot open Downloads")

            trace("IN: sink open, reading chunks")
            val hash = sink.use { out ->
                link.receiveFile(file, out) { written ->
                    progress(written.toFloat() / file.size.coerceAtLeast(1))
                }
            }
            trace("IN: chunks done, hash=${hash.joinToString("") { "%02X".format(it) }.take(12)}")

            // Verify before publishing the entry, otherwise a corrupt file
            // becomes visible in Downloads.
            if (!hash.contentEquals(file.sha256)) {
                link.abort()
                throw IOException("hash mismatch")
            }
            if (!link.verifyDone(manifest.manifestHash())) {
                link.abort()
                throw IOException("manifest mismatch")
            }
            trace("IN: DONE verified")
            finalizeDownload(ctx, target)
            pendingDownload = null
            link.ack()
            trace("IN: ACK written")

            lastSaved = "${file.name} · ${formatBytes(file.size)}"
            publish(ctx, drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "Saved to Downloads/MORPH"))
            status = "Saved ${file.name} to Downloads/MORPH"
            delay(1800)
            publish(ctx, null)
        } catch (t: Throwable) {
            pendingDownload?.let { uri ->
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            }
            Log.w("MORPH", "IN transfer failed", t)
            val message = friendly(t)
            lastError = message
            status = message
            publish(ctx, drop?.copy(phase = TransferPhase.DONE, message = message, ok = false))
            delay(2600)
            publish(ctx, null)
        } finally {
            pending = null
            runCatching { socket.close() }
        }
    }

    private fun publish(ctx: Context, ui: DropUi?) {
        drop = ui
        when {
            ui == null -> runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
            }

            !MorphApp.isForeground -> runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
            }
            // When a MORPH activity is in front, the in-app card is enough.
            // Never send HIDE here: it races with an explicit SHOW from the
            // NFC trampoline and can close the card immediately.
            else -> Unit
        }
    }

    private fun progress(fraction: Float) {
        val current = drop ?: return
        val next = fraction.coerceIn(0f, 1f)
        if (next - current.progress < 0.01f && next < 1f) return
        drop = current.copy(progress = next)
    }

    // --------------------------------------------------------------------- NSD

    private fun startNsd(ctx: Context) {
        val manager = ctx.getSystemService(NsdManager::class.java) ?: return
        nsd = manager

        val info = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = SERVICE_TYPE
            port = localPort
            setAttribute("shell", ShellDetector.detect().code.toString())
            setAttribute("model", Build.MODEL)
        }

        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                status = "Visible as ${serviceInfo.serviceName}"
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                lastError = "NSD registration failed ($errorCode)"
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }

        startDiscovery(ctx, manager)
    }

    private fun startDiscovery(ctx: Context, manager: NsdManager) {
        var retries = 0
        // Assigned right below; the callbacks only read it after that.
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                status = "Scanning for nearby phones…"
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (!name.startsWith(TAG_NAME)) return
                if (name == ownServiceName && !includeSelfInDiscovery) return
                resolve(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                resolved.remove(name)
                peers.removeAll { it.id == name }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                lastError = "Discovery failed ($errorCode)"
                // A previous stop is still in flight; retry once it settles.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE &&
                    discovery === listener && retries < 3
                ) {
                    retries++
                    scope.launch {
                        delay(600)
                        if (running && discovery === listener) startDiscovery(ctx, manager)
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // resolveService is deprecated in API 34 in favour of registerServiceInfoCallback,
    // but the callback API is unavailable on our minSdk and the old call still works.
    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val name = serviceInfo.serviceName ?: return
                val shellCode = serviceInfo.attributes["shell"]
                    ?.toString(Charsets.UTF_8)
                    ?.toIntOrNull()
                    ?: ShellId.UNKNOWN.code
                val displayName = serviceInfo.attributes["model"]
                    ?.toString(Charsets.UTF_8)
                    ?.takeIf { it.isNotBlank() }
                    ?: name.removePrefix(TAG_NAME).substringBeforeLast('-')
                val peer = Peer(
                    id = name,
                    name = displayName,
                    host = host,
                    port = serviceInfo.port,
                    shell = ShellId.from(shellCode),
                )
                resolved[name] = peer
                val self = name == ownServiceName
                val index = peers.indexOfFirst { it.id == name }
                if (index >= 0) peers[index] = peer else if (!self) peers.add(peer)
                if (self) {
                    selfPeer?.complete(peer)
                }
            }
        })
    }

    // ------------------------------------------------------------------ files

    private fun prepare(ctx: Context, uri: Uri): Prepared {
        val resolver = ctx.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        val mime = resolver.getType(uri) ?: "application/octet-stream"

        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val temp = File(ctx.cacheDir, "out-${System.nanoTime()}-$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } ?: throw IOException("cannot read the selected file")

        if (size >= 0 && size != total) {
            // Provider lied about the size; trust the bytes we copied.
            size = total
        }
        return Prepared(temp, name, mime, total, digest.digest())
    }

    private fun createDownload(ctx: Context, file: FileEntry): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(safeDisplayName(file.name)))
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime.ifBlank { "application/octet-stream" })
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/MORPH",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("cannot create a Downloads entry")
    }

    private fun finalizeDownload(ctx: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        runCatching { ctx.contentResolver.update(uri, values, null, null) }
    }

    /**
     * The sender controls the offered file name. Never let it escape
     * Downloads/MORPH or smuggle path separators / control characters into
     * MediaStore.
     */
    private fun safeDisplayName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .trim()
            .trimEnd('.')
        val base = cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file"
        if (base.length <= 120) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0 && base.length - dot <= 16) {
            base.take(120 - (base.length - dot)) + base.substring(dot)
        } else {
            base.take(120)
        }
    }

    private fun uniqueName(name: String): String {
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + "-" + stamp + name.substring(dot)
        } else {
            "$name-$stamp"
        }
    }

    // ------------------------------------------------------------- self-test

    fun runSelfTest() {
        scope.launch {
            withContext(Dispatchers.Main) { selfTest = "Running loopback…" }
            val server = try {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { selfTest = "Self-test failed: ${t.message}" }
                return@launch
            }
            try {
                val payload = ByteArray(512 * 1024).also { Random(11).nextBytes(it) }
                val payloadHash = Digest.sha256(payload)
                val received = ByteArrayOutputStream()

                val receiver = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.RECEIVER)
                        link.handshake(localCapabilities())
                        val manifest = link.receiveOffer()
                        trace("IN: offer ${manifest.files.size} file(s)")
                        link.accept()
                        val hash = link.receiveFile(manifest.files[0], received)
                        link.verifyDone(manifest.manifestHash())
                        link.ack()
                        hash
                    }
                }

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 5_000)
                    socket.soTimeout = 15_000
                    trace("OUT: connected to 127.0.0.1:${server.localPort}")
                    val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.SENDER)
                    link.handshake(localCapabilities())
                    val entry = FileEntry(1u, payload.size.toLong(), "application/octet-stream", "selftest.bin", payloadHash)
                    val manifest = Manifest(listOf(entry))
                    link.sendOffer(manifest)
                    if (!link.awaitAccept()) throw IOException("declined")
                    link.sendFile(entry, payload.inputStream())
                    trace("OUT: chunks sent")
                    link.sendDone(manifest.manifestHash())
                    if (!link.awaitAck()) throw IOException("no ack")
                }

                val hash = receiver.await()
                val ok = hash.contentEquals(payloadHash) &&
                    received.size() == payload.size &&
                    received.toByteArray().contentEquals(payload)

                withContext(Dispatchers.Main) {
                    selfTest = if (ok) {
                        "Loopback OK · 512 KB · X25519 + AES-256-GCM"
                    } else {
                        "Loopback hash mismatch"
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { selfTest = "Self-test failed: ${friendly(t)}" }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    // ------------------------------------------------------------------ utils

    private fun localCapabilities() = Capabilities(
        shellId = ShellDetector.detect(),
        flags = CapsFlag.ENCRYPTED or CapsFlag.WIFI_DIRECT,
        maxMtu = 1500,
    )

    private fun acquireMulticast(ctx: Context) {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("morph-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun deviceName(): String = Build.MODEL ?: "Android"

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Connection timed out"
        is java.net.ConnectException -> "Peer not reachable"
        is java.net.UnknownHostException -> "Address not found"
        else -> t.message ?: t.javaClass.simpleName
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10) {
        "${value.toInt()} ${units[unit]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}
