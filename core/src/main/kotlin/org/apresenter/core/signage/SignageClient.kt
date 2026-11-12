package org.apresenter.core.signage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.apresenter.core.discovery.DiscoveredServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ConnectionPhase {
    SEARCHING,
    PAIRING,
    CONNECTED,
    OFFLINE_CACHED,
    ERROR,
}

data class SignageState(
    val playerId: String,
    val pairingCode: String,
    val paired: Boolean,
    val phase: ConnectionPhase,
    val host: String,
    val port: Int,
    val ownerName: String = "",
    val manifest: Manifest? = null,
    val override: ScreenOverride? = null,
    val decision: ScreenDecision? = null,
    val identifyBanner: String? = null,
    val statusNote: String = "",
    val autoStartOnBoot: Boolean = false,
)

/**
 * Connects a dedicated Android TV receiver (`Platform.ANDROID_TV`) to Apresenter Desktop over
 * `/api/signage/ws`, caches manifests & media locally, listens for UDP nudges on port 47640,
 * and evaluates the playback decision offline every second.
 */
class SignageClient(
    context: Context,
    private val appVersion: String,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("apresenter_tv_signage", Context.MODE_PRIVATE)
    val mediaCache = MediaCache(appContext.filesDir)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val playerId: String = prefs.getString("player_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("player_id", it).apply() }

    private var pairingCode: String = generatePairingCode()
    private var token: String? = prefs.getString("token", null)
    private var ownerId: String = prefs.getString("owner_id", null).orEmpty()
    private var ownerName: String = prefs.getString("owner_name", null).orEmpty()
    private var configuredHost: String = prefs.getString("host", null).orEmpty()
    private var configuredPort: Int = prefs.getInt("port", 8765)
    private var autoStartOnBoot: Boolean = prefs.getBoolean("boot_start", false)

    private var currentManifest: Manifest? = mediaCache.loadManifest()
    private var manifestVersion: String? = mediaCache.loadManifestVersion()
    private var currentOverride: ScreenOverride? = mediaCache.loadOverride()
    private var lastSyncMs: Long? = null
    private var lastErrors: List<String> = emptyList()

    private val _state = MutableStateFlow(
        SignageState(
            playerId = playerId,
            pairingCode = pairingCode,
            paired = token != null,
            phase = if (token != null && currentManifest != null) ConnectionPhase.OFFLINE_CACHED else ConnectionPhase.SEARCHING,
            host = configuredHost,
            port = configuredPort,
            ownerName = ownerName,
            manifest = currentManifest,
            override = currentOverride,
            decision = currentManifest?.let { evaluateDecision(it) },
            autoStartOnBoot = autoStartOnBoot,
        ),
    )
    val state: StateFlow<SignageState> = _state.asStateFlow()

    @Volatile
    private var activeWebSocket: WebSocket? = null
    private var loopJob: Job? = null
    private var tickerJob: Job? = null
    private var nudgeJob: Job? = null
    private var identifyJob: Job? = null

    fun start() {
        if (tickerJob == null) {
            tickerJob = scope.launch {
                while (isActive) {
                    refreshDecision()
                    delay(1_000L)
                }
            }
        }
        if (nudgeJob == null) {
            nudgeJob = scope.launch { listenUdpNudges() }
        }
        if (loopJob == null && configuredHost.isNotBlank()) {
            connectTo(configuredHost, configuredPort)
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        activeWebSocket?.close(1000, "stopped")
        activeWebSocket = null
        tickerJob?.cancel()
        tickerJob = null
        nudgeJob?.cancel()
        nudgeJob = null
        identifyJob?.cancel()
    }

    fun onDiscoveredServers(servers: List<DiscoveredServer>) {
        if (configuredHost.isBlank() && servers.isNotEmpty()) {
            val first = servers.first()
            connectTo(first.host, first.port)
        }
    }

    fun connectTo(host: String, port: Int) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        configuredHost = cleanHost
        configuredPort = port
        prefs.edit().putString("host", cleanHost).putInt("port", port).apply()

        loopJob?.cancel()
        activeWebSocket?.close(1000, "reconnect")
        activeWebSocket = null

        loopJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                val connected = openSingleSession(cleanHost, port)
                backoffMs = if (connected) 2_000L else (backoffMs * 2).coerceAtMost(30_000L)
                publishState(
                    phase = if (token != null && currentManifest != null) {
                        ConnectionPhase.OFFLINE_CACHED
                    } else {
                        ConnectionPhase.SEARCHING
                    },
                    note = "Reconectando a $cleanHost:$port...",
                )
                delay(backoffMs)
            }
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        autoStartOnBoot = enabled
        prefs.edit().putBoolean("boot_start", enabled).apply()
        publishState()
    }

    fun unpairAndReset() {
        token = null
        ownerId = ""
        ownerName = ""
        pairingCode = generatePairingCode()
        currentManifest = null
        manifestVersion = null
        currentOverride = null
        mediaCache.clearAll()
        prefs.edit().remove("token").remove("owner_id").remove("owner_name").apply()
        activeWebSocket?.close(1000, "unpaired")
        publishState(phase = ConnectionPhase.PAIRING, note = "Despareado. Aguardando novo pareamento.")
        if (configuredHost.isNotBlank()) {
            connectTo(configuredHost, configuredPort)
        }
    }

    private suspend fun openSingleSession(host: String, port: Int): Boolean {
        val wsUrl = "ws://$host:$port/api/signage/ws"
        val reqBuilder = Request.Builder().url(wsUrl)
        val currentToken = token
        if (!currentToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $currentToken")
        }

        val sessionClosed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var wasOpened = false

        val socket = httpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wasOpened = true
                    activeWebSocket = webSocket
                    val hello = PlayerMessage.Hello(
                        playerId = playerId,
                        agentVersion = appVersion,
                        protocolMin = PROTOCOL_VERSION,
                        protocolMax = PROTOCOL_VERSION,
                        manifestVersion = manifestVersion,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        platform = Platform.ANDROID_TV,
                        pairingCode = if (currentToken.isNullOrBlank()) pairingCode else null,
                        features = listOf(
                            Features.PREVIEW,
                            Features.WIPE,
                            Features.FACTORY_RESET,
                            Features.STREAM,
                            Features.WEB,
                            Features.ROTATION,
                            Features.MESSAGE,
                        ),
                    )
                    webSocket.send(SignageJson.encodeToString(PlayerMessage.serializer(), hello))
                    publishState(
                        phase = if (currentToken.isNullOrBlank()) ConnectionPhase.PAIRING else ConnectionPhase.CONNECTED,
                        note = if (currentToken.isNullOrBlank()) {
                            "Aprove o codigo $pairingCode na aba Signage do Apresenter"
                        } else {
                            "Conectado a $host:$port"
                        },
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching {
                        SignageJson.decodeFromString(ServerMessage.serializer(), text)
                    }.getOrNull() ?: return
                    handleServerMessage(host, port, msg)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        token = null
                        pairingCode = generatePairingCode()
                        prefs.edit().remove("token").apply()
                    }
                    publishState(
                        phase = if (currentManifest != null) ConnectionPhase.OFFLINE_CACHED else ConnectionPhase.ERROR,
                        note = "Erro de conexao: ${t.message ?: response?.code ?: "desconhecido"}",
                    )
                    sessionClosed.complete(wasOpened)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sessionClosed.complete(wasOpened)
                }
            },
        )

        val statusJob = scope.launch {
            while (isActive && !sessionClosed.isCompleted) {
                delay(15_000L)
                sendStatus(socket)
            }
        }
        val result = sessionClosed.await()
        statusJob.cancel()
        if (activeWebSocket === socket) activeWebSocket = null
        return result
    }

    private fun handleServerMessage(host: String, port: Int, msg: ServerMessage) {
        when (msg) {
            is ServerMessage.PairingPending -> {
                pairingCode = msg.code
                publishState(
                    phase = ConnectionPhase.PAIRING,
                    note = "Aprove o codigo ${msg.code} na aba Signage do Apresenter",
                )
            }
            is ServerMessage.Paired -> {
                token = msg.token
                ownerId = msg.ownerId
                ownerName = msg.ownerName
                prefs.edit()
                    .putString("token", msg.token)
                    .putString("owner_id", msg.ownerId)
                    .putString("owner_name", msg.ownerName)
                    .apply()
                publishState(phase = ConnectionPhase.CONNECTED, note = "Pareado com ${msg.ownerName}")
                activeWebSocket?.close(1000, "reconnecting paired")
            }
            ServerMessage.PairingRejected -> {
                pairingCode = generatePairingCode()
                publishState(phase = ConnectionPhase.ERROR, note = "Pareamento recusado no computador.")
            }
            ServerMessage.Released, ServerMessage.FactoryReset -> {
                unpairAndReset()
            }
            is ServerMessage.ManifestChanged -> {
                scope.launch { fetchAndSyncManifest("http://$host:$port", msg.version) }
            }
            is ServerMessage.SetOverride -> {
                currentOverride = msg.override
                mediaCache.saveOverride(msg.override)
                refreshDecision()
            }
            is ServerMessage.Identify -> {
                identifyJob?.cancel()
                val label = currentManifest?.screen?.name ?: "TV Apresenter"
                publishState(identifyBanner = label)
                identifyJob = scope.launch {
                    delay(msg.seconds * 1_000L)
                    publishState(identifyBanner = null)
                }
            }
            ServerMessage.Refresh -> {
                val base = "http://$host:$port"
                scope.launch { fetchAndSyncManifest(base, manifestVersion) }
            }
            ServerMessage.Wipe -> {
                currentManifest = null
                manifestVersion = null
                currentOverride = null
                mediaCache.clearAll()
                publishState()
                scope.launch { fetchAndSyncManifest("http://$host:$port", null) }
            }
            is ServerMessage.Unsupported -> {
                publishState(phase = ConnectionPhase.ERROR, note = "Protocolo incompativel (${msg.serverProtocol}).")
            }
            is ServerMessage.Tv -> Unit
        }
    }

    private suspend fun fetchAndSyncManifest(baseHttpUrl: String, expectedVersion: String?) {
        val currentToken = token ?: return
        val request = Request.Builder()
            .url("$baseHttpUrl/api/signage/manifest")
            .header("Authorization", "Bearer $currentToken")
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    lastErrors = emptyList()
                    publishState(
                        phase = ConnectionPhase.CONNECTED,
                        note = "TV pareada! Crie e envie uma playlist na aba Signage do Apresenter.",
                    )
                    return
                }
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val etag = response.header("ETag")?.trim('"') ?: expectedVersion
                val body = response.body?.string() ?: error("manifesto vazio")
                val parsed = SignageJson.decodeFromString(Manifest.serializer(), body)
                if (ownerId.isNotEmpty() && parsed.ownerId != ownerId) {
                    error("ownerId divergente")
                }
                val syncErrors = mediaCache.syncFiles(parsed, baseHttpUrl, currentToken, httpClient)
                lastErrors = syncErrors
                lastSyncMs = System.currentTimeMillis()
                currentManifest = parsed
                manifestVersion = etag
                mediaCache.saveManifest(parsed, etag)
                publishState(note = "Sincronizado com ${ownerName.ifBlank { baseHttpUrl }}")
                refreshDecision()
                activeWebSocket?.let(::sendStatus)
            }
        }.onFailure { err ->
            lastErrors = listOf("Falha ao sincronizar manifesto: ${err.message}")
            publishState(note = lastErrors.first())
        }
    }

    private fun sendStatus(socket: WebSocket) {
        val decision = _state.value.decision
        val showingPlaylistId = (decision?.show as? Show.PlaylistShow)?.playlistId
        val status = PlayerMessage.Status(
            showingPlaylistId = showingPlaylistId,
            reason = decision?.reason?.name?.lowercase(),
            lastSyncMs = lastSyncMs,
            freeDiskBytes = mediaCache.freeSpaceBytes(),
            cec = CecState.UNAVAILABLE,
            errors = lastErrors,
        )
        socket.send(SignageJson.encodeToString(PlayerMessage.serializer(), status))
    }

    private suspend fun listenUdpNudges() {
        runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(NUDGE_PORT))
            }
        }.getOrNull()?.use { socket ->
            val buf = ByteArray(1024)
            while (scope.isActive) {
                val packet = DatagramPacket(buf, buf.size)
                runCatching {
                    socket.receive(packet)
                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val nudge = SignageJson.decodeFromString(Nudge.serializer(), payload)
                    val matchesOwner = ownerId.isNotEmpty() && nudge.owner == nudgeOwner(ownerId)
                    val matchesPlayer = nudge.player != null && nudge.player == nudgePlayer(playerId)
                    if (matchesOwner || matchesPlayer) {
                        val senderHost = packet.address.hostAddress ?: return@runCatching
                        connectTo(senderHost, nudge.port)
                    }
                }
            }
        }
    }

    private fun refreshDecision() {
        val manifest = currentManifest ?: return
        val decision = evaluateDecision(manifest)
        if (decision != _state.value.decision) {
            publishState(decision = decision)
        }
    }

    private fun evaluateDecision(manifest: Manifest): ScreenDecision = decide(
        manifest = manifest,
        override = currentOverride,
        now = Instant.now(),
        isPlayable = mediaCache::isPlaylistReady,
    )

    private fun publishState(
        phase: ConnectionPhase = _state.value.phase,
        note: String = _state.value.statusNote,
        decision: ScreenDecision? = _state.value.decision,
        identifyBanner: String? = _state.value.identifyBanner,
    ) {
        _state.value = SignageState(
            playerId = playerId,
            pairingCode = pairingCode,
            paired = token != null,
            phase = phase,
            host = configuredHost,
            port = configuredPort,
            ownerName = ownerName,
            manifest = currentManifest,
            override = currentOverride,
            decision = decision,
            identifyBanner = identifyBanner,
            statusNote = note,
            autoStartOnBoot = autoStartOnBoot,
        )
    }

    private fun generatePairingCode(): String =
        (100_000 + SecureRandom().nextInt(900_000)).toString()
}
