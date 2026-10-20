package com.ryderdavid.voicebar.voice

/**
 * What's on screen while you dictate (the chat you're replying to, the email thread), read once
 * when dictation starts. It helps two ways: names on screen go to speech recognition as keyterms
 * and its tail as context, and the cleanup sees it to spell names as they appear and to pick the
 * right word among sound-alikes. Pure, so it can be tested.
 */
object ScreenContext {
    /** One piece of text on screen, where it is. */
    data class Piece(val text: String, val top: Int, val left: Int, val field: Boolean = false)

    data class Snapshot(val text: String, val app: String = "") {
        val names: List<String> by lazy { names(text) }
        /** For speech recognition: the end of it (the latest messages), short. */
        val recent: String get() = tail(text, RECENT_CHARS)
        /** For the cleanup prompt, or "" with nothing on screen. */
        val prompt: String get() = prompt(text, app)
        val isEmpty get() = text.isBlank()
    }

    /** Kept small, as other dictation apps do: the header and the latest messages, not the whole screen. */
    const val MAX_CHARS = 1200
    /** The top of the screen kept when it's long: a chat's header has who you're talking to. */
    const val HEAD_CHARS = 200
    const val RECENT_CHARS = 800
    const val MAX_NAMES = 20

    /** Reading order (top to bottom, then left to right), repeats dropped, trimmed to [MAX_CHARS]. */
    fun build(pieces: List<Piece>, app: String = ""): Snapshot {
        val seen = HashSet<String>()
        val lines = pieces.sortedWith(compareBy({ it.top }, { it.left }))
            .map { it.text.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && seen.add(it) }
        return Snapshot(trim(lines.joinToString("\n")), app)
    }

    fun trim(text: String): String =
        if (text.length <= MAX_CHARS) text
        else text.take(HEAD_CHARS).substringBeforeLast('\n', text.take(HEAD_CHARS)) + "\n…\n" +
            tail(text, MAX_CHARS - HEAD_CHARS - 3)

    fun tail(text: String, n: Int): String =
        if (text.length <= n) text else text.takeLast(n).let { t -> t.substringAfter('\n', t).ifBlank { t } }

    private val WORD = Regex("[\\p{L}][\\p{L}\\p{M}'’-]*[\\p{L}]|[\\p{L}]")

    /**
     * Words that look like names or terms: Capitalized (Siona), CamelCase (iPhone, McAllister) or
     * short ALL CAPS (SRP), not everyday words. A capitalized word that also shows up in lowercase
     * on screen is an ordinary word at the start of a sentence. Most frequent first.
     */
    fun names(text: String): List<String> {
        // each word, and whether it opens a sentence in a longer line (capitalized there by grammar);
        // a short line (a chat header, a sender's name) is taken as written
        val found = ArrayList<Pair<String, Boolean>>()
        for (line in text.lines()) {
            val short = WORD.findAll(line).count() <= 3
            var opening = true
            var last = 0
            for (m in WORD.findAll(line)) {
                if (Regex("[.!?:]\\s*$").containsMatchIn(line.substring(last, m.range.first))) opening = true
                val w = m.value.trim('\'', '’', '-')
                if (w.isNotEmpty()) found += w to (opening && !short)
                opening = false
                last = m.range.last + 1
            }
        }
        val words = found.map { it.first }
        val lower = words.filter { it.first().isLowerCase() }.map { it.lowercase() }.toHashSet()
        // capitalized somewhere a sentence doesn't start: a name
        val midCaps = found.filter { (w, opening) -> !opening && w.first().isUpperCase() }.map { it.first.lowercase() }.toHashSet()
        val counts = LinkedHashMap<String, Int>()
        for ((w, opening) in found) {
            if (opening && w.lowercase() !in midCaps && w.drop(1).none { it.isUpperCase() }) continue
            if (w.length < 2 || w.length > 20) continue
            val caps = w.all { !it.isLetter() || it.isUpperCase() }
            val looks = when {
                caps -> w.length in 2..6
                w.first().isUpperCase() -> true
                else -> w.drop(1).any { it.isUpperCase() } // iPhone, eBay
            }
            if (!looks) continue
            val l = w.lowercase()
            if (l in COMMON || (!caps && l in lower)) continue
            if (caps && l in COMMON_CAPS) continue
            counts[w] = (counts[w] ?: 0) + 1
        }
        // one spelling per word: the most frequent
        val byLower = LinkedHashMap<String, Pair<String, Int>>()
        for ((w, c) in counts) {
            val k = w.lowercase()
            val prev = byLower[k]
            byLower[k] = if (prev == null) w to c else (if (c > prev.second) w else prev.first) to (prev.second + c)
        }
        return byLower.values.sortedByDescending { it.second }.map { it.first }.take(MAX_NAMES)
    }

    /** The cleanup prompt's screen section. Untrusted text: it must never steer the model. */
    fun prompt(text: String, app: String = ""): String = if (text.isBlank()) "" else
        "<screen>\n" + (if (app.isNotBlank()) "App: ${app.replace("</screen>", "")}\n" else "") +
            text.replace("</screen>", "") + "\n</screen>"

    /**
     * The recognizer's lead-in from the text before the cursor: just the last sentence (at most
     * [LEAD_CHARS]), or null. More than that and it starts carrying on from it instead of listening.
     */
    fun leadIn(before: String): String? {
        val t = before.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        // the sentence still being written, else the last finished one
        val body = t.trimEnd('.', '!', '?', ' ')
        val start = Regex("[.!?]\\s").findAll(body).lastOrNull()?.range?.last?.plus(1) ?: 0
        val sentence = t.substring(start).trim()
        return (if (sentence.length <= LEAD_CHARS) sentence else sentence.takeLast(LEAD_CHARS).substringAfter(' ')).ifBlank { null }
    }

    const val LEAD_CHARS = 160

    /** Keyterms for one dictation: names on screen first (they're about this message), then the dictionary. */
    fun keyterms(screen: List<String>, dictionary: List<String>, max: Int = 50): List<String> {
        val out = LinkedHashMap<String, String>()
        for (t in screen.take(MAX_NAMES) + dictionary) {
            if (t.length !in 1..20) continue
            out.putIfAbsent(t.lowercase(), t)
            if (out.size == max) break
        }
        return out.values.toList()
    }

    private val COMMON_CAPS = setOf("ok", "am", "pm", "us", "it", "a", "i", "id", "tv", "lol", "omg", "btw", "fyi", "asap", "eta", "faq", "new", "gif", "url", "pdf", "edt", "pdt", "mst", "est", "pst", "utc")

    /** Everyday words and app chrome that are often capitalized but aren't names. */
    private val COMMON = """
        the a an i i'm i've i'll i'd im ive ill id me my mine we our us you your yours he him his she her hers they them their it its
        this that these those there here what when where why who whom whose which how yes no not ok okay sure yeah yep nope hey hi hello
        thanks thank please sorry good great nice cool awesome love lol haha and or but so if then because also just only really very
        is are was were be been being am do does did done have has had will would can could should may might must shall let lets let's
        got get go going gone come came see saw look looks maybe well oh ah um uh wow omg congrats happy merry
        all any some every each both either neither one two three four five six seven eight nine ten first last next new old other
        today tomorrow yesterday tonight now later soon morning afternoon evening night week weekend month year day days
        monday tuesday wednesday thursday friday saturday sunday mon tue tues wed thu thur thurs fri sat sun
        january february march april may june july august september october november december jan feb mar apr jun jul aug sep sept oct nov dec
        send sent reply forward search message messages chat chats call calls video photo photos image camera attach attachment file files
        inbox archive delete edit share copy paste select more menu settings back home open close cancel done save undo
        type typing online offline active seen delivered read unread typing… mute muted notifications notification
        to from cc bcc subject re fwd fw sms mms rcs text texts new group groups contact contacts profile info details
        in on at by for with about of off out up down over under into onto as like via per
        mr mrs ms dr st ave rd
    """.trim().split(Regex("\\s+")).toHashSet()
}
