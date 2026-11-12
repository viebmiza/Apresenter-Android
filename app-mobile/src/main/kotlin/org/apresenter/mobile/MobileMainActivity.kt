package org.apresenter.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.apresenter.core.companion.BibleBookDto
import org.apresenter.core.companion.CompanionClient
import org.apresenter.core.companion.CompanionUiState
import org.apresenter.core.companion.LiveStateDto
import org.apresenter.core.companion.SongDto
import org.apresenter.core.discovery.DiscoveredServer
import org.apresenter.core.discovery.ServerDiscovery

class MobileMainActivity : ComponentActivity() {
    private lateinit var discovery: ServerDiscovery
    private lateinit var companion: CompanionClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = ServerDiscovery(this)
        companion = CompanionClient(this)
        companion.startIfSaved()

        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                MobileRootScreen(discovery = discovery, companion = companion)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        companion.disconnect()
        super.onDestroy()
    }
}

private enum class MobileTab(val label: String) {
    LIVE("Ao Vivo"),
    SONGS("Musicas"),
    BIBLE("Biblia"),
    CONNECTION("Conexao"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileRootScreen(
    discovery: ServerDiscovery,
    companion: CompanionClient,
) {
    val ui by companion.state.collectAsState()
    val servers by discovery.servers.collectAsState()
    var activeTab by remember { mutableStateOf(if (ui.connected) MobileTab.LIVE else MobileTab.CONNECTION) }
    var webPagePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.connected) {
        if (ui.connected && activeTab == MobileTab.CONNECTION) {
            activeTab = MobileTab.LIVE
        }
    }

    if (webPagePath != null && ui.host.isNotBlank()) {
        BackHandler { webPagePath = null }
        EmbeddedCompanionWebView(
            title = when (webPagePath) {
                "/qa/admin" -> "Moderacao P&R"
                "/qa/vote" -> "Votacao P&R"
                "/qa" -> "Enviar Pergunta (P&R)"
                else -> "Companion Web"
            },
            url = "${companion.baseHttpUrl()}$webPagePath",
            onClose = { webPagePath = null },
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (ui.connected) "Apresenter · ${ui.host}" else "Apresenter Celular",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == MobileTab.LIVE,
                    onClick = { activeTab = MobileTab.LIVE },
                    icon = { Icon(Icons.Default.Cast, contentDescription = null) },
                    label = { Text(MobileTab.LIVE.label) },
                )
                NavigationBarItem(
                    selected = activeTab == MobileTab.SONGS,
                    onClick = { activeTab = MobileTab.SONGS },
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                    label = { Text(MobileTab.SONGS.label) },
                )
                NavigationBarItem(
                    selected = activeTab == MobileTab.BIBLE,
                    onClick = { activeTab = MobileTab.BIBLE },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = { Text(MobileTab.BIBLE.label) },
                )
                NavigationBarItem(
                    selected = activeTab == MobileTab.CONNECTION,
                    onClick = { activeTab = MobileTab.CONNECTION },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(MobileTab.CONNECTION.label) },
                )
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (activeTab) {
                MobileTab.LIVE -> LiveControlTab(ui = ui, companion = companion)
                MobileTab.SONGS -> SongsTab(ui = ui, companion = companion)
                MobileTab.BIBLE -> BibleTab(ui = ui, companion = companion)
                MobileTab.CONNECTION -> ConnectionTab(
                    ui = ui,
                    servers = servers,
                    companion = companion,
                    onOpenWebPage = { webPagePath = it },
                )
            }
        }
    }
}

@Composable
private fun LiveControlTab(
    ui: CompanionUiState,
    companion: CompanionClient,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LiveStatusCard(ui.liveState)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { companion.sendSimpleCommand("previous_slide") },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Anterior")
                }
                Button(
                    onClick = { companion.sendSimpleCommand("next_slide") },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("Proximo")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { companion.triggerTake() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Ir ao Vivo")
                }
                OutlinedButton(
                    onClick = { companion.sendSimpleCommand("media_play_pause") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Midia")
                }
                Button(
                    onClick = { companion.triggerClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Limpar")
                }
            }
        }
        item {
            Text(
                text = "Roteiro (${ui.schedule.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (ui.schedule.isEmpty()) {
            item {
                Text(
                    text = if (ui.connected) "Nenhum item no roteiro atual." else "Conecte-se ao Apresenter Desktop na aba Conexao.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(ui.schedule, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (item.type == "song") {
                            companion.selectScheduleSong(item)
                        }
                    },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(item.displayText, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = item.type.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStatusCard(live: LiveStateDto?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (live == null || live.contentType == "NONE") "Tela limpa" else "NO AR · ${live.contentType}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            val headline = when {
                live == null -> "Nenhuma projecao ativa no momento."
                !live.songTitle.isNullOrBlank() -> "${live.songTitle}${live.sectionLabel?.let { " · $it" }.orEmpty()}"
                !live.bookName.isNullOrBlank() -> "${live.bookName} ${live.chapter}:${live.verseRange ?: live.verseNumber}"
                !live.announcementText.isNullOrBlank() -> live.announcementText.orEmpty()
                !live.questionText.isNullOrBlank() -> live.questionText.orEmpty()
                else -> live.contentType
            }
            Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val body = live?.lines?.joinToString("\n") ?: live?.verseText
            if (!body.isNullOrBlank()) {
                Text(body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SongsTab(
    ui: CompanionUiState,
    companion: CompanionClient,
) {
    var query by remember { mutableStateOf("") }
    val selected = ui.selectedSong

    if (selected != null) {
        BackHandler { companion.clearSelectedSong() }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(selected.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${selected.songbook} · #${selected.number}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { companion.clearSelectedSong() }) {
                    Text("Voltar")
                }
            }
            SongSectionsList(ui = ui, companion = companion)
        }
        return
    }

    val allSongs: List<Pair<String, SongDto>> = remember(ui.songCatalog, query) {
        val q = query.trim().lowercase()
        ui.songCatalog.songBook.flatMap { book ->
            book.songs
                .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.number.lowercase().contains(q) }
                .map { book.bookName to it }
        }.take(120)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar musica por nome ou numero") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allSongs) { (bookName, song) ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        companion.loadSongDetail(song, bookName)
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(song.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("$bookName · #${song.number}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongSectionsList(
    ui: CompanionUiState,
    companion: CompanionClient,
) {
    val detail = ui.selectedSong ?: return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(detail.sections) { idx, section ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    companion.selectSongSection(detail.number, idx)
                },
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = section.label ?: section.type.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(section.lines.joinToString("\n"))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BibleTab(
    ui: CompanionUiState,
    companion: CompanionClient,
) {
    val catalog = ui.bibleCatalog
    var selectedBook by remember(catalog) { mutableStateOf<BibleBookDto?>(null) }
    var selectedChapterNum by remember { mutableIntStateOf(1) }

    if (catalog == null || catalog.books.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nenhuma Biblia carregada do servidor.")
        }
        return
    }

    val currentBook = selectedBook
    if (currentBook == null) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Biblia ativa: ${catalog.translation}", style = MaterialTheme.typography.labelLarge)
            }
            items(catalog.books, key = { it.bookId }) { book ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedBook = book
                        selectedChapterNum = 1
                        companion.loadBibleChapter(book.bookId, 1)
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(book.bookName, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("${book.chapterTotal} capitulos") },
                    )
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${currentBook.bookName} $selectedChapterNum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { selectedBook = null }) { Text("Livros") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..currentBook.chapterTotal).forEach { ch ->
                    AssistChip(
                        onClick = {
                            selectedChapterNum = ch
                            companion.loadBibleChapter(currentBook.bookId, ch)
                        },
                        label = { Text("$ch") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val chapterData = ui.selectedChapter
                if (chapterData != null && chapterData.bookId == currentBook.bookId) {
                    items(chapterData.verses, key = { it.verse }) { v ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                companion.selectBibleVerse(
                                    bookName = currentBook.bookName,
                                    chapter = chapterData.chapter,
                                    verseNumber = v.verse,
                                    verseText = v.text,
                                )
                            },
                        ) {
                            Text(
                                text = "${v.verse}. ${v.text}",
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionTab(
    ui: CompanionUiState,
    servers: List<DiscoveredServer>,
    companion: CompanionClient,
    onOpenWebPage: (String) -> Unit,
) {
    var hostInput by remember(ui.host) { mutableStateOf(ui.host) }
    var portInput by remember(ui.port) { mutableStateOf(ui.port.toString()) }
    var apiKeyInput by remember(ui.apiKey) { mutableStateOf(ui.apiKey) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ui.statusText, fontWeight = FontWeight.Bold)
                    if (ui.serverVersion.isNotBlank()) {
                        Text("Apresenter Desktop v${ui.serverVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Text("Servidores na rede Wi-Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (servers.isEmpty()) {
            item {
                Text("Procurando Apresenter automaticamente na rede...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(servers) { srv ->
                OutlinedButton(
                    onClick = { companion.connect(srv.host, srv.port, apiKeyInput) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("${srv.name} (${srv.host}:${srv.port})")
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Conexao manual", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("IP do computador") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("Porta") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Chave API (opcional)") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { companion.connect(hostInput, portInput.toIntOrNull() ?: 8765, apiKeyInput) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Conectar")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Dica: No computador, abra Opções → Servidor para conferir o IP da rede Wi-Fi local (ex: 192.168.x.x).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.host.isNotBlank()) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Ferramentas P&R (Web integrada)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onOpenWebPage("/qa") }, modifier = Modifier.weight(1f)) {
                        Text("Enviar P&R")
                    }
                    OutlinedButton(onClick = { onOpenWebPage("/qa/vote") }, modifier = Modifier.weight(1f)) {
                        Text("Votar P&R")
                    }
                    OutlinedButton(onClick = { onOpenWebPage("/qa/admin") }, modifier = Modifier.weight(1f)) {
                        Text("Moderar P&R")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbeddedCompanionWebView(
    title: String,
    url: String,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Clear, contentDescription = "Fechar")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
