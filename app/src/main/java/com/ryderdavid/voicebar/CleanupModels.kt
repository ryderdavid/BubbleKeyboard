package com.ryderdavid.voicebar

import android.content.Context
import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import com.ryderdavid.voicebar.voice.OpenAICleanupRequest
import com.ryderdavid.voicebar.voice.Pricing
import com.ryderdavid.voicebar.voice.Secrets

/**
 * The two cleanup models: Claude Haiku 5.5 (with a Claude key) and GPT-6 Luna (with an OpenAI key).
 * With one key, that key's model is used; with both, the one chosen (Haiku unless changed).
 */
object CleanupModels {
    const val HAIKU = ClaudeCleanupRequest.DEFAULT_MODEL
    const val LUNA = OpenAICleanupRequest.LUNA
    val IDS = listOf(HAIKU, LUNA)

    enum class Provider { ANTHROPIC, OPENAI }

    data class Choice(val model: String, val provider: Provider, val key: String)

    fun provider(model: String) = if (model.startsWith("gpt-")) Provider.OPENAI else Provider.ANTHROPIC

    fun keyName(model: String) = if (provider(model) == Provider.OPENAI) Secrets.OPENAI else Secrets.ANTHROPIC

    /** The model the user picked (one of [IDS]; anything older counts as Haiku). */
    fun chosen(context: Context): String =
        Prefs.get(context).getString(Prefs.CLEANUP_MODEL, null)?.takeIf { it in IDS } ?: HAIKU

    /** What cleanup will actually use now, given the keys there are; null with no key for either. */
    fun resolve(context: Context): Choice? {
        val want = chosen(context)
        for (m in listOf(want) + IDS.filter { it != want }) {
            val key = Secrets.get(context, keyName(m))?.takeIf { it.isNotBlank() } ?: continue
            return Choice(m, provider(m), key)
        }
        return null
    }

    fun available(context: Context, model: String) = Secrets.has(context, keyName(model))

    fun label(context: Context, id: String): String = context.getString(if (id == LUNA) R.string.model_luna else R.string.model_haiku)

    fun detail(context: Context, id: String): String {
        val what = context.getString(if (id == LUNA) R.string.model_luna_detail else R.string.model_haiku_detail)
        val price = Pricing.claude(id)?.let { p -> context.getString(R.string.model_price, fmt(p.input), fmt(p.output)) }
        return listOfNotNull(what, price).joinToString(" · ")
    }

    private fun fmt(v: Double) = if (v < 1) "%.2f".format(v) else "%.0f".format(v)
}
