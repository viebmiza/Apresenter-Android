package org.apresenter.core.signage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class SignageDecisionTest {

    @Test
    fun `override beats active schedule block and default playlist`() {
        val manifest = Manifest(
            ownerId = "church-1",
            screen = ScreenSettings(name = "TV Hall"),
            playlists = listOf(
                Playlist(id = "default", name = "Geral", items = listOf(PlaylistItem("a".repeat(64), MediaKind.IMAGE))),
                Playlist(id = "sunday", name = "Domingo", items = listOf(PlaylistItem("b".repeat(64), MediaKind.IMAGE))),
                Playlist(id = "urgent", name = "Urgente", items = listOf(PlaylistItem("c".repeat(64), MediaKind.IMAGE))),
            ),
            schedule = listOf(
                ScheduleBlock(
                    id = "blk-1",
                    days = setOf(Day.SUN),
                    start = ClockTime.of(9, 0),
                    end = ClockTime.of(12, 0),
                    action = BlockAction.Play("sunday"),
                ),
            ),
            defaultPlaylistId = "default",
            timeZone = "UTC",
        )

        val sundayMorning = Instant.parse("2026-11-15T10:00:00Z")
        val scheduled = decide(manifest, override = null, now = sundayMorning)
        assertEquals(Show.PlaylistShow("sunday"), scheduled.show)
        assertEquals(Reason.SCHEDULE, scheduled.reason)

        val withOverride = decide(
            manifest,
            override = ScreenOverride(OverrideAction.Play("urgent"), Until.Released, setAtMs = 0L),
            now = sundayMorning,
        )
        assertEquals(Show.PlaylistShow("urgent"), withOverride.show)
        assertEquals(Reason.OVERRIDE, withOverride.reason)
    }

    @Test
    fun `overnight schedule block covers hours before and after midnight`() {
        val block = ScheduleBlock(
            id = "night",
            days = setOf(Day.SAT),
            start = ClockTime.of(22, 0),
            end = ClockTime.of(6, 0),
            action = BlockAction.TvOff,
        )
        assertTrue(block.covers(LocalDateTime.of(2026, 11, 14, 23, 30))) // Saturday 23:30
        assertTrue(block.covers(LocalDateTime.of(2026, 11, 15, 2, 15)))  // Sunday 02:15
        assertFalse(block.covers(LocalDateTime.of(2026, 11, 15, 7, 0)))  // Sunday 07:00
    }

    @Test
    fun `kiosk pin hashing verifies matching pin and rejects wrong pin`() {
        val hash = KioskPin.hash("1234")
        assertTrue(KioskPin.matches("1234", hash))
        assertFalse(KioskPin.matches("9999", hash))
    }

    @Test
    fun `manifest and server message roundtrip matches signage protocol`() {
        val msg = ServerMessage.Paired(token = "tok-1", ownerId = "o-1", ownerName = "Igreja")
        val encoded = SignageJson.encodeToString(ServerMessage.serializer(), msg)
        val decoded = SignageJson.decodeFromString(ServerMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }
}
