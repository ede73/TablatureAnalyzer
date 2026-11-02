package org.tablatureanalyzer.app

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver

/** Own the output explicitly; an unconnected sequencer does not choose a synthesizer. */
internal class DesktopMidiOutput private constructor(
    private val device: MidiDevice,
    val receiver: Receiver
) : AutoCloseable {
    override fun close() {
        try { receiver.close() } finally { device.close() }
    }

    companion object {
        fun open(): DesktopMidiOutput {
            var lastFailure: Exception? = null
            // The Windows software MIDI device does not require Gervill to open
            // its hard-coded stereo PCM line, which some Windows drivers reject.
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                for (name in listOf("Microsoft GS Wavetable Synth", "Microsoft MIDI Mapper")) {
                    val info = MidiSystem.getMidiDeviceInfo().firstOrNull { it.name == name } ?: continue
                    val device = MidiSystem.getMidiDevice(info)
                    try {
                        device.open()
                        return DesktopMidiOutput(device, device.receiver)
                    } catch (failure: Exception) {
                        device.close()
                        lastFailure = failure
                    }
                }
            }
            val synth = MidiSystem.getSynthesizer()
            try {
                synth.open()
                return DesktopMidiOutput(synth, synth.receiver)
            } catch (failure: Exception) {
                synth.close()
                lastFailure?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
