package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsDevicesTest {
    private fun labels(o: List<StatsDeviceOption>) = o.map { it.label }

    @Test
    fun noPeersMeansNoDropdown() {
        assertTrue(statsDeviceOptions(emptyList(), listOf(SyncModel.Device("a", "Mac"))).isEmpty())
    }

    @Test
    fun namesFromRosterAndRemovedMarker() {
        val roster = listOf(SyncModel.Device("m1", "Mac"), SyncModel.Device("p1", "Pad"))
        val o = statsDeviceOptions(listOf("m1", "gone"), roster)
        assertEquals(listOf("全部设备", "本机", "Mac", "已移除的设备（gone）"), labels(o))
        assertEquals(listOf("all", "self", "m1", "gone"), o.map { it.value })
    }

    @Test
    fun missingRosterShowsIds() {
        assertEquals(listOf("全部设备", "本机", "m1"), labels(statsDeviceOptions(listOf("m1"), null)))
        assertEquals(listOf("全部设备", "本机", "m1"), labels(statsDeviceOptions(listOf("m1"), emptyList())))
    }

    @Test
    fun unreadableOrBlankNameFallsBackToId() {
        val roster = listOf(
            SyncModel.Device("a", "", readable = true),
            SyncModel.Device("b", "", readable = false),
        )
        assertEquals(listOf("全部设备", "本机", "a", "b"), labels(statsDeviceOptions(listOf("a", "b"), roster)))
    }

    @Test
    fun peersSortedByLabel() {
        val roster = listOf(SyncModel.Device("x", "Zed"), SyncModel.Device("y", "Alpha"))
        assertEquals(listOf("Alpha", "Zed"), labels(statsDeviceOptions(listOf("x", "y"), roster)).drop(2))
    }

    @Test
    fun vanishedSelectionFallsBackToAll() {
        val o = statsDeviceOptions(listOf("m1"), null)
        assertEquals("m1", statsDeviceOrAll("m1", o))
        assertEquals("self", statsDeviceOrAll("self", o))
        assertEquals("all", statsDeviceOrAll("gone", o))
        assertEquals("all", statsDeviceOrAll("m1", emptyList()))
    }
}
