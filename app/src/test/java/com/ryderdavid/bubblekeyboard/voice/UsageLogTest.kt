package com.ryderdavid.bubblekeyboard.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class UsageLogTest {
    private var now = 1_000L * UsageLog.HOUR_MS
    private var saved: String? = null
    private val log = UsageLog(object : UsageLog.Store {
        override fun read() = saved
        override fun write(json: String) { saved = json }
    }) { now }

    @Test fun pricesKnownModelsIncludingDatedIds() {
        assertEquals(Pricing.TokenPrice(0.10, 0.50), Pricing.claude("claude-haiku-5-5"))
        assertEquals(Pricing.TokenPrice(4.0, 20.0), Pricing.claude("claude-opus-5-5"), "not mistaken for claude-opus-5")
        assertEquals(Pricing.TokenPrice(5.0, 25.0), Pricing.claude("claude-opus-5"))
        assertEquals(Pricing.TokenPrice(1.0, 5.0), Pricing.claude("claude-haiku-4-5-20251001"))
        assertEquals(Pricing.TokenPrice(3.0, 15.0), Pricing.claude("claude-sonnet-4-5"))
        assertNull(Pricing.claude("gpt-something"))
    }

    @Test fun anHourOfAudioCostsTheRealtimeRate() {
        assertEquals(0.39, Pricing.scribeCost(UsageLog.HOUR_MS), 1e-9)
    }

    @Test fun windowsAddUpTheRightHours() {
        log.addAudio(60_000) // a minute, now
        log.addClaude("claude-haiku-5-5", 1_000_000, 100_000) // $0.10 + $0.05
        now += 30 * UsageLog.HOUR_MS
        log.addAudio(120_000)
        now += 10 * 24 * UsageLog.HOUR_MS

        assertEquals(0L, log.last(24).audioMs)
        assertEquals(0L, log.last(24 * 7).audioMs)
        val month = log.last(24 * 30)
        assertEquals(180_000L, month.audioMs)
        assertEquals(0.15, month.claudeUsd, 1e-9)
        assertEquals(180_000 / 3_600_000.0 * 0.39 + 0.15, month.totalUsd, 1e-9)
    }

    @Test fun unknownModelsAreCountedButNotPriced() {
        log.addClaude("claude-future-9", 500, 50)
        val t = log.last(24)
        assertEquals(550L, t.unpricedTokens)
        assertEquals(0.0, t.claudeUsd)
        assertEquals(500L, t.inputTokens)
    }

    @Test fun oldHoursAreDroppedAndClearEmptiesIt() {
        log.addAudio(1000)
        now += 40 * 24 * UsageLog.HOUR_MS
        log.addAudio(2000) // writing prunes the month-old hour
        assertEquals(1, Regex("\"\\d+\":").findAll(saved!!).count())
        log.clear()
        assertEquals(0L, log.last(24 * 30).audioMs)
    }

    @Test fun formatting() {
        assertEquals("$0", UsageLog.formatUsd(0.0))
        assertEquals("$0.0031", UsageLog.formatUsd(0.0031))
        assertEquals("$1.25", UsageLog.formatUsd(1.249))
        assertEquals("4 min", UsageLog.formatAudio(250_000))
        assertEquals("12.3K", UsageLog.formatTokens(12_300))
    }
}
