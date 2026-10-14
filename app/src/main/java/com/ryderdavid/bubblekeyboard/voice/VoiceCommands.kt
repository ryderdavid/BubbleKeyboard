package com.ryderdavid.bubblekeyboard.voice

/**
 * Spoken commands: "voice bar" followed by a command, said as the last thing before a short pause
 * ("…see you at six. Voice bar, send."). The command can be worded loosely: each one has a set of
 * ways to say it. Unrecognized words after "voice bar" are just text.
 */
object VoiceCommands {
    enum class Command { DONE, SEND, KEYBOARD, CLEANUP, CANCEL, HIDE }

    /** What was heard: the command, and how many words (from "voice" on) it took to say. */
    data class Heard(val command: Command, val words: Int)

    private val SAYINGS: Map<String, Command> = buildMap {
        fun say(c: Command, vararg s: String) = s.forEach { put(it.replace("'", ""), c) }
        say(Command.DONE, "done", "im done", "finish", "finished", "finish up", "stop", "stop listening", "end", "that's it",
            "thats it", "that's all", "thats all", "over", "complete", "wrap up", "wrap it up", "enter", "ok", "okay", "all done")
        say(Command.SEND, "send", "send it", "send this", "send that", "send message", "send the message", "send now",
            "submit", "post", "post it", "reply", "go", "ship it", "fire", "fire away")
        say(Command.KEYBOARD, "keyboard", "type", "typing", "let me type", "abc", "gboard", "g board", "switch",
            "switch keyboard", "switch keyboards", "letters", "open keyboard", "show keyboard", "keys")
        say(Command.CLEANUP, "cleanup", "clean up", "clean", "clean it up", "clean that up", "polish", "polish it", "polish that",
            "tidy", "tidy up", "fix", "fix it", "fix that", "fix it up", "proofread", "cleanup now", "clean up now")
        say(Command.HIDE, "close", "hide", "hide keyboard", "close keyboard", "hide the keyboard", "close the keyboard",
            "dismiss", "go away", "minimize", "put it away")
        say(Command.CANCEL, "cancel", "never mind", "nevermind", "scratch that", "scrap that", "scrap it", "discard",
            "delete", "delete that", "delete it", "throw it away", "throw that away", "forget it", "forget that", "abort",
            "erase", "erase that", "start over", "cancel that")
    }

    private val FILLER_BEFORE = setOf("and", "please", "uh", "um", "now", "okay", "ok")
    private val FILLER_AFTER = setOf("please", "now", "thanks", "thank you", "thankyou")

    private fun norm(word: String) = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }.trim('\'')

    /**
     * "Voice bar" near the end of what's been said: the text before it ([before], for the field),
     * what's been said after it ([said], for the pill), the command if that's one yet, and how
     * many words from "voice" on.
     */
    data class Pending(val before: String, val said: String, val command: Command?, val words: Int)

    fun pending(text: String): Pending? {
        val raw = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val w = raw.map(::norm)
        for (i in w.indices.reversed()) {
            if (w.size - i > MAX_COMMAND_WORDS + 2) break
            val wakeLen = wakeAt(w, i)
            if (wakeLen == 0) continue
            val before = raw.take(i).joinToString(" ").trimEnd { it.isWhitespace() || it in ",;:-–—" }
            val said = raw.drop(i + wakeLen).joinToString(" ").trim { !it.isLetterOrDigit() && it != '\'' }
            return Pending(before, said, atEnd(text)?.command, raw.size - i)
        }
        return null
    }

    private fun wakeAt(w: List<String>, i: Int) = when {
        w[i] == "voicebar" || w[i] == "voicebars" -> 1
        w[i] == "voice" && i + 1 < w.size && (w[i + 1] == "bar" || w[i + 1] == "bars") -> 2
        else -> 0
    }

    /** The command [text] ends with, if it ends with "voice bar" and a command. */
    fun atEnd(text: String): Heard? {
        val raw = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val w = raw.map(::norm)
        // the last "voice bar" (or "voicebar"), with at most a few words after it
        for (i in w.indices.reversed()) {
            if (w.size - i > MAX_COMMAND_WORDS + 2) break
            val wakeLen = wakeAt(w, i)
            if (wakeLen == 0) continue
            var cmd = w.drop(i + wakeLen).filter { it.isNotEmpty() }
            while (cmd.isNotEmpty() && cmd.first() in FILLER_BEFORE && cmd.size > 1) cmd = cmd.drop(1)
            while (cmd.isNotEmpty() && cmd.last() in FILLER_AFTER && cmd.size > 1) cmd = cmd.dropLast(1)
            if (cmd.size >= 2 && cmd.takeLast(2).joinToString(" ") == "thank you") cmd = cmd.dropLast(2)
            val said = cmd.joinToString(" ").replace("'", "")
            val c = SAYINGS[said] ?: return null
            return Heard(c, raw.size - i)
        }
        return null
    }

    private const val MAX_COMMAND_WORDS = 4
}
