package com.ryderdavid.bubblekeyboard.handsfree

/** Something hands-free mode does to the phone. [say] is the short note the pill shows ("Tap Send"). */
sealed class Action {
    abstract val say: String
    /** Sends, buys, deletes, posts: asked about first. */
    open val risky: Boolean get() = false

    data class Tap(val id: Int? = null, val x: Int? = null, val y: Int? = null, val long: Boolean = false,
                   override val say: String = "", override val risky: Boolean = false) : Action()
    data class Type(val text: String, val id: Int? = null, val submit: Boolean = false,
                    override val say: String = "", override val risky: Boolean = false) : Action()
    /** Sets the field to [text] exactly ("" clears it). */
    data class SetText(val text: String, val id: Int? = null, override val say: String = "") : Action()
    data class Focus(val id: Int, override val say: String = "") : Action()
    data class Scroll(val direction: Dir, val id: Int? = null, val amount: Amount = Amount.Step, override val say: String = "") : Action()
    data class Swipe(val direction: Dir, override val say: String = "") : Action()
    data class Drag(val x1: Int, val y1: Int, val x2: Int, val y2: Int, override val say: String = "") : Action()
    data class Pinch(val zoomIn: Boolean, override val say: String = "") : Action()
    data class Global(val what: System, override val say: String = "") : Action()
    data class OpenApp(val name: String, override val say: String = "") : Action()
    /** Moves the on-screen cursor only. */
    data class Cursor(val dx: Int, val dy: Int, override val say: String = "") : Action()
    /** Nothing to do; [say] explains (goal finished, can't find it). */
    data class Done(override val say: String) : Action()

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** How far a scroll goes. */
    sealed class Amount {
        /** The app's own scroll step. */
        data object Step : Amount()
        /** Exactly this many heights (or widths) of the scrolling area: "one full screen", "half a page". */
        data class Screens(val n: Float) : Amount()
        /** All the way: "to the top", "to the bottom". */
        data object ToEnd : Amount()
    }
    enum class System { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT }
}

/** What a spoken hands-free utterance means, decided on the phone when it can be. */
sealed class Command {
    data object Stop : Command()
    data object Yes : Command()
    data object No : Command()
    data object ShowNumbers : Command()
    data object HideNumbers : Command()
    data class TapNumber(val n: Int, val long: Boolean = false) : Command()
    data class TapName(val name: String, val long: Boolean = false) : Command()
    /** "Open X": something on screen called X, or else the app. */
    data class Open(val name: String) : Command()
    data class FocusName(val name: String) : Command()
    data class Type(val text: String) : Command()
    data object Clear : Command()
    /** Tap (or hold) wherever the cursor is. */
    data class TapCursor(val long: Boolean = false) : Command()
    data class CursorTo(val name: String) : Command()
    data class Do(val action: Action) : Command()
    /** "Go ahead and …": several steps toward a goal, planned by Claude. */
    data class Goal(val goal: String) : Command()
    /** Anything else: one step, planned by Claude. */
    data class Ask(val text: String) : Command()
}

object Commands {
    /** How far "cursor up" moves, as a share of the screen's shorter side. */
    const val CURSOR_STEP = 0.08f

    private val FILLER_START = Regex("^(?:voice ?bar|voicebar|okay|ok|hey|um|uh|so|now|and|then|please|can you|could you|would you)\\b\\s*")
    private val FILLER_END = Regex("\\s*\\b(?:please|thanks|thank you|for me|now)$")

    private val NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "ate" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    )
    /** Sound-alikes of numbers, only trusted right after "tap": "tap to" is "tap 2". */
    private val SOUNDALIKES = mapOf("won" to 1, "to" to 2, "too" to 2, "for" to 4, "fore" to 4, "ate" to 8, "tree" to 3)

    /** "12", "twelve", "twenty three", "number 7": the number, or null. */
    fun number(s: String, loose: Boolean = false): Int? {
        val t = s.trim().removePrefix("number ").removePrefix("#").trim()
        t.toIntOrNull()?.let { return it }
        if (loose) SOUNDALIKES[t]?.let { return it }
        val w = t.split(' ', '-').filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > 2) return null
        val a = NUMBERS[w[0]] ?: return null
        if (w.size == 1) return a
        val b = NUMBERS[w[1]] ?: return null
        return if (a >= 20 && a % 10 == 0 && b < 10) a + b else null
    }

    fun clean(raw: String): String {
        var t = raw.lowercase().replace(Regex("[^\\p{L}\\p{N}#' ]"), " ").replace(Regex("\\s+"), " ").trim()
        repeat(3) { t = t.replace(FILLER_START, "").trim() }
        t = t.replace(FILLER_END, "").trim()
        return t
    }

    private fun dir(s: String) = when (s) {
        "up" -> Action.Dir.UP; "down" -> Action.Dir.DOWN; "left" -> Action.Dir.LEFT; "right" -> Action.Dir.RIGHT; else -> null
    }

    /** [raw]: what was heard. [original] keeps its case and punctuation for typing. */
    fun parse(raw: String): Command {
        val t = clean(raw)
        if (t.isEmpty()) return Command.Ask(raw)

        // leaving hands-free: needs "stop"/"exit" with hands free, or alone
        if (Regex("^(?:stop|exit|quit|end|close|turn off|cancel|leave|done with)(?: (?:the )?hands ?free(?: mode)?)?$").matches(t) ||
            Regex("^hands ?free (?:off|stop|done)$").matches(t) || t == "stop listening") return Command.Stop
        if (Regex("^(?:yes|yeah|yep|yup|sure|do it|go|go ahead|confirm|send it|ok do it|okay do it|correct|affirmative)$").matches(t)) return Command.Yes
        if (Regex("^(?:no|nope|don't|do not|never mind|nevermind|cancel that|stop that|wait)$").matches(t)) return Command.No

        if (Regex("^(?:show|turn on) (?:the )?(?:numbers|labels|badges)$").matches(t) || t == "numbers") return Command.ShowNumbers
        if (Regex("^(?:hide|turn off|remove|clear) (?:the )?(?:numbers|labels|badges)$").matches(t)) return Command.HideNumbers

        Regex("^go ?ahead(?: and)? (.+)$").find(t)?.let { return Command.Goal(original(raw, it.groupValues[1])) }

        when (t) {
            "back", "go back", "press back", "back button" -> return Command.Do(Action.Global(Action.System.BACK, "Back"))
            "home", "go home", "home screen", "go to home screen", "go to the home screen" -> return Command.Do(Action.Global(Action.System.HOME, "Home"))
            "recents", "recent apps", "recent", "app switcher", "show recent apps", "overview", "switch apps" -> return Command.Do(Action.Global(Action.System.RECENTS, "Recent apps"))
            "notifications", "show notifications", "open notifications", "pull down notifications" -> return Command.Do(Action.Global(Action.System.NOTIFICATIONS, "Notifications"))
            "quick settings", "open quick settings", "show quick settings" -> return Command.Do(Action.Global(Action.System.QUICK_SETTINGS, "Quick settings"))
            "lock", "lock phone", "lock the phone", "lock screen", "lock the screen" -> return Command.Do(Action.Global(Action.System.LOCK, "Lock"))
            "screenshot", "take a screenshot", "take screenshot" -> return Command.Do(Action.Global(Action.System.SCREENSHOT, "Screenshot"))
            "zoom in", "pinch out" -> return Command.Do(Action.Pinch(true, "Zoom in"))
            "zoom out", "pinch in", "pinch" -> return Command.Do(Action.Pinch(false, "Zoom out"))
            "tap", "click", "tap here", "click here", "tap it", "click it", "press", "select" -> return Command.TapCursor()
            "long press", "press and hold", "hold", "long press here", "hold here" -> return Command.TapCursor(long = true)
            "clear", "clear it", "clear the field", "clear the text", "clear text", "delete all", "erase it", "erase all" -> return Command.Clear
        }

        scroll(t)?.let { return Command.Do(it) }
        Regex("^swipe (up|down|left|right)$").find(t)?.let {
            return Command.Do(Action.Swipe(dir(it.groupValues[1])!!, "Swipe ${it.groupValues[1]}"))
        }
        Regex("^(?:move )?(?:the )?(?:cursor|pointer|mouse) (up|down|left|right)(?: (a bit|a little|a lot|more))?$").find(t)?.let {
            val k = when (it.groupValues[2]) { "a bit", "a little" -> 0.5f; "a lot" -> 3f; else -> 1f }
            val (dx, dy) = when (it.groupValues[1]) { "up" -> 0 to -1; "down" -> 0 to 1; "left" -> -1 to 0; else -> 1 to 0 }
            val step = (1000 * CURSOR_STEP * k).toInt() // in thousandths of the shorter side
            return Command.Do(Action.Cursor(dx * step, dy * step, "Cursor ${it.groupValues[1]}"))
        }
        Regex("^(?:move )?(?:the )?(?:cursor|pointer|mouse) (?:to|on|over) (.+)$").find(t)?.let { return Command.CursorTo(it.groupValues[1]) }

        Regex("^(?:type|write|enter|input|dictate)(?: in)? (.+)$").find(t)?.let { return Command.Type(original(raw, it.groupValues[1])) }

        Regex("^(long press|press and hold|hold|long tap|long click|tap|click|press|hit|select|choose|pick|touch|tap on|click on) (.+)$").find(t)?.let {
            val long = it.groupValues[1].let { v -> v.startsWith("long") || v.contains("hold") }
            val what = it.groupValues[2].removePrefix("on ").trim()
            number(what, loose = true)?.let { n -> return Command.TapNumber(n, long) }
            return Command.TapName(what, long)
        }
        number(t)?.let { return Command.TapNumber(it) }
        Regex("^(?:focus|go to|jump to|move to)(?: on)? (?:the )?(.+?)(?: (?:field|box))?$").find(t)?.let { return Command.FocusName(it.groupValues[1]) }
        Regex("^(?:open|launch|start|show|go to) (?:the )?(.+?)(?: app)?$").find(t)?.let { return Command.Open(it.groupValues[1]) }
        return Command.Ask(raw.trim())
    }

    private val SCROLL_START = Regex("^(?:scroll|page|go|jump|move|head|back|take me|skip|flip)\\b")
    private val SCROLL_WORDS = Regex("\\b(?:up|down|left|right|top|bottom|beginning|start|end|page|pages|screen|screens|screenful|screenfuls)\\b")

    /**
     * Scrolling, with how far: "scroll down", "scroll up to the top", "go to the bottom",
     * "scroll down one full screen", "page down", "scroll up two pages", "half a screen down",
     * "scroll a bit", "scroll down a lot", "all the way down", "back to top".
     */
    fun scroll(t: String): Action.Scroll? {
        if (!SCROLL_START.containsMatchIn(t) || !SCROLL_WORDS.containsMatchIn(t)) return null
        // "go up" is a scroll; "go to settings" and "go back" aren't
        if (t.startsWith("go ") && !Regex("^go (?:all the way )?(?:up|down|left|right|to (?:the )?(?:very )?(?:top|bottom|beginning|end|start))").containsMatchIn(t)) return null
        if (t.startsWith("back") && !Regex("^back (?:up )?to (?:the )?(?:top|start|beginning)").containsMatchIn(t)) return null
        val words = t.split(' ')
        var dir = words.firstNotNullOfOrNull { dir(it) }
        val toTop = Regex("\\b(?:top|beginning|start)\\b").containsMatchIn(t)
        val toBottom = Regex("\\b(?:bottom|end)\\b").containsMatchIn(t)
        val allTheWay = t.contains("all the way") || t.contains("the very")
        if (dir == null) dir = when {
            toTop -> Action.Dir.UP
            toBottom -> Action.Dir.DOWN
            t.startsWith("page") || t.contains("next page") || t.contains("next screen") -> Action.Dir.DOWN
            t.contains("previous page") || t.contains("previous screen") || t.contains("last page") -> Action.Dir.UP
            else -> Action.Dir.DOWN
        }
        val horizontal = dir == Action.Dir.LEFT || dir == Action.Dir.RIGHT
        val amount: Action.Amount = when {
            toTop || toBottom || allTheWay -> Action.Amount.ToEnd
            Regex("\\bhalf (?:a |of a |the )?(?:screen|page|screenful)").containsMatchIn(t) -> Action.Amount.Screens(0.5f)
            else -> Regex("(?:\\b([a-z0-9]+(?: [a-z]+)?) )?(?:full |whole |entire )?(?:screens?|pages?|screenfuls?)\\b").find(t)?.let { m ->
                val before = m.groupValues[1].removeSuffix(" full").removeSuffix(" whole").removeSuffix(" entire").trim()
                val n = when {
                    before.isEmpty() || before in setOf("a", "an", "one", "the", "next", "previous", "last", "by", "up", "down", "scroll", "page") -> 1
                    else -> number(before) ?: before.split(' ').lastOrNull()?.let { number(it) } ?: 1
                }
                Action.Amount.Screens(n.toFloat().coerceIn(0.25f, 20f))
            } ?: when {
                Regex("\\b(?:a (?:little )?bit|a little|slightly|a tad|a touch|a smidge)\\b").containsMatchIn(t) -> Action.Amount.Screens(0.3f)
                Regex("\\b(?:a lot|a bunch|way|far|lots)\\b").containsMatchIn(t) -> Action.Amount.Screens(3f)
                else -> Action.Amount.Step
            }
        }
        val dirWord = dir.name.lowercase()
        val say = when (amount) {
            Action.Amount.ToEnd -> if (horizontal) "Scroll all the way $dirWord" else if (dir == Action.Dir.UP) "Scroll to the top" else "Scroll to the bottom"
            is Action.Amount.Screens -> when (amount.n) {
                1f -> "Scroll $dirWord one screen"
                0.5f -> "Scroll $dirWord half a screen"
                0.3f -> "Scroll $dirWord a bit"
                else -> "Scroll $dirWord ${if (amount.n % 1f == 0f) amount.n.toInt().toString() else amount.n.toString()} screens"
            }
            Action.Amount.Step -> "Scroll $dirWord"
        }
        return Action.Scroll(dir, amount = amount, say = say)
    }

    /** The words after a command as originally heard (with case and punctuation), for typing. */
    fun original(raw: String, lowered: String): String {
        val words = lowered.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return lowered
        // find where the first of these words starts in the original
        val r = Regex("(?i)\\b" + Regex.escape(words.first()))
        val m = r.findAll(raw).lastOrNull { raw.substring(it.range.first).lowercase().replace(Regex("[^\\p{L}\\p{N}#' -]"), " ")
            .replace(Regex("\\s+"), " ").trim().startsWith(lowered) } ?: r.find(raw) ?: return lowered
        return raw.substring(m.range.first).trim()
    }

    /** Labels that send, pay, delete or publish something: tapping one is asked about first. */
    private val RISKY = Regex("\\b(send|post|publish|tweet|share|pay|buy|purchase|order|checkout|check out|place order|subscribe|delete|remove|discard|erase|transfer|submit|confirm|book|reserve|call|unfriend|block|report|sign out|log out|uninstall|reset)\\b", RegexOption.IGNORE_CASE)

    fun risky(label: String) = RISKY.containsMatchIn(label)
}
