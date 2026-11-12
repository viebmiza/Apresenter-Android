package org.apresenter.core.companion

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CompanionUiState(
    val connected: Boolean = false,
    val host: String = "",
    val port: Int = 8765,
    val apiKey: String = "",
    val statusText: String = "Desconectado",
    val serverVersion: String = "",
    val liveState: LiveStateDto? = null,
    val schedule: List<ScheduleItemDto> = emptyList(),
    val songCatalog: SongCatalogResponse = SongCatalogResponse(),
    val bibleCatalog: BibleCatalogResponse? = null,
    val selectedSong: SongDetailDto? = null,
    val selectedChapter: BibleChapterResponse? = null,
)

/**
 * Companion HTTP + WebSocket client for Apresenter Mobile Controller (`:app-mobile`).
 */
class CompanionClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("apresenter_mobile_companion", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var savedHost: String = prefs.getString("host", null).orEmpty()
    private var savedPort: Int = prefs.getInt("port", 8765)
    private var savedApiKey: String = prefs.getString("api_key", null).orEmpty()

    private val _state = MutableStateFlow(
        CompanionUiState(
            host = savedHost,
            port = savedPort,
            apiKey = savedApiKey,
        ),
    )
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()

    @Volatile
    private var activeSocket: WebSocket? = null
    private var connectJob: Job? = null

    fun baseHttpUrl(): String = "http://${_state.value.host}:${_state.value.port}"

    fun startIfSaved() {
        if (savedHost.isNotBlank() && connectJob == null) {
            connect(savedHost, savedPort, savedApiKey)
        }
    }

    fun connect(host: String, port: Int, apiKey: String = _state.value.apiKey) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        savedHost = cleanHost
        savedPort = port
        savedApiKey = apiKey.trim()
        prefs.edit()
            .putString("host", savedHost)
            .putInt("port", savedPort)
            .putString("api_key", savedApiKey)
            .apply()

        connectJob?.cancel()
        activeSocket?.close(1000, "reconnect")
        activeSocket = null
        _state.value = _state.value.copy(
            host = savedHost,
            port = savedPort,
            apiKey = savedApiKey,
            statusText = "Conectando a $savedHost:$savedPort...",
        )

        connectJob = scope.launch {
            while (isActive) {
                runSocketSession(savedHost, savedPort, savedApiKey)
                delay(3_000L)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        activeSocket?.close(1000, "user_disconnect")
        activeSocket = null
        _state.value = _state.value.copy(connected = false, statusText = "Desconectado")
    }

    private suspend fun runSocketSession(host: String, port: Int, apiKey: String) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url("ws://$host:$port/ws")
            .header("X-Device-Id", deviceId)
            .apply {
                if (apiKey.isNotEmpty()) header("X-Api-Key", apiKey)
            }
            .build()

        httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeSocket = webSocket
                    _state.value = _state.value.copy(
                        connected = true,
                        statusText = "Conectado a $host:$port",
                    )
                    scope.launch { refreshInitialData() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleEvent(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = t.message ?: response?.code?.toString() ?: "sem resposta"
                    _state.value = _state.value.copy(
                        connected = false,
                        statusText = "Falha ao conectar em $host:$port ($detail). Verifique o IP em Opções → Servidor no PC.",
                    )
                    done.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value = _state.value.copy(
                        connected = false,
                        statusText = "Desconectado de $host:$port. Tentando reconectar...",
                    )
                    done.complete(Unit)
                }
            },
        )
        done.await()
    }

    private fun handleEvent(raw: String) {
        val msg = runCatching {
            CompanionJson.decodeFromString(WebSocketMessage.serializer(), raw)
        }.getOrNull() ?: return

        when (msg.type) {
            "songs_updated" -> scope.launch { refreshSongs() }
            "bible_updated" -> runCatching {
                CompanionJson.decodeFromString(BibleCatalogResponse.serializer(), msg.payload)
            }.getOrNull()?.let { bible ->
                _state.value = _state.value.copy(bibleCatalog = bible)
            }
            "schedule_updated" -> runCatching {
                CompanionJson.decodeFromString(ScheduleResponse.serializer(), msg.payload)
            }.getOrNull()?.let { sched ->
                _state.value = _state.value.copy(schedule = sched.items)
            }
            "live_state_changed" -> runCatching {
                CompanionJson.decodeFromString(LiveStateDto.serializer(), msg.payload)
            }.getOrNull()?.let { live ->
                _state.value = _state.value.copy(liveState = live)
            }
            "display_cleared" -> {
                _state.value = _state.value.copy(liveState = null)
            }
        }
    }

    private suspend fun refreshInitialData() {
        runCatching {
            val statusJson = get("/api/status")
            if (statusJson != null) {
                val status = CompanionJson.decodeFromString(StatusResponse.serializer(), statusJson)
                _state.value = _state.value.copy(serverVersion = status.appVersion)
            }
        }
        refreshSongs()
        runCatching {
            val schedJson = get("/api/schedule")
            if (schedJson != null) {
                val sched = CompanionJson.decodeFromString(ScheduleResponse.serializer(), schedJson)
                _state.value = _state.value.copy(schedule = sched.items)
            }
        }
        runCatching {
            val bibleJson = get("/api/bible")
            if (bibleJson != null && bibleJson.contains("\"books\"")) {
                val bible = CompanionJson.decodeFromString(BibleCatalogResponse.serializer(), bibleJson)
                _state.value = _state.value.copy(bibleCatalog = bible)
            }
        }
    }

    suspend fun refreshSongs() {
        runCatching {
            val songsJson = get("/api/songs") ?: return
            val catalog = CompanionJson.decodeFromString(SongCatalogResponse.serializer(), songsJson)
            _state.value = _state.value.copy(songCatalog = catalog)
        }
    }

    fun loadSongDetail(song: SongDto, bookName: String) {
        scope.launch {
            val encodedBook = URLEncoder.encode(bookName, "UTF-8")
            val encodedNumber = URLEncoder.encode(song.number.ifBlank { song.id.toString() }, "UTF-8")
            val path = "/api/songs/$encodedNumber?songbook=$encodedBook&id=${song.id}"
            runCatching {
                val json = get(path) ?: return@launch
                val detail = CompanionJson.decodeFromString(SongDetailDto.serializer(), json)
                _state.value = _state.value.copy(selectedSong = detail)
            }
        }
    }

    fun clearSelectedSong() {
        _state.value = _state.value.copy(selectedSong = null)
    }

    fun loadBibleChapter(bookId: Int, chapter: Int) {
        scope.launch {
            runCatching {
                val json = get("/api/bible?book=$bookId&chapter=$chapter") ?: return@launch
                val ch = CompanionJson.decodeFromString(BibleChapterResponse.serializer(), json)
                _state.value = _state.value.copy(selectedChapter = ch)
            }
        }
    }

    fun sendSimpleCommand(type: String, payload: String = "") {
        val msg = CompanionJson.encodeToString(
            WebSocketMessage.serializer(),
            WebSocketMessage(type = type, payload = payload),
        )
        activeSocket?.send(msg)
    }

    fun triggerTake() = scope.launch { postEmpty("/api/take") }

    fun triggerClear() = scope.launch { postEmpty("/api/clear") }

    fun selectScheduleSong(item: ScheduleItemDto) {
        val dto = ScheduleSongDto(
            id = item.id,
            songNumber = item.songNumber ?: 0,
            title = item.title ?: item.displayText,
            songbook = item.songbook.orEmpty(),
        )
        sendSimpleCommand(
            "select_song",
            CompanionJson.encodeToString(ScheduleSongDto.serializer(), dto),
        )
    }

    fun selectSongSection(number: String, sectionIndex: Int) {
        sendSimpleCommand(
            "select_song_section",
            CompanionJson.encodeToString(
                SelectSongSectionRequest.serializer(),
                SelectSongSectionRequest(number = number, section = sectionIndex),
            ),
        )
    }

    fun selectBibleVerse(bookName: String, chapter: Int, verseNumber: Int, verseText: String) {
        sendSimpleCommand(
            "select_bible_verse",
            CompanionJson.encodeToString(
                SelectBibleVerseRequest.serializer(),
                SelectBibleVerseRequest(
                    bookName = bookName,
                    chapter = chapter,
                    verseNumber = verseNumber,
                    verseText = verseText,
                ),
            ),
        )
    }

    private fun authorizedRequest(path: String): Request.Builder {
        val builder = Request.Builder()
            .url("${baseHttpUrl()}$path")
            .header("X-Device-Id", deviceId)
        val key = _state.value.apiKey
        if (key.isNotEmpty()) builder.header("X-Api-Key", key)
        return builder
    }

    private fun get(path: String): String? {
        if (_state.value.host.isBlank()) return null
        return httpClient.newCall(authorizedRequest(path).build()).execute().use { res ->
            if (res.isSuccessful) res.body?.string() else null
        }
    }

    private fun postEmpty(path: String) {
        if (_state.value.host.isBlank()) return
        runCatching {
            val req = authorizedRequest(path)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
        }
    }
}
