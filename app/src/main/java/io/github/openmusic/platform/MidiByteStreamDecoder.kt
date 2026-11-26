package io.github.openmusic.platform

enum class MidiEventKind { NOTE_ON, NOTE_OFF, PEDAL }

/** MIDI channel is zero based. The timestamp uses the System.nanoTime time base. */
data class MidiInputEvent(
    val kind: MidiEventKind,
    val pitch: Int,
    val velocity: Int,
    val channel: Int,
    val timestampNanos: Long,
    val value: Int = 0,
)

/** Stateful MIDI 1.0 framing: handles running status, split packets, SysEx and real-time bytes. */
internal class MidiByteStreamDecoder(private val emit: (MidiInputEvent) -> Unit) {
    private var runningStatus = 0
    private var status = 0
    private var needed = 0
    private var received = 0
    private val data = IntArray(2)
    private var inSysEx = false
    private var messageTime = 0L

    @Synchronized
    fun feed(bytes: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
        for (index in offset until offset + count) {
            val value = bytes[index].toInt() and 0xff
            if (value >= 0xf8) continue // Real-time messages can interrupt any other message.
            if (value and 0x80 != 0) {
                if (value == 0xf0) {
                    inSysEx = true
                    runningStatus = 0
                    status = 0
                    received = 0
                    continue
                }
                if (value == 0xf7) {
                    inSysEx = false
                    status = 0
                    received = 0
                    continue
                }
                inSysEx = false
                status = value
                runningStatus = if (value < 0xf0) value else 0
                received = 0
                messageTime = timestampNanos
                needed = when {
                    value < 0xf0 -> if (value and 0xf0 == 0xc0 || value and 0xf0 == 0xd0) 1 else 2
                    value == 0xf1 || value == 0xf3 -> 1
                    value == 0xf2 -> 2
                    else -> 0
                }
                continue
            }
            if (inSysEx) continue
            if (status == 0) {
                if (runningStatus == 0) continue
                status = runningStatus
                needed = if (status and 0xf0 == 0xc0 || status and 0xf0 == 0xd0) 1 else 2
                received = 0
                messageTime = timestampNanos
            }
            if (needed == 0) continue
            data[received++] = value
            if (received == needed) {
                val channel = status and 0x0f
                when (status and 0xf0) {
                    0x80 -> emit(MidiInputEvent(MidiEventKind.NOTE_OFF, data[0], data[1], channel, messageTime))
                    0x90 -> emit(MidiInputEvent(
                        if (data[1] == 0) MidiEventKind.NOTE_OFF else MidiEventKind.NOTE_ON,
                        data[0], data[1], channel, messageTime,
                    ))
                    0xb0 -> if (data[0] == 64) emit(MidiInputEvent(
                        MidiEventKind.PEDAL, 0, 0, channel, messageTime, data[1],
                    ))
                }
                status = 0
                received = 0
            }
        }
    }
}
