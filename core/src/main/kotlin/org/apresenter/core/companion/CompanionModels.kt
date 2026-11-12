@file:OptIn(ExperimentalSerializationApi::class)

package org.apresenter.core.companion

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val CompanionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
data class WebSocketMessage(
    val type: String,
    val payload: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val commandId: String? = null,
)

@Serializable
data class DevicePermissionsDto(
    val controlOutput: Boolean = true,
    val editSchedule: Boolean = true,
)

@Serializable
data class StatusResponse(
    val appVersion: String = "",
    val endpoints: List<String> = emptyList(),
    val bibles: List<String> = emptyList(),
    val songbooks: List<String> = emptyList(),
    val permissions: DevicePermissionsDto = DevicePermissionsDto(),
)

@Serializable
data class SongDto(
    val id: Int = 0,
    val number: String = "",
    val title: String = "",
    val tune: String = "",
    val author: String = "",
    val secondaryTitle: String = "",
)

@Serializable
data class SongbookEntry(
    @SerialName("book-name") val bookName: String = "",
    @SerialName("song-total") val songTotal: Int = 0,
    val songs: List<SongDto> = emptyList(),
)

@Serializable
data class SongCatalogResponse(
    @SerialName("song-book") val songBook: List<SongbookEntry> = emptyList(),
    val songBooks: Int = 0,
    val total: Int = 0,
)

@Serializable
data class SongSectionDto(
    val type: String = "verse",
    val lines: List<String> = emptyList(),
    val label: String? = null,
    val key: String? = null,
)

@Serializable
data class SongDetailDto(
    val number: String = "",
    val title: String = "",
    val songbook: String = "",
    val tune: String = "",
    val author: String = "",
    val composer: String = "",
    @SerialName("section-total") val sectionTotal: Int = 0,
    val sections: List<SongSectionDto> = emptyList(),
    val order: List<String> = emptyList(),
    val notes: String = "",
    val cues: Map<String, String> = emptyMap(),
    val playOrder: List<String> = emptyList(),
)

@Serializable
data class ScheduleSongDto(
    val id: String,
    val songNumber: Int,
    val title: String,
    val songbook: String,
)

@Serializable
data class ScheduleItemDto(
    val id: String,
    val type: String,
    val displayText: String,
    val songNumber: Int? = null,
    val title: String? = null,
    val songbook: String? = null,
    val bookName: String? = null,
    val chapter: Int? = null,
    val verseNumber: Int? = null,
    val verseRange: String? = null,
    val text: String? = null,
    val mediaUrl: String? = null,
    val mediaTitle: String? = null,
    val url: String? = null,
)

@Serializable
data class ScheduleResponse(
    val items: List<ScheduleItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class LiveStateDto(
    val contentType: String = "NONE",
    val bookName: String? = null,
    val chapter: Int? = null,
    val verseNumber: Int? = null,
    val verseRange: String? = null,
    val verseText: String? = null,
    val songTitle: String? = null,
    val songNumber: Int? = null,
    val sectionType: String? = null,
    val sectionLabel: String? = null,
    val lines: List<String>? = null,
    val songSectionIndex: Int? = null,
    val songLineIndex: Int? = null,
    val announcementText: String? = null,
    val websiteTitle: String? = null,
    val websiteUrl: String? = null,
    val sceneName: String? = null,
    val questionText: String? = null,
)

@Serializable
data class BibleChapterDto(
    val chapter: Int,
    @SerialName("verse-total") val verseTotal: Int,
)

@Serializable
data class BibleBookDto(
    @SerialName("book-id") val bookId: Int,
    @SerialName("book-name") val bookName: String,
    @SerialName("chapter-total") val chapterTotal: Int,
    val chapters: List<BibleChapterDto> = emptyList(),
)

@Serializable
data class BibleCatalogResponse(
    val translation: String = "",
    val books: List<BibleBookDto> = emptyList(),
    @SerialName("book-total") val bookTotal: Int = 0,
    @SerialName("verse-total") val verseTotal: Int = 0,
)

@Serializable
data class BibleVerseDto(
    val verse: Int,
    val text: String,
)

@Serializable
data class BibleChapterResponse(
    val translation: String = "",
    @SerialName("book-id") val bookId: Int = 0,
    @SerialName("book-name") val bookName: String = "",
    val chapter: Int = 0,
    @SerialName("verse-total") val verseTotal: Int = 0,
    val verses: List<BibleVerseDto> = emptyList(),
)

@Serializable
data class SelectSongSectionRequest(
    val number: String,
    val section: Int,
    val lineIndex: Int = -1,
)

@Serializable
data class SelectBibleVerseRequest(
    val bookName: String,
    val chapter: Int,
    val verseNumber: Int,
    val verseText: String = "",
    val verseRange: String = "",
)
