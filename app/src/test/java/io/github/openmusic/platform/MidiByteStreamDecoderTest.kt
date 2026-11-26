package io.github.openmusic.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiByteStreamDecoderTest {
    @Test fun runningStatusAndVelocityZeroPreserveChannelAndPitch() {
        val events = mutableListOf<MidiInputEvent>()
        val decoder = MidiByteStreamDecoder(events::add)
        decoder.send(10, 0x92, 60, 100, 64, 90, 60, 0)

        assertEquals(listOf(MidiEventKind.NOTE_ON, MidiEventKind.NOTE_ON, MidiEventKind.NOTE_OFF), events.map { it.kind })
        assertEquals(listOf(60, 64, 60), events.map { it.pitch })
        assertEquals(listOf(100, 90, 0), events.map { it.velocity })
        assertTrue(events.all { it.channel == 2 && it.timestampNanos == 10L })
    }

    @Test fun splitMessageKeepsFirstByteTimestampAndHonorsPacketOffsets() {
        val events = mutableListOf<MidiInputEvent>()
        val decoder = MidiByteStreamDecoder(events::add)
        decoder.send(100, 0x90)
        decoder.send(200, 60)
        assertTrue(events.isEmpty())
        decoder.feed(byteArrayOf(99, 88, 100, 77), 2, 1, 300)
        decoder.send(400, 62, 70)

        assertEquals(2, events.size)
        assertEquals(MidiInputEvent(MidiEventKind.NOTE_ON, 60, 100, 0, 100), events[0])
        assertEquals(MidiInputEvent(MidiEventKind.NOTE_ON, 62, 70, 0, 400), events[1])
    }

    @Test fun realtimeMessagesDoNotInterruptAChordOrPartialNote() {
        val events = mutableListOf<MidiInputEvent>()
        val decoder = MidiByteStreamDecoder(events::add)
        decoder.send(10, 0x90, 60, 0xf8)
        decoder.send(20, 0xfe, 100, 0xf8, 64, 0xfa, 90, 0xfc)

        assertEquals(listOf(60, 64), events.map { it.pitch })
        assertEquals(listOf(10L, 20L), events.map { it.timestampNanos })
        assertTrue(events.all { it.kind == MidiEventKind.NOTE_ON })
    }

    @Test fun sysexAndSystemCommonCancelRunningStatusWithoutGeneratingNotes() {
        val events = mutableListOf<MidiInputEvent>()
        val decoder = MidiByteStreamDecoder(events::add)
        decoder.send(10, 0x90, 60, 100, 0xf0, 0x43, 60, 90, 0xf8)
        decoder.send(20, 64, 100, 0xf7, 65, 100)
        decoder.send(30, 0x90, 67, 100, 0xf1, 0x01, 69, 100)
        decoder.send(40, 0xc0, 7, 9, 0x90, 72, 100)

        assertEquals(listOf(60, 67, 72), events.map { it.pitch })
        assertEquals(listOf(10L, 30L, 40L), events.map { it.timestampNanos })
    }

    @Test fun sustainAndNoteOffAreDeliveredWithoutTreatingOtherCcAsPedal() {
        val events = mutableListOf<MidiInputEvent>()
        val decoder = MidiByteStreamDecoder(events::add)
        decoder.send(10, 0xb3, 64, 127, 7, 80, 64, 0, 0x83, 60, 35)

        assertEquals(listOf(MidiEventKind.PEDAL, MidiEventKind.PEDAL, MidiEventKind.NOTE_OFF), events.map { it.kind })
        assertEquals(listOf(127, 0), events.take(2).map { it.value })
        assertTrue(events.all { it.channel == 3 })
        assertEquals(60, events.last().pitch)
        assertEquals(35, events.last().velocity)
    }

    private fun MidiByteStreamDecoder.send(timestamp: Long, vararg values: Int) {
        val bytes = values.map(Int::toByte).toByteArray()
        feed(bytes, 0, bytes.size, timestamp)
    }
}
