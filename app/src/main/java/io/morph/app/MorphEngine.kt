package io.morph.app

import android.content.ContentValues
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
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
)

/**
 * Real DROP/1 transport over the local network:
 * NSD/mDNS discovery + TCP + X25519 handshake + AES-256-GCM frames.
 */
object MorphEngine {

    private const val SERVICE_TYPE = "_morph._tcp"
    private const val TAG_NAME = "MORPH-"

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

    /** Local TCP port other phones connect to. */
    val port: Int get() = localPort

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pending: Incoming? = null
    private var localPort = Protocol.WIFI_PORT

    private val resolved = ConcurrentHashMap<String, Peer>()

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

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val ctx = context.applicationContext
        appContext = ctx
        running = true
        status = "Starting…"

        try {
            val socket = try {
                ServerSocket(Protocol.WIFI_PORT)
            } catch (t: IOException) {
                ServerSocket(0)
            }
            socket.reuseAddress = true
            serverSocket = socket
            localPort = socket.localPort
            acquireMulticast(ctx)
            startNsd(ctx)
            scope.launch { acceptLoop(ctx, socket) }
            status = "Visible as ${deviceName()} · port $localPort"
        } catch (t: Throwable) {
            running = false
            lastError = t.message ?: t.javaClass.simpleName
            status = "Failed to start: ${lastError}"
        }
    }

    fun stop() {
        running = false
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
        val old = discovery ?: return
        runCatching { manager.stopServiceDiscovery(old) }
        peers.clear()
        resolved.clear()
        startDiscovery(ctx, manager)
    }

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
            )
        )
        scope.launch {
            delay(10_000)
            if (drop?.kind == DropKind.READY) publish(ctx, null)
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

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
                    socket.soTimeout = 30_000
                    val link = DropLink(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        Role.SENDER,
                    )
                    link.handshake(localCapabilities())

                    val entry = FileEntry(1u, prepared.size, prepared.mime, prepared.name, prepared.sha256)
                    val manifest = Manifest(listOf(entry))
                    link.sendOffer(manifest)

                    if (!link.awaitAccept()) throw IOException("declined by ${peer.name}")

                    prepared.file.inputStream().use { input ->
                        link.sendFile(entry, input) { sent ->
                            progress(ctx, sent.toFloat() / prepared.size.coerceAtLeast(1))
                        }
                    }

                    link.sendDone(manifest.manifestHash())
                    if (!link.awaitAck()) throw IOException("receiver rejected the file")
                }

                publish(
                    ctx,
                    drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "Delivered")
                )
                status = "Sent ${prepared.name} to ${peer.name}"
                delay(1600)
                if (drop?.kind == DropKind.OUTGOING) publish(ctx, null)
            } catch (t: Throwable) {
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
            val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.RECEIVER)
            link.handshake(localCapabilities())
            val manifest = link.receiveOffer()
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

            val accepted = withTimeoutOrNull(Protocol.ACCEPT_TIMEOUT_MS) {
                incoming.decision.await()
            } ?: false

            if (!accepted) {
                link.decline()
                status = "Declined ${file.name}"
                publish(ctx, null)
                return
            }

            publish(ctx, drop?.copy(phase = TransferPhase.RUNNING, progress = 0f))

            val target = createDownload(ctx, file)
            val sink: OutputStream = ctx.contentResolver.openOutputStream(target)
                ?: throw IOException("cannot open Downloads")

            val hash = sink.use { out ->
                link.receiveFile(file, out) { written ->
                    progress(ctx, written.toFloat() / file.size.coerceAtLeast(1))
                }
            }
            finalizeDownload(ctx, target)

            if (!hash.contentEquals(file.sha256)) {
                link.abort()
                throw IOException("hash mismatch")
            }
            if (!link.verifyDone(manifest.manifestHash())) {
                link.abort()
                throw IOException("manifest mismatch")
            }
            link.ack()

            publish(ctx, drop?.copy(phase = TransferPhase.DONE, progress = 1f, message = "Saved to Downloads/MORPH"))
            status = "Saved ${file.name} to Downloads/MORPH"
            delay(1800)
            publish(ctx, null)
        } catch (t: Throwable) {
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
        if (ui == null || MorphApp.isForeground) {
            runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
            }
        } else {
            runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
            }
        }
    }

    private fun progress(ctx: Context, fraction: Float) {
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
            serviceName = TAG_NAME + deviceName()
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
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                status = "Scanning for nearby phones…"
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (!name.startsWith(TAG_NAME)) return
                if (name == TAG_NAME + deviceName()) return
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
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

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
                val peer = Peer(
                    id = name,
                    name = name.removePrefix(TAG_NAME),
                    host = host,
                    port = serviceInfo.port,
                    shell = ShellId.from(shellCode),
                )
                resolved[name] = peer
                val index = peers.indexOfFirst { it.id == name }
                if (index >= 0) peers[index] = peer else peers.add(peer)
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(file.name))
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

    fun runSelfTest(context: Context) {
        val ctx = context.applicationContext
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
                    val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.SENDER)
                    link.handshake(localCapabilities())
                    val entry = FileEntry(1u, payload.size.toLong(), "application/octet-stream", "selftest.bin", payloadHash)
                    val manifest = Manifest(listOf(entry))
                    link.sendOffer(manifest)
                    if (!link.awaitAccept()) throw IOException("declined")
                    link.sendFile(entry, payload.inputStream())
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
