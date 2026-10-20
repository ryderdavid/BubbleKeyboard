package com.ryderdavid.voicebar.handsfree

/**
 * What hands-free mode sees on screen: the things you can tap, type into or scroll, and the text
 * around them, numbered in reading order. Built from the accessibility tree; kept pure (plain
 * values) so matching and the planner's description can be tested.
 */
data class Element(
    val id: Int,
    val label: String,
    /** button, field, list, switch, checkbox, image, text, … */
    val role: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = (right - left).toLong() * (bottom - top)
    /** Something you can act on: worth a number badge. */
    val actionable get() = clickable || longClickable || editable || scrollable
}

class Screen(
    val app: String,
    val width: Int,
    val height: Int,
    val elements: List<Element>,
) {
    fun byId(id: Int) = elements.firstOrNull { it.id == id }

    /** The badges' numbers: actionable elements in reading order, from 1. */
    val numbered: List<Element> by lazy { elements.filter { it.clickable || it.longClickable || it.editable } }
    fun byNumber(n: Int) = numbered.getOrNull(n - 1)
    fun numberOf(e: Element) = numbered.indexOf(e).takeIf { it >= 0 }?.plus(1)

    val focusedField get() = elements.firstOrNull { it.editable && it.focused }
    val fields get() = elements.filter { it.editable }

    /** The main scrolling area: the biggest scrollable thing (the chat, the feed, the page). */
    val mainScroll get() = elements.filter { it.scrollable }.maxByOrNull { it.area }

    /** Few labels: an app drawn without accessibility (a map, a game, a canvas). Worth a screenshot. */
    val opaque get() = elements.count { it.label.isNotBlank() } < 4

    /**
     * For the planner: one line per element, compact.
     * `[12] button "Send" @(980,2010)` · flags in braces · text-only lines have no actions.
     */
    fun describe(max: Int = MAX_DESCRIBED): String = buildString {
        append("App: ").append(app).append(" · screen ").append(width).append('×').append(height).append('\n')
        for (e in elements.take(max)) {
            append('[').append(e.id).append("] ").append(e.role)
            if (e.label.isNotBlank()) append(" \"").append(e.label.take(80).replace('"', '\'')).append('"')
            append(" @(").append(e.centerX).append(',').append(e.centerY).append(')')
            val flags = listOfNotNull(
                "tap".takeIf { e.clickable }, "hold".takeIf { e.longClickable }, "type".takeIf { e.editable },
                "scroll".takeIf { e.scrollable }, "focused".takeIf { e.focused },
                e.checked?.let { if (it) "on" else "off" },
            )
            if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
            append('\n')
        }
        if (elements.size > max) append("(").append(elements.size - max).append(" more not listed)\n")
    }

    /**
     * The element a spoken name points to: "Send", "the search box", "Siona", "settings".
     * Exact label first, then starts-with, then contains, then shared words; something you can
     * act on beats plain text. Null when nothing is a decent match.
     */
    fun find(spoken: String, wantField: Boolean = false): Element? {
        val q = norm(spoken).removePrefix("the ").removeSuffix(" button").removeSuffix(" icon").trim()
        if (q.isEmpty()) return null
        val fieldWords = Regex("\\s*\\b(box|field|bar|input)$")
        val qField = q.contains(fieldWords)
        val core = q.replace(fieldWords, "").trim().ifEmpty { q }
        var best: Element? = null
        var bestScore = 0.0
        for (e in elements) {
            val l = norm(e.label)
            if (l.isEmpty()) continue
            var s = when {
                l == core || l == q -> 1.0
                l.startsWith("$core ") || l.startsWith(core) && core.length >= 3 -> 0.8
                l.contains(core) && core.length >= 3 -> 0.65
                else -> overlap(core, l) * 0.6
            }
            if (s <= 0.0) continue
            if (e.actionable) s += 0.15
            if ((wantField || qField) && e.editable) s += 0.3
            if (s > bestScore || (s == bestScore && best != null && e.area < best.area)) { best = e; bestScore = s }
        }
        return best.takeIf { bestScore >= 0.45 }
    }

    companion object {
        const val MAX_DESCRIBED = 160

        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        private val STOP = setOf("the", "a", "an", "to", "on", "of", "my", "for", "in", "and")

        /** Share of the spoken words (not filler) that appear in the label. */
        fun overlap(q: String, label: String): Double {
            val qw = q.split(' ').filter { it !in STOP && it.isNotEmpty() }
            if (qw.isEmpty()) return 0.0
            val lw = label.split(' ').toSet()
            return qw.count { it in lw }.toDouble() / qw.size
        }
    }
}
