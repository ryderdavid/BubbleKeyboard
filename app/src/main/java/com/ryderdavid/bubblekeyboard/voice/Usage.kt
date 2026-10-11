package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * List prices, used to turn what this phone sent into a cost estimate. They don't know about plan
 * allowances, credits or discounts, so the provider's own dashboard is the bill; this is the
 * "what did my dictation cost at list price" view.
 */
object Pricing {
    /** ElevenLabs Scribe v2 Realtime, US$ per hour of audio streamed. */
    const val SCRIBE_REALTIME_PER_HOUR = 0.39

    /** US$ per million tokens, input then output. */
    data class TokenPrice(val input: Double, val output: Double)

    // most specific first: "claude-opus-5-5" must not fall through to "claude-opus-5"
    private val claude = listOf(
        "claude-fable-5-1" to TokenPrice(10.0, 50.0),
        "claude-mythos-5-1" to TokenPrice(10.0, 50.0),
        "claude-fable-5" to TokenPrice(10.0, 50.0),
        "claude-mythos-5" to TokenPrice(10.0, 50.0),
        "claude-opus-5-5" to TokenPrice(4.0, 20.0),
        "claude-sonnet-5-5" to TokenPrice(2.0, 10.0),
        // short prompts (≤100K tokens), which a dictation always is
        "claude-haiku-5-5" to TokenPrice(0.10, 0.50),
        "claude-opus-5" to TokenPrice(5.0, 25.0),
        "claude-sonnet-5" to TokenPrice(2.0, 10.0),
        "claude-opus-4-8" to TokenPrice(5.0, 25.0),
        "claude-opus-4-7" to TokenPrice(5.0, 25.0),
        "claude-opus-4-6" to TokenPrice(5.0, 25.0),
        "claude-opus-4-5" to TokenPrice(5.0, 25.0),
        "claude-opus-4-1" to TokenPrice(15.0, 75.0),
        "claude-opus-4" to TokenPrice(15.0, 75.0),
        "claude-sonnet-4" to TokenPrice(3.0, 15.0),
        "claude-haiku-4-5" to TokenPrice(1.0, 5.0),
        "claude-3-5-haiku" to TokenPrice(0.80, 4.0),
    )

    /** The price for a model id (dated ids like claude-haiku-4-5-20251001 included), or null if unknown. */
    fun claude(model: String): TokenPrice? {
        val m = model.trim().lowercase(Locale.ROOT)
        return claude.firstOrNull { (id, _) -> m == id || m.startsWith("$id-") }?.second
    }

    fun claudeCost(model: String, input: Long, output: Long): Double? =
        claude(model)?.let { (input * it.input + output * it.output) / 1_000_000.0 }

    fun scribeCost(audioMs: Long): Double = audioMs / 3_600_000.0 * SCRIBE_REALTIME_PER_HOUR
}

/**
 * What dictation used, kept on the phone in hourly buckets (31 days of them) so the last 24 hours,
 * 7 days and 30 days can be added up. Only counts and costs are kept; never any text or audio.
 */
class UsageLog(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    interface Store {
        fun read(): String?
        fun write(json: String)
    }

    data class Totals(
        val audioMs: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val scribeUsd: Double = 0.0,
        val claudeUsd: Double = 0.0,
        /** Tokens from models with no known price: not in [claudeUsd]. */
        val unpricedTokens: Long = 0,
    ) {
        val totalUsd get() = scribeUsd + claudeUsd
        operator fun plus(o: Totals) = Totals(
            audioMs + o.audioMs, inputTokens + o.inputTokens, outputTokens + o.outputTokens,
            scribeUsd + o.scribeUsd, claudeUsd + o.claudeUsd, unpricedTokens + o.unpricedTokens,
        )
    }

    private val lock = Any()

    fun addAudio(ms: Long) {
        if (ms <= 0) return
        add(Totals(audioMs = ms, scribeUsd = Pricing.scribeCost(ms)))
    }

    fun addClaude(model: String, input: Long, output: Long) {
        if (input <= 0 && output <= 0) return
        val cost = Pricing.claudeCost(model, input, output)
        add(Totals(inputTokens = input, outputTokens = output, claudeUsd = cost ?: 0.0, unpricedTokens = if (cost == null) input + output else 0))
    }

    /** Everything in the last [hours] hours (the current hour included). */
    fun last(hours: Int): Totals = synchronized(lock) {
        val now = clock() / HOUR_MS
        val all = load()
        var t = Totals()
        for (k in all.keys()) {
            val h = k.toLongOrNull() ?: continue
            if (h > now - hours) t += decode(all.getJSONObject(k))
        }
        t
    }

    fun clear() = synchronized(lock) { store.write("{}") }

    private fun add(d: Totals) = synchronized(lock) {
        val now = clock() / HOUR_MS
        val all = load()
        val key = now.toString()
        val cur = all.optJSONObject(key)?.let { decode(it) } ?: Totals()
        all.put(key, encode(cur + d))
        // drop what's older than the longest window
        for (k in all.keys().asSequence().toList()) if ((k.toLongOrNull() ?: 0) <= now - KEEP_HOURS) all.remove(k)
        store.write(all.toString())
    }

    private fun load(): JSONObject = runCatching { JSONObject(store.read() ?: "{}") }.getOrElse { JSONObject() }

    private fun encode(t: Totals) = JSONObject()
        .put("a", t.audioMs).put("i", t.inputTokens).put("o", t.outputTokens)
        .put("s", t.scribeUsd).put("c", t.claudeUsd).put("u", t.unpricedTokens)

    private fun decode(j: JSONObject) = Totals(
        j.optLong("a"), j.optLong("i"), j.optLong("o"), j.optDouble("s", 0.0), j.optDouble("c", 0.0), j.optLong("u"),
    )

    companion object {
        const val HOUR_MS = 3_600_000L
        const val KEEP_HOURS = 31 * 24

        private const val PREFS = "usage"
        private const val KEY = "hours"

        fun of(context: Context): UsageLog {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return UsageLog(object : Store {
                override fun read() = p.getString(KEY, null)
                override fun write(json: String) { p.edit().putString(KEY, json).apply() }
            })
        }

        /** "$0.42", "$0.0031", "<$0.0001" or "$0". */
        fun formatUsd(v: Double): String = when {
            v <= 0.0 -> "$0"
            v < 0.0001 -> "<$0.0001"
            v < 0.01 -> String.format(Locale.US, "$%.4f", v)
            v < 100 -> String.format(Locale.US, "$%.2f", v)
            else -> String.format(Locale.US, "$%,.0f", v)
        }

        fun formatAudio(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${s / 60} min"
                else -> String.format(Locale.US, "%.1f h", s / 3600.0)
            }
        }

        fun formatTokens(n: Long): String = when {
            n < 1000 -> "$n"
            n < 1_000_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
            else -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
        }
    }
}
