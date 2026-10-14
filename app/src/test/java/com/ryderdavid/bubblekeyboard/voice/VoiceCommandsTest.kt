package com.ryderdavid.bubblekeyboard.voice

import com.ryderdavid.bubblekeyboard.voice.VoiceCommands.Command
import com.ryderdavid.bubblekeyboard.voice.VoiceCommands.Heard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCommandsTest {
    @Test fun theFiveCommands() {
        assertEquals(Heard(Command.DONE, 3), VoiceCommands.atEnd("see you at six. Voice bar, done."))
        assertEquals(Heard(Command.SEND, 3), VoiceCommands.atEnd("see you at six voice bar send"))
        assertEquals(Heard(Command.KEYBOARD, 3), VoiceCommands.atEnd("the code is Voice Bar keyboard"))
        assertEquals(Heard(Command.CLEANUP, 4), VoiceCommands.atEnd("so anyway Voice bar, clean up."))
        assertEquals(Heard(Command.CANCEL, 3), VoiceCommands.atEnd("ugh no. Voice bar cancel!"))
    }

    @Test fun looseWordingAndFiller() {
        assertEquals(Command.SEND, VoiceCommands.atEnd("ok voice bar, send it please")?.command)
        assertEquals(Command.CANCEL, VoiceCommands.atEnd("voice bar never mind")?.command)
        assertEquals(Command.CANCEL, VoiceCommands.atEnd("Voice bar, scratch that.")?.command)
        assertEquals(Command.DONE, VoiceCommands.atEnd("Voice bar, that's it.")?.command)
        assertEquals(Command.KEYBOARD, VoiceCommands.atEnd("Voicebar, let me type")?.command)
        assertEquals(Command.CLEANUP, VoiceCommands.atEnd("Voice-bar polish")?.command)
        assertEquals(Heard(Command.SEND, 2), VoiceCommands.atEnd("Voicebar send."))
        assertEquals(Heard(Command.DONE, 6), VoiceCommands.atEnd("Voice bar, and done, thank you."))
    }

    @Test fun onlyAtTheEndAndOnlyRealCommands() {
        assertNull(VoiceCommands.atEnd("voice bar send me the address"))
        assertNull(VoiceCommands.atEnd("I'm done"))
        assertNull(VoiceCommands.atEnd("send it"))
        assertNull(VoiceCommands.atEnd("I love voice bar"))
        assertNull(VoiceCommands.atEnd("the voice bar is great"))
        assertNull(VoiceCommands.atEnd(""))
    }
}
