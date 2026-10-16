package com.ryderdavid.bubblekeyboard.handsfree

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HandsFreeTest {
    private val chat = Screen("com.google.android.apps.messaging", 1080, 2400, listOf(
        Element(1, "Navigate up", "button", 0, 100, 140, 240, clickable = true),
        Element(2, "Siona Okafor", "text", 160, 120, 700, 220),
        Element(3, "Video call", "button", 800, 100, 940, 240, clickable = true),
        Element(4, "", "list", 0, 260, 1080, 2000, scrollable = true),
        Element(5, "See you at 6:40 at the trailhead", "text", 100, 1700, 900, 1800, longClickable = true),
        Element(6, "Text message", "field", 120, 2050, 860, 2180, clickable = true, editable = true, focused = true),
        Element(7, "Send SMS", "button", 900, 2050, 1040, 2180, clickable = true),
    ))

    @Test fun phoneCommands() {
        assertEquals(Command.Stop, Commands.parse("Stop hands-free."))
        assertEquals(Command.Stop, Commands.parse("voice bar, stop"))
        assertEquals(Command.Yes, Commands.parse("Yeah."))
        assertEquals(Command.No, Commands.parse("No."))
        assertEquals(Command.ShowNumbers, Commands.parse("Show numbers"))
        assertEquals(Command.Do(Action.Global(Action.System.BACK, "Back")), Commands.parse("Go back."))
        assertEquals(Command.Do(Action.Global(Action.System.HOME, "Home")), Commands.parse("Go home, please."))
        assertEquals(Command.Do(Action.Scroll(Action.Dir.DOWN, say = "Scroll down")), Commands.parse("Scroll down."))
        assertEquals(Command.Do(Action.Scroll(Action.Dir.UP, amount = Action.Amount.ToEnd, say = "Scroll to the top")), Commands.parse("scroll to the top"))
        assertEquals(Command.Do(Action.Swipe(Action.Dir.LEFT, "Swipe left")), Commands.parse("Swipe left"))
        assertEquals(Command.TapNumber(7), Commands.parse("Tap 7."))
        assertEquals(Command.TapNumber(23), Commands.parse("click twenty three"))
        assertEquals(Command.TapNumber(2), Commands.parse("tap to"))
        assertEquals(Command.TapNumber(12, long = true), Commands.parse("long press 12"))
        assertEquals(Command.TapName("send sms"), Commands.parse("Tap Send SMS."))
        assertEquals(Command.TapCursor(), Commands.parse("click"))
        assertIs<Command.Do>(Commands.parse("move the cursor up a bit"))
        assertEquals(Command.Open("spotify"), Commands.parse("Open Spotify."))
        assertEquals(Command.Type("I'll be there in 10 minutes."), Commands.parse("Type I'll be there in 10 minutes."))
        assertEquals(Command.Goal("reply to Siona saying I'm running late."), Commands.parse("Go ahead and reply to Siona saying I'm running late."))
        assertEquals(Command.Ask("Find the second email from Mark."), Commands.parse("Find the second email from Mark."))
        // a lone sound-alike isn't a tap
        assertIs<Command.Ask>(Commands.parse("too"))
    }

    @Test fun scrollAmounts() {
        fun sc(s: String) = (Commands.parse(s) as Command.Do).action as Action.Scroll
        val top = Action.Scroll(Action.Dir.UP, amount = Action.Amount.ToEnd, say = "Scroll to the top")
        val bottom = Action.Scroll(Action.Dir.DOWN, amount = Action.Amount.ToEnd, say = "Scroll to the bottom")
        assertEquals(top, sc("Scroll up to the top."))
        assertEquals(top, sc("go to the top"))
        assertEquals(top, sc("back to top"))
        assertEquals(top, sc("scroll all the way up"))
        assertEquals(top, sc("Scroll to the very top"))
        assertEquals(bottom, sc("scroll down to the bottom"))
        assertEquals(bottom, sc("Go to the bottom of the page."))
        assertEquals(Action.Amount.Screens(1f), sc("Scroll down one full screen.").amount)
        assertEquals(Action.Dir.DOWN, sc("Scroll down one full screen.").direction)
        assertEquals(Action.Amount.Screens(1f), sc("scroll down a full page").amount)
        assertEquals(Action.Amount.Screens(1f), sc("page down").amount)
        assertEquals(Action.Dir.UP, sc("page up").direction)
        assertEquals(Action.Amount.Screens(1f), sc("scroll up one screen").amount)
        assertEquals(Action.Amount.Screens(2f), sc("Scroll down two pages.").amount)
        assertEquals(Action.Amount.Screens(3f), sc("scroll up 3 screens").amount)
        assertEquals(Action.Amount.Screens(0.5f), sc("scroll down half a screen").amount)
        assertEquals(Action.Amount.Screens(0.3f), sc("scroll down a bit").amount)
        assertEquals(Action.Amount.Screens(3f), sc("scroll down a lot").amount)
        assertEquals(Action.Scroll(Action.Dir.DOWN, say = "Scroll down"), sc("scroll down"))
        assertEquals(Action.Dir.RIGHT, sc("scroll right").direction)
        assertEquals("Scroll down one screen", sc("scroll down one full screen").say)
        // not scrolls
        assertEquals(Command.Do(Action.Global(Action.System.BACK, "Back")), Commands.parse("go back"))
        assertIs<Command.FocusName>(Commands.parse("go to settings"))
        // Claude's scroll tool carries the amount too
        assertEquals(Action.Scroll(Action.Dir.UP, amount = Action.Amount.ToEnd, say = "Top"),
            PlannerRequest.parse("""{"content":[{"type":"tool_use","id":"t","name":"scroll","input":{"direction":"up","amount":"to_end","say":"Top"}}]}"""))
        assertEquals(Action.Scroll(Action.Dir.DOWN, amount = Action.Amount.Screens(1f), say = "One screen"),
            PlannerRequest.parse("""{"content":[{"type":"tool_use","id":"t","name":"scroll","input":{"direction":"down","amount":"screens","screens":1,"say":"One screen"}}]}"""))
    }

    @Test fun findsWhatYouName() {
        assertEquals(7, chat.find("send")?.id)
        assertEquals(6, chat.find("the message box")?.id)
        assertEquals(6, chat.find("text message")?.id)
        assertEquals(3, chat.find("video call button")?.id)
        assertEquals(2, chat.find("Siona")?.id)
        assertNull(chat.find("settings"))
        // badges number only what you can tap or type in, in order
        assertEquals(listOf(1, 3, 5, 6, 7), chat.numbered.map { it.id })
        assertEquals(6, chat.byNumber(4)?.id)
        assertEquals(chat.elements[3], chat.mainScroll)
    }

    @Test fun appsByName() {
        val apps = listOf("Spotify" to "com.spotify.music", "Google Maps" to "com.google.android.apps.maps",
            "Maps" to "x.maps", "YouTube Music" to "com.google.android.apps.youtube.music", "YouTube" to "com.google.android.youtube")
        assertEquals("com.spotify.music", AppMatch.best("spotify", apps)?.second)
        assertEquals("x.maps", AppMatch.best("maps", apps)?.second)
        assertEquals("com.google.android.youtube", AppMatch.best("youtube", apps)?.second)
        assertEquals("com.google.android.apps.youtube.music", AppMatch.best("youtube music app", apps)?.second)
        assertNull(AppMatch.best("calculator", apps))
    }

    @Test fun riskyLabels() {
        assertTrue(Commands.risky("Send SMS"))
        assertTrue(Commands.risky("Place order"))
        assertTrue(!Commands.risky("Navigate up"))
    }

    @Test fun plannerRequestDescribesTheScreen() {
        val body = JSONObject(PlannerRequest.body("open the video call", chat, "m"))
        assertEquals("auto", body.getJSONObject("tool_choice").getString("type"))
        val text = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.startsWith("Command: open the video call"))
        assertTrue(text.contains("[7] button \"Send SMS\" @(970,2115) {tap}"), text)
        assertTrue(text.contains("[6] field \"Text message\" @(490,2115) {tap,type,focused}"), text)
        val names = (0 until body.getJSONArray("tools").length()).map { body.getJSONArray("tools").getJSONObject(it).getString("name") }
        assertEquals(listOf("tap", "type", "scroll", "swipe", "drag", "system", "open_app", "done"), names)

        val goal = JSONObject(PlannerRequest.body("reply to Siona", chat, "m", goal = true, done = listOf("Tap the message box")))
        val g = goal.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(g.startsWith("Goal: reply to Siona\nSteps taken so far:\n1. Tap the message box"), g)
    }

    @Test fun plannerRepliesBecomeActions() {
        val tap = PlannerRequest.parse("""{"content":[{"type":"tool_use","id":"t","name":"tap","input":{"id":7,"say":"Tap Send","risky":true}}]}""")
        assertEquals(Action.Tap(7, say = "Tap Send", risky = true), tap)
        val type = PlannerRequest.parse("""{"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t","name":"type","input":{"id":6,"text":"On my way","say":"Type reply","risky":false}}]}""")
        assertEquals(Action.Type("On my way", 6, say = "Type reply"), type)
        // screenshot coordinates map back to the screen
        val shot = PlannerRequest.Shot("x", 540, 1200, 0.5f)
        assertEquals(Action.Tap(null, 200, 400, say = "Tap map", risky = false),
            PlannerRequest.parse("""{"content":[{"type":"tool_use","id":"t","name":"tap","input":{"x":100,"y":200,"say":"Tap map","risky":false}}]}""", shot))
        assertEquals(Action.Done("Can't find that"), PlannerRequest.parse("""{"content":[{"type":"text","text":"Can't find that"}]}"""))
        assertEquals(Action.Global(Action.System.NOTIFICATIONS, "Open notifications"),
            PlannerRequest.parse("""{"content":[{"type":"tool_use","id":"t","name":"system","input":{"action":"notifications","say":"Open notifications"}}]}"""))
        assertNull(PlannerRequest.parse("not json"))
    }
}
