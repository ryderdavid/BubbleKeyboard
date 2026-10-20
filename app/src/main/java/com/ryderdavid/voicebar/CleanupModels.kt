package com.ryderdavid.voicebar

import android.content.Context
import com.ryderdavid.voicebar.voice.Pricing

/** The Claude models offered for cleanup, fastest first. A model saved by hand earlier stays in the list. */
object CleanupModels {
    val IDS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")

    fun options(current: String): List<String> = if (current in IDS) IDS else IDS + current

    fun label(context: Context, id: String): String = when (id) {
        "claude-haiku-5-5" -> context.getString(R.string.model_haiku)
        "claude-sonnet-5-5" -> context.getString(R.string.model_sonnet)
        "claude-opus-5-5" -> context.getString(R.string.model_opus)
        else -> id
    }

    fun detail(context: Context, id: String): String {
        val what = when (id) {
            "claude-haiku-5-5" -> context.getString(R.string.model_haiku_detail)
            "claude-sonnet-5-5" -> context.getString(R.string.model_sonnet_detail)
            "claude-opus-5-5" -> context.getString(R.string.model_opus_detail)
            else -> context.getString(R.string.model_custom_detail)
        }
        val price = Pricing.claude(id)?.let { p -> context.getString(R.string.model_price, fmt(p.input), fmt(p.output)) }
        return listOfNotNull(what, price).joinToString(" · ")
    }

    private fun fmt(v: Double) = if (v < 1) "%.2f".format(v) else if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
}
