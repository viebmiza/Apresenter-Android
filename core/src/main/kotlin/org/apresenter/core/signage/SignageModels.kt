@file:OptIn(ExperimentalSerializationApi::class)

package org.apresenter.core.signage

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val PROTOCOL_VERSION = 1
const val NUDGE_PORT = 47640
private const val NUDGE_OWNER_CHARS = 16

val SignageJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

fun nudgeOwner(ownerId: String): String = sha256Hex(ownerId.toByteArray()).take(NUDGE_OWNER_CHARS)

fun nudgePlayer(playerId: String): String = nudgeOwner(playerId)

@Serializable
data class Nudge(val owner: String, val port: Int, val player: String? = null)

@Serializable(with = ClockTimeSerializer::class)
@JvmInline
value class ClockTime(val minuteOfDay: Int) : Comparable<ClockTime> {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minute of day out of range: $minuteOfDay" }
    }

    val hour: Int get() = minuteOfDay / MINUTES_PER_HOUR
    val minute: Int get() = minuteOfDay % MINUTES_PER_HOUR

    fun toLocalTime(): LocalTime = LocalTime.of(hour, minute)

    override fun compareTo(other: ClockTime): Int = minuteOfDay.compareTo(other.minuteOfDay)

    override fun toString(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

        fun of(hour: Int, minute: Int): ClockTime = ClockTime(hour * MINUTES_PER_HOUR + minute)

        fun parse(text: String): ClockTime {
            val parts = text.split(':')
            require(parts.size == 2) { "expected HH:mm, got '$text'" }
            val hour = requireNotNull(parts[0].toIntOrNull()) { "bad hour in '$text'" }
            val minute = requireNotNull(parts[1].toIntOrNull()) { "bad minute in '$text'" }
            require(hour in 0..23 && minute in 0..59) { "time out of range: '$text'" }
            return of(hour, minute)
        }
    }
}

internal object ClockTimeSerializer : KSerializer<ClockTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.churchpresenter.signage.ClockTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ClockTime) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): ClockTime = ClockTime.parse(decoder.decodeString())
}

@Serializable
enum class Day(val dayOfWeek: DayOfWeek) {
    @SerialName("mon") MON(DayOfWeek.MONDAY),
    @SerialName("tue") TUE(DayOfWeek.TUESDAY),
    @SerialName("wed") WED(DayOfWeek.WEDNESDAY),
    @SerialName("thu") THU(DayOfWeek.THURSDAY),
    @SerialName("fri") FRI(DayOfWeek.FRIDAY),
    @SerialName("sat") SAT(DayOfWeek.SATURDAY),
    @SerialName("sun") SUN(DayOfWeek.SUNDAY),
    ;

    companion object {
        fun of(dayOfWeek: DayOfWeek): Day = entries.first { it.dayOfWeek == dayOfWeek }
    }
}

@Serializable
data class ScheduleBlock(
    val id: String,
    val days: Set<Day>,
    val start: ClockTime,
    val end: ClockTime,
    val action: BlockAction,
) {
    val crossesMidnight: Boolean get() = end <= start

    fun covers(at: LocalDateTime): Boolean {
        val day = Day.of(at.dayOfWeek)
        val time = ClockTime.of(at.hour, at.minute)
        return if (!crossesMidnight) {
            day in days && time >= start && time < end
        } else {
            (day in days && time >= start) || (Day.of(at.dayOfWeek.minus(1)) in days && time < end)
        }
    }

    fun edgesOn(date: LocalDate, zone: ZoneId): Sequence<Instant> {
        if (Day.of(date.dayOfWeek) !in days) return emptySequence()
        val endDate = if (crossesMidnight) date.plusDays(1) else date
        return sequenceOf(date.atTime(start.toLocalTime()), endDate.atTime(end.toLocalTime()))
            .map { it.atZone(zone).toInstant() }
    }
}

@Serializable
sealed interface BlockAction {
    @Serializable
    @SerialName("play")
    data class Play(val playlistId: String) : BlockAction

    @Serializable
    @SerialName("tv_off")
    data object TvOff : BlockAction

    @Serializable
    @SerialName("service")
    data object Service : BlockAction
}

fun List<ScheduleBlock>.activeAt(now: Instant, zone: ZoneId): ScheduleBlock? {
    val local = LocalDateTime.ofInstant(now, zone)
    return firstOrNull { it.covers(local) }
}

fun List<ScheduleBlock>.nextChangeAfter(after: Instant, zone: ZoneId): Instant? {
    val today = LocalDate.ofInstant(after, zone)
    return (-1L..8L).asSequence()
        .map(today::plusDays)
        .flatMap { date -> asSequence().flatMap { block -> block.edgesOn(date, zone) } }
        .filter { it > after }
        .minOrNull()
}

@Serializable
data class ScreenOverride(
    val action: OverrideAction,
    val until: Until = Until.NextChange,
    val setAtMs: Long,
) {
    fun isActive(now: Instant, schedule: List<ScheduleBlock>, zone: ZoneId): Boolean = when (until) {
        Until.NextChange -> {
            val change = schedule.nextChangeAfter(Instant.ofEpochMilli(setAtMs), zone)
            change == null || now < change
        }
        is Until.Time -> now.toEpochMilli() < until.epochMs
        Until.Released -> true
    }
}

@Serializable
sealed interface OverrideAction {
    @Serializable
    @SerialName("play")
    data class Play(val playlistId: String) : OverrideAction

    @Serializable
    @SerialName("tv_off")
    data object TvOff : OverrideAction
}

@Serializable
sealed interface Until {
    @Serializable
    @SerialName("next_change")
    data object NextChange : Until

    @Serializable
    @SerialName("time")
    data class Time(val epochMs: Long) : Until

    @Serializable
    @SerialName("released")
    data object Released : Until
}

@Serializable
data class Manifest(
    val protocol: Int = PROTOCOL_VERSION,
    val ownerId: String,
    val screen: ScreenSettings,
    val playlists: List<Playlist> = emptyList(),
    val schedule: List<ScheduleBlock> = emptyList(),
    val defaultPlaylistId: String? = null,
    val idleCard: IdleCard = IdleCard(),
    val timeZone: String,
    val diagnostics: Boolean = true,
) {
    fun playlist(id: String?): Playlist? = id?.let { wanted -> playlists.firstOrNull { it.id == wanted } }

    fun fileHashes(): Set<String> = buildSet {
        playlists.forEach { playlist -> playlist.items.filterNot { it.kind.isLink }.forEach { add(it.sha256) } }
        idleCard.logoSha256?.let(::add)
    }
}

@Serializable
data class ScreenSettings(
    val name: String,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val width: Int = 1920,
    val height: Int = 1080,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kioskPinHash: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kioskGesture: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kioskGesturePlaylistId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val message: ScreenMessage? = null,
)

@Serializable
data class ScreenMessage(
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fullScreen: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val untilMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blockTouch: Boolean = false,
) {
    fun showsAt(nowMs: Long): Boolean = text.isNotBlank() && (untilMs == null || nowMs < untilMs)
}

@Serializable
enum class Orientation {
    @SerialName("landscape") LANDSCAPE,
    @SerialName("portrait") PORTRAIT,
    @SerialName("landscape_flipped") LANDSCAPE_FLIPPED,
    @SerialName("portrait_flipped") PORTRAIT_FLIPPED,
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val items: List<PlaylistItem> = emptyList(),
)

@Serializable
data class PlaylistItem(
    val sha256: String = "",
    val kind: MediaKind,
    val durationMs: Long? = null,
    val transition: Transition = Transition.CROSSFADE,
    val transitionMs: Long = DEFAULT_TRANSITION_MS,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val refreshMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val maxFps: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val interactive: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val idleResetMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val allowedHosts: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val idlePlaylistId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val idleUrl: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val idleRefreshMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val idlePrompt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pageCss: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pageScript: String? = null,
) {
    companion object {
        const val DEFAULT_TRANSITION_MS = 800L
        const val DEFAULT_DURATION_MS = 10_000L
    }
}

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("stream") STREAM,
    @SerialName("web") WEB,
    ;

    val isLink: Boolean get() = this == STREAM || this == WEB
}

@Serializable
enum class Transition {
    @SerialName("crossfade") CROSSFADE,
    @SerialName("cut") CUT,
}

@Serializable
data class IdleCard(
    val title: String = "",
    val logoSha256: String? = null,
)

data class ScreenDecision(
    val show: Show,
    val tv: TvPower,
    val reason: Reason,
)

sealed interface Show {
    data class PlaylistShow(val playlistId: String) : Show
    data object Idle : Show
    data object Blank : Show
}

enum class TvPower { ON, OFF, LEAVE }
enum class Reason { OVERRIDE, SCHEDULE, DEFAULT }

fun decide(
    manifest: Manifest,
    override: ScreenOverride?,
    now: Instant,
    isPlayable: (Playlist) -> Boolean = { it.items.isNotEmpty() },
): ScreenDecision {
    val zone = manifest.zoneOrUtc()

    fun play(playlistId: String?, reason: Reason, tv: TvPower): ScreenDecision {
        val wanted = manifest.playlist(playlistId)?.takeIf(isPlayable)
        val chosen = wanted ?: manifest.playlist(manifest.defaultPlaylistId)?.takeIf(isPlayable)
        val show = chosen?.let { Show.PlaylistShow(it.id) } ?: Show.Idle
        return ScreenDecision(show, tv, reason)
    }

    if (override != null && override.isActive(now, manifest.schedule, zone)) {
        return when (val action = override.action) {
            is OverrideAction.Play -> play(action.playlistId, Reason.OVERRIDE, TvPower.ON)
            OverrideAction.TvOff -> ScreenDecision(Show.Blank, TvPower.OFF, Reason.OVERRIDE)
        }
    }
    return when (val action = manifest.schedule.activeAt(now, zone)?.action) {
        is BlockAction.Play -> play(action.playlistId, Reason.SCHEDULE, TvPower.ON)
        BlockAction.TvOff -> ScreenDecision(Show.Blank, TvPower.OFF, Reason.SCHEDULE)
        BlockAction.Service -> play(manifest.defaultPlaylistId, Reason.SCHEDULE, TvPower.LEAVE)
        null -> play(manifest.defaultPlaylistId, Reason.DEFAULT, TvPower.ON)
    }
}

fun Manifest.zoneOrUtc(): ZoneId = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("UTC"))

@Serializable
enum class Platform {
    @SerialName("linux_arm64") LINUX_ARM64,
    @SerialName("linux_x64") LINUX_X64,
    @SerialName("windows_x64") WINDOWS_X64,
    @SerialName("android_tv") ANDROID_TV,
}

@Serializable
enum class CecState {
    @SerialName("unknown") UNKNOWN,
    @SerialName("working") WORKING,
    @SerialName("unavailable") UNAVAILABLE,
}

object Features {
    const val PREVIEW = "preview"
    const val WIPE = "wipe"
    const val FACTORY_RESET = "factory_reset"
    const val STREAM = "stream"
    const val WEB = "web"
    const val ROTATION = "rotation"
    const val MESSAGE = "message"
}

@Serializable
sealed interface PlayerMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val playerId: String,
        val agentVersion: String,
        val protocolMin: Int,
        val protocolMax: Int,
        val manifestVersion: String? = null,
        val screenWidth: Int,
        val screenHeight: Int,
        val platform: Platform,
        val pairingCode: String? = null,
        val features: List<String> = emptyList(),
    ) : PlayerMessage

    @Serializable
    @SerialName("status")
    data class Status(
        val showingPlaylistId: String? = null,
        val showingItemSha256: String? = null,
        val reason: String? = null,
        val lastSyncMs: Long? = null,
        val freeDiskBytes: Long? = null,
        val cec: CecState = CecState.UNKNOWN,
        val errors: List<String> = emptyList(),
        val cpuPercent: Int? = null,
        val memoryPercent: Int? = null,
        val temperatureC: Double? = null,
        val uptimeSeconds: Long? = null,
        val interactive: Boolean = false,
        val systemUpdates: Int? = null,
    ) : PlayerMessage

    @Serializable
    @SerialName("preview")
    data class Preview(
        val jpegBase64: String,
        val width: Int,
        val height: Int,
        val takenAtMs: Long,
    ) : PlayerMessage
}

@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("unsupported")
    data class Unsupported(val serverProtocol: Int) : ServerMessage

    @Serializable
    @SerialName("pairing_pending")
    data class PairingPending(val code: String) : ServerMessage

    @Serializable
    @SerialName("paired")
    data class Paired(val token: String, val ownerId: String, val ownerName: String) : ServerMessage

    @Serializable
    @SerialName("pairing_rejected")
    data object PairingRejected : ServerMessage

    @Serializable
    @SerialName("released")
    data object Released : ServerMessage

    @Serializable
    @SerialName("manifest_changed")
    data class ManifestChanged(val version: String) : ServerMessage

    @Serializable
    @SerialName("override")
    data class SetOverride(val override: ScreenOverride?) : ServerMessage

    @Serializable
    @SerialName("identify")
    data class Identify(val seconds: Int = 10) : ServerMessage

    @Serializable
    @SerialName("refresh")
    data object Refresh : ServerMessage

    @Serializable
    @SerialName("wipe")
    data object Wipe : ServerMessage

    @Serializable
    @SerialName("factory_reset")
    data object FactoryReset : ServerMessage

    @Serializable
    @SerialName("tv")
    data class Tv(val on: Boolean) : ServerMessage
}

object KioskPin {
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 8
    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256
    private const val PARTS = 4
    private const val HEX = 16

    fun isValid(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)): String {
        require(isValid(pin)) { "a PIN is $MIN_DIGITS to $MAX_DIGITS digits" }
        return "$PREFIX:$ITERATIONS:${salt.hex()}:${derive(pin, salt, ITERATIONS).hex()}"
    }

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        val iterations = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        val salt = parts.getOrNull(2)?.unhex()
        val expected = parts.getOrNull(3)?.unhex()
        val ours = parts.size == PARTS && parts[0] == PREFIX && isValid(pin)
        return ours && iterations != null && salt != null && expected != null &&
            MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)).encoded

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? =
        if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' }) null
        else chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
}
