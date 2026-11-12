package org.apresenter.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.apresenter.core.discovery.DiscoveredServer
import org.apresenter.core.discovery.ServerDiscovery
import org.apresenter.core.signage.ConnectionPhase
import org.apresenter.core.signage.MediaCache
import org.apresenter.core.signage.MediaKind
import org.apresenter.core.signage.PlaylistItem
import org.apresenter.core.signage.ScreenMessage
import org.apresenter.core.signage.Show
import org.apresenter.core.signage.SignageClient
import org.apresenter.core.signage.SignageState
import org.apresenter.core.signage.Transition
import java.io.File

class TvMainActivity : ComponentActivity() {
    private lateinit var discovery: ServerDiscovery
    private lateinit var signageClient: SignageClient
    private val showSettingsOverlay = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val metrics = resources.displayMetrics
        val pkgVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        }.getOrDefault("0.1.0")

        discovery = ServerDiscovery(this)
        signageClient = SignageClient(
            context = this,
            appVersion = pkgVersion,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val discovered by discovery.servers.collectAsState()
                val signage by signageClient.state.collectAsState()
                var overlayOpen by showSettingsOverlay

                LaunchedEffect(discovered) {
                    signageClient.onDiscoveredServers(discovered)
                }

                BackHandler(enabled = signage.paired || overlayOpen) {
                    overlayOpen = !overlayOpen
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    if (!signage.paired) {
                        TvPairingScreen(
                            signage = signage,
                            discovered = discovered,
                            onConnect = { host, port -> signageClient.connectTo(host, port) },
                        )
                    } else {
                        TvPlayerSurface(
                            signage = signage,
                            mediaCache = signageClient.mediaCache,
                        )
                    }

                    signage.identifyBanner?.let { label ->
                        IdentifyOverlay(label = label)
                    }

                    if (overlayOpen) {
                        TvOptionsOverlay(
                            signage = signage,
                            discovered = discovered,
                            onClose = { overlayOpen = false },
                            onConnect = { host, port ->
                                signageClient.connectTo(host, port)
                                overlayOpen = false
                            },
                            onToggleBoot = { signageClient.setAutoStartOnBoot(it) },
                            onUnpair = {
                                signageClient.unpairAndReset()
                                overlayOpen = false
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        signageClient.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        signageClient.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSettingsOverlay.value = !showSettingsOverlay.value
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun TvPairingScreen(
    signage: SignageState,
    discovered: List<DiscoveredServer>,
    onConnect: (String, Int) -> Unit,
) {
    var manualHost by remember(signage.host) { mutableStateOf(signage.host) }
    var manualPort by remember(signage.port) { mutableStateOf(signage.port.toString()) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(40.dp))
                Text("Apresenter TV · Receptor", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                "Esta TV funciona apenas como tela de sinalizacao. Conecte ao Apresenter Desktop na rede da igreja:",
                fontSize = 18.sp,
                color = Color(0xFFCBD5E1),
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codigo de pareamento desta TV:", fontSize = 16.sp, color = Color(0xFF94A3B8))
                    Text(
                        text = signage.pairingCode.chunked(3).joinToString(" "),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF60A5FA),
                    )
                    Text(
                        "No PC: abra a aba Signage no Apresenter e clique em Aprovar neste codigo.",
                        fontSize = 15.sp,
                        color = Color(0xFFE2E8F0),
                    )
                }
            }

            if (signage.statusNote.isNotBlank()) {
                Text(signage.statusNote, fontSize = 15.sp, color = Color(0xFFFBBF24))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Servidores encontrados na rede", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (discovered.isEmpty()) {
                Text("Buscando Apresenter via Wi-Fi / rede local...", fontSize = 15.sp, color = Color(0xFF94A3B8))
            } else {
                discovered.forEach { server ->
                    Button(
                        onClick = { onConnect(server.host, server.port) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${server.name} (${server.host}:${server.port})")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Ou informar IP manualmente", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("IP do PC") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = { Text("Porta") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = { onConnect(manualHost, manualPort.toIntOrNull() ?: 8765) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Conectar e parear")
            }
        }
    }
}

@Composable
private fun TvPlayerSurface(
    signage: SignageState,
    mediaCache: MediaCache,
) {
    val manifest = signage.manifest
    val decision = signage.decision

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val show = decision?.show) {
            is Show.PlaylistShow -> {
                val playlist = manifest?.playlist(show.playlistId)
                val items = remember(playlist, manifest) {
                    playlist?.items.orEmpty().filter { item ->
                        item.kind.isLink || mediaCache.fileFor(item.sha256) != null
                    }
                }
                if (items.isNotEmpty()) {
                    PlaylistPlayback(items = items, mediaCache = mediaCache)
                } else {
                    TvIdleView(
                        title = manifest?.idleCard?.title.orEmpty().ifBlank { signage.ownerName },
                        note = signage.statusNote,
                        logoFile = manifest?.idleCard?.logoSha256?.let(mediaCache::fileFor),
                    )
                }
            }
            Show.Blank -> Box(Modifier.fillMaxSize().background(Color.Black))
            Show.Idle, null -> {
                TvIdleView(
                    title = manifest?.idleCard?.title.orEmpty().ifBlank { signage.ownerName.ifBlank { "Apresenter TV" } },
                    note = signage.statusNote.ifBlank {
                        if (signage.phase == ConnectionPhase.CONNECTED) {
                            "Aguardando playlist na aba Signage do Apresenter"
                        } else {
                            "Modo offline ativo"
                        }
                    },
                    logoFile = manifest?.idleCard?.logoSha256?.let(mediaCache::fileFor),
                )
            }
        }

        val message = manifest?.screen?.message
        if (message != null && message.showsAt(System.currentTimeMillis())) {
            ScreenMessageOverlay(message)
        }
    }
}

@Composable
private fun PlaylistPlayback(
    items: List<PlaylistItem>,
    mediaCache: MediaCache,
) {
    var index by remember(items) { mutableIntStateOf(0) }
    val safeIndex = index.coerceIn(0, items.lastIndex)
    val currentItem = items[safeIndex]

    fun advance() {
        index = if (items.size <= 1) 0 else (safeIndex + 1) % items.size
    }

    if (currentItem.kind == MediaKind.IMAGE || currentItem.kind == MediaKind.WEB) {
        LaunchedEffect(safeIndex, currentItem) {
            val waitMs = currentItem.durationMs ?: PlaylistItem.DEFAULT_DURATION_MS
            delay(waitMs.coerceAtLeast(2_000L))
            advance()
        }
    }

    val durationAnim = if (currentItem.transition == Transition.CROSSFADE) {
        currentItem.transitionMs.toInt().coerceIn(0, 2000)
    } else {
        0
    }

    Crossfade(
        targetState = safeIndex to currentItem,
        animationSpec = tween(durationMillis = durationAnim),
        label = "signage_slide",
    ) { (_, item) ->
        when (item.kind) {
            MediaKind.IMAGE -> {
                val file = mediaCache.fileFor(item.sha256)
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                    )
                }
            }
            MediaKind.VIDEO -> {
                val file = mediaCache.fileFor(item.sha256)
                if (file != null) {
                    VideoItemPlayer(
                        uri = file.toURI().toString(),
                        loopSingle = items.size == 1,
                        maxDurationMs = item.durationMs,
                        onEnded = ::advance,
                    )
                }
            }
            MediaKind.STREAM -> {
                val url = item.url
                if (!url.isNullOrBlank()) {
                    VideoItemPlayer(
                        uri = url,
                        loopSingle = items.size == 1,
                        maxDurationMs = item.durationMs,
                        onEnded = ::advance,
                    )
                }
            }
            MediaKind.WEB -> {
                val url = item.url
                if (!url.isNullOrBlank()) {
                    WebItemView(url = url)
                }
            }
        }
    }
}

@Composable
private fun VideoItemPlayer(
    uri: String,
    loopSingle: Boolean,
    maxDurationMs: Long?,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(uri, loopSingle) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = if (loopSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(uri, maxDurationMs) {
        if (maxDurationMs != null && maxDurationMs > 0L) {
            delay(maxDurationMs)
            onEnded()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !loopSingle) {
                    onEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebItemView(url: String) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun TvIdleView(
    title: String,
    note: String,
    logoFile: File?,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B1120)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            if (logoFile != null) {
                AsyncImage(
                    model = logoFile,
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                )
            } else {
                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(72.dp))
            }
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            if (note.isNotBlank()) {
                Text(note, fontSize = 18.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
            }
            Text(
                "Pressione MENU ou VOLTAR no controle para opcoes da TV",
                fontSize = 14.sp,
                color = Color(0xFF475569),
            )
        }
    }
}

@Composable
private fun ScreenMessageOverlay(message: ScreenMessage) {
    if (message.fullScreen) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xE60F172A)).padding(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.text,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC1E293B))
                .padding(horizontal = 32.dp, vertical = 18.dp),
        ) {
            Text(
                text = message.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun IdentifyOverlay(label: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC0F172A)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF2563EB),
        ) {
            Text(
                text = label,
                fontSize = 56.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 28.dp),
            )
        }
    }
}

@Composable
private fun TvOptionsOverlay(
    signage: SignageState,
    discovered: List<DiscoveredServer>,
    onClose: () -> Unit,
    onConnect: (String, Int) -> Unit,
    onToggleBoot: (Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E293B),
            modifier = Modifier.width(620.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Apresenter TV · Opcoes", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Status: ${signage.phase.name} · Servidor: ${signage.host.ifBlank { "-" }}:${signage.port}",
                    fontSize = 15.sp,
                    color = Color(0xFFCBD5E1),
                )
                if (signage.statusNote.isNotBlank()) {
                    Text(signage.statusNote, fontSize = 14.sp, color = Color(0xFFFBBF24))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Abrir automaticamente ao ligar a TV", color = Color.White, fontSize = 16.sp)
                    Switch(checked = signage.autoStartOnBoot, onCheckedChange = onToggleBoot)
                }

                if (discovered.isNotEmpty()) {
                    val server = discovered.first()
                    OutlinedButton(
                        onClick = { onConnect(server.host, server.port) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reconectar a ${server.name} (${server.host})")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onUnpair, modifier = Modifier.weight(1f)) {
                        Text("Desparear TV")
                    }
                    Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text("Fechar")
                    }
                }
            }
        }
    }
}
