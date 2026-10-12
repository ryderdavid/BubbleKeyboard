package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.edit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test

/**
 * Not a check: the video walkthrough. Plays every feature through the real BubbleView with
 * scripted touches over a mock chat app, 24 frames a second, into app/build/walkthrough/frames.
 * A small text model stands in for the app's field and is driven by the keyboard's own listener
 * calls, so what gets typed, selected and aimed at comes from the real gesture code. Chapters and
 * captions (by frame) go to app/build/walkthrough/chapters.json.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-port-xhdpi")
class RenderWalkthroughTest {
    private lateinit var activity: Activity
    private lateinit var pad: BubbleView
    private lateinit var out: File
    private var d = 2f
    private var downTime = 0L
    private var frame = 0
    private var finger: PointF? = null
    private var fingerDown = false
    private val chapters = mutableListOf<Triple<Int, String, String>>()
    private val captions = mutableListOf<Pair<Int, String>>()

    // ------------------------------------------------------------------ the mock app's text field
    private var text = StringBuilder()
    private var cursor = 0
    private var selA = -1
    private var selB = -1
    private var draftStart = -1
    private var draftEnd = -1
    private var clipboard = ""
    private val sent = mutableListOf<String>()
    // the joystick
    private var target = 0
    private var markA = -1
    private var stickAnchor = -1
    private var stickSel = false

    private val hasSel get() = selA >= 0 && selB != selA
    private fun sync() { pad.hasSelection = hasSel }

    private fun insert(s: String) {
        if (hasSel) deleteSel()
        text.insert(cursor, s)
        cursor += s.length
        selA = -1; selB = -1
    }

    private fun deleteSel() {
        val a = min(selA, selB); val b = max(selA, selB)
        text.delete(a, b)
        cursor = a
        selA = -1; selB = -1
    }

    private fun setField(s: String, cur: Int = s.length) {
        text = StringBuilder(s); cursor = cur; selA = -1; selB = -1; draftStart = -1; draftEnd = -1; markA = -1
        sync()
    }

    private val listener = object : BubbleView.Listener {
        override fun onKey(key: BubbleView.Key, repeat: Boolean) {
            when (key) {
                BubbleView.Key.LEFT -> { cursor = max(0, cursor - 1); selA = -1; selB = -1 }
                BubbleView.Key.RIGHT -> { cursor = min(text.length, cursor + 1); selA = -1; selB = -1 }
                BubbleView.Key.UP, BubbleView.Key.DOWN -> Unit
                BubbleView.Key.DELETE -> if (hasSel) deleteSel() else if (cursor > 0) { text.deleteCharAt(cursor - 1); cursor-- }
                BubbleView.Key.SPACE -> insert(" ")
                BubbleView.Key.ENTER -> if (text.isNotBlank()) { sent.add(text.toString()); setField("") }
            }
            sync()
        }
        override fun onLetter(ch: Char) { insert(ch.toString()); sync() }
        override fun onSwitchKeyboard() {}
        override fun shouldCapitalize() = text.isEmpty() || text.endsWith(". ")
        override fun onTalkStart(): Boolean {
            pad.voiceInField = true
            pad.showListening()
            return true
        }
        override fun onTalkEnd(cancel: Boolean) { talkEnded = if (cancel) "cancel" else "finish" }
        override fun onEdit(action: BubbleView.EditAction) {
            when (action) {
                BubbleView.EditAction.CUT -> if (hasSel) { clipboard = text.substring(min(selA, selB), max(selA, selB)); deleteSel() }
                BubbleView.EditAction.COPY -> if (hasSel) clipboard = text.substring(min(selA, selB), max(selA, selB))
                BubbleView.EditAction.PASTE -> insert(clipboard)
                BubbleView.EditAction.DELETE -> if (hasSel) deleteSel() else if (cursor > 0) { text.deleteCharAt(cursor - 1); cursor-- }
            }
            markA = -1
            sync()
        }
        override fun onStickStart() { target = cursor; markA = -1; stickSel = false }
        override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) {
            val step = cols + lines * 30
            target = (target + step).coerceIn(0, text.length)
            if (selecting) { selA = stickAnchor; selB = target; markA = -1; cursor = target }
            else markA = if (target < text.length) target else max(0, target - 1)
            sync()
        }
        override fun onStickTap() { cursor = target; markA = -1; selA = -1; selB = -1; sync() }
        override fun onStickSelect() { stickAnchor = cursor; target = cursor; markA = -1; stickSel = true }
        override fun onStickEnd() { markA = -1 }
    }
    private var talkEnded: String? = null

    @Test fun renderWalkthrough() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        d = activity.resources.displayMetrics.density
        val wPx = activity.resources.displayMetrics.widthPixels
        val hPx = activity.resources.displayMetrics.heightPixels
        Prefs.get(activity).edit {
            clear()
            putFloat("${Prefs.POSITION_PREFIX}x_$wPx", wPx - 96 * d)
            putFloat("${Prefs.POSITION_PREFIX}y_$wPx", 560 * d)
        }
        pad = BubbleView(activity, null)
        pad.listener = listener
        activity.setContentView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        idle(10)
        out = File("build/walkthrough/frames").apply { deleteRecursively(); mkdirs() }
        sent.add("Are we still on for the trailhead tomorrow?")

        val home = pad.testCenter()

        // 1 · meet the bubble
        chapter("Meet the bubble", "The whole keyboard is this bubble. It floats over the app and never covers your text.")
        setField("Running late")
        hold(60)

        // 2 · the ring
        chapter("Tap to open the ring", "A tap opens eight keys around it.")
        tap(home); hold(36)
        caption("The arc around the center counts down; untouched, the ring folds itself after 5 seconds.")
        hold(110)

        // 3 · move it
        chapter("Drag it anywhere, flick it away", "Drag the bubble wherever your thumb rests.")
        press(home); glide(home, PointF(150 * d, 380 * d), 20); hold(4); release(); hold(14)
        caption("Flick it and it glides, then settles against the edge with a small bounce.")
        val a = pad.testCenter()
        press(a); glide(a, PointF(a.x - 90 * d, a.y + 90 * d), 2); release(); hold(40)
        val b = pad.testCenter()
        press(b); glide(b, home, 22); hold(4); release(); hold(10)

        // 4 · keys
        chapter("Arrows, space and backspace", "Arrows move the cursor; backspace and space edit.")
        tap(home); hold(14)
        tapKey("LEFT"); tapKey("LEFT"); tapKey("RIGHT"); tapKey("RIGHT")
        hold(8)

        // 5 · blooms
        chapter("Hold for a bloom", "Hold space for punctuation; slide to a key and let go. Keys swell and part around your finger.")
        bloomType("SPACE", ',')
        tapKey("SPACE")
        hold(6)

        // 6 · the full keyboard
        chapter("Tap abc for the full keyboard", "abc fans out a whole QWERTY grid, and it stays open.")
        val abc = pad.testSlotCenter("LETTERS")
        tap(abc); hold(14)
        for (ch in "be there in") { val p = pad.testPill(ch) ?: continue; press(p); hold(2); release(); hold(3) }
        hold(8)
        caption("Slide along the space bar to move the cursor, as on Gboard.")
        val bar = pad.testPill(' ')!!
        press(bar); hold(3)
        glide(bar, PointF(bar.x - 60 * d, bar.y), 16); hold(6)
        glide(PointF(bar.x - 60 * d, bar.y), PointF(bar.x + 4 * d, bar.y), 14); hold(4)
        release(); hold(10)
        caption("abc became ✕: tap it to fold the grid away.")
        tap(abc); hold(14)
        caption("Enter holds the numbers.")
        tapKey("SPACE")
        bloomType("ENTER", '5')
        bloomType("SPACE", '!')
        hold(10)
        caption("Enter sends it.")
        tapKey("ENTER"); hold(24)

        // 7 · pull-outs
        chapter("Pull a key out for its second job", "Hold an arrow and a socket appears beyond it.")
        val right = pad.testSlotCenter("RIGHT")
        val sock = pad.testSideChip("RIGHT")
        press(right); hold(10)
        glide(right, lerp(right, sock, 0.5f), 8); hold(6)
        caption("Pull it most of the way and it clicks in. Let go short and it springs back.")
        glide(lerp(right, sock, 0.5f), lerp(right, sock, 0.9f), 6); hold(10)
        glide(lerp(right, sock, 0.9f), lerp(right, sock, 0.25f), 6); release(); hold(14)
        caption("Up pins the ring open: no countdown, a pin on the center.")
        val up = pad.testSlotCenter("UP")
        val upSock = pad.testSideChip("UP")
        press(up); hold(8); glide(up, upSock, 8); hold(6); release(); hold(30)
        caption("Show hold hints puts a faint icon outside each key for what holding it does.")
        Prefs.get(activity).edit { putBoolean(Prefs.HOLD_HINTS, true) }
        pad.reloadSettings(); hold(54)
        Prefs.get(activity).edit { putBoolean(Prefs.HOLD_HINTS, false) }
        pad.reloadSettings(); pad.setPinned(false)
        pad.reset(); hold(8)

        // 8 · press to talk
        chapter("Press and hold to talk", "A tick, the bubble sinks, a pulse swells out to the ring's edge…")
        press(home); hold(11)
        caption("…and a click: the ring opens already listening. Let go and keep talking.")
        hold(5); release()
        speak("um can you grab the red headlamp and uh the spare batteries", 96)
        caption("Words go straight into the field as you speak; the arc counts down the silence.")
        quiet(30)
        caption("Tap the center to finish: Claude tidies it up in place.")
        tap(home)
        pad.showCleaning(text.substring(draftStart.coerceAtLeast(0), draftEnd.coerceAtLeast(0)))
        hold(22)
        replaceDraft("Can you grab the red headlamp and the spare batteries?")
        pad.hideVoice(); pad.talkEnded(); hold(20)
        tapKey("ENTER"); hold(16)

        // 9 · discard
        chapter("Changed your mind? Throw it away", "While listening, backspace becomes a red ✕.")
        tap(home)
        speak("actually never mind", 30)
        tapKey("DELETE")
        setField(""); pad.hideVoice(); pad.talkEnded(); hold(20)
        pad.reset(); hold(6)

        // 10 · editing
        chapter("Cut, copy, paste, delete", "Select text and four buttons appear beside the bubble.")
        setField("Meet at 6:40 by the trailhead")
        selA = 8; selB = 13; cursor = 13; sync()
        fingerOnField(true); hold(16); fingerOnField(false); hold(10)
        val cut = pad.testEditButtons().first()
        tap(cut); hold(16)

        // 11 · joystick
        chapter("Slide right for the cursor joystick", "Press the bubble and four notches appear: left the ring, right the joystick, up the keyboard, down settings.")
        pad.reset(); setField("Meet by the trailhead at 6:40 tomorrow"); hold(6)
        // the emulator's clock runs long during the ring's opening animation, which breaks up a
        // real triple tap here; the three taps are shown, and the joystick is switched on directly
        caption("Press the bubble and slide right into the notch.")
        val notch = pad.testNotch(1)
        press(home); hold(6); glide(home, notch, 8); hold(8); release()
        caption("Push the knob to aim: slow near the middle, faster toward the rim.")
        press(home); glide(home, PointF(home.x - 26 * d, home.y), 8); hold(44); release(); hold(10)
        caption("Tap the knob and the cursor jumps there.")
        tap(home); hold(14)
        caption("Hold it still for a click, then push to select.")
        press(home); hold(11); glide(home, PointF(home.x + 24 * d, home.y), 6); hold(26); release(); hold(14)
        caption("The ✕ on the rim folds it away.")
        tap(pad.testStickExit()); hold(24)

        File("build/walkthrough/chapters.json").writeText(json())
    }

    // ------------------------------------------------------------------ voice helpers

    private fun speak(words: String, frames: Int) {
        val list = words.split(" ")
        val at = cursor
        var shown = 0
        draftStart = at; draftEnd = at
        for (f in 0 until frames) {
            val want = ((f + 1) * list.size / frames.toFloat()).toInt().coerceAtMost(list.size)
            if (want > shown) {
                shown = want
                val s = list.take(shown).joinToString(" ")
                text.replace(draftStart, draftEnd, s)
                draftEnd = draftStart + s.length
                cursor = draftEnd
                pad.heardSpeech()
            }
            val t = f / 24f
            pad.showLevel((0.35f + 0.35f * abs(sin(t * 7.3f)) + 0.2f * abs(sin(t * 2.1f + 1f))).coerceIn(0f, 1f))
            step()
        }
    }

    private fun quiet(frames: Int) { repeat(frames) { pad.showLevel(0.04f); step() } }

    private fun replaceDraft(s: String) {
        if (draftStart < 0) return
        text.replace(draftStart, draftEnd, s)
        cursor = draftStart + s.length
        draftStart = -1; draftEnd = -1
    }

    // ------------------------------------------------------------------ touch helpers

    private fun lerp(a: PointF, b: PointF, t: Float) = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun chapter(title: String, caption: String) { chapters.add(Triple(frame, title, caption)); captions.add(frame to caption) }
    private fun caption(s: String) { captions.add(frame to s) }

    private fun tapKey(slot: String) { val p = pad.testSlotCenter(slot); tap(p); hold(5) }

    private fun bloomType(slot: String, ch: Char) {
        val p = pad.testSlotCenter(slot)
        press(p); hold(9)
        val n = PointF(p.x + 12 * d, p.y + 12 * d)
        glide(p, n, 2); hold(9)
        val to = pad.testLetterCenter(ch)
        glide(n, to, 7); hold(5); release(); hold(14)
    }

    private fun tap(p: PointF, frames: Int = 3) { press(p); hold(frames); release() }

    /** A fingertip tapping, without sending the touch. */
    private fun showTap(p: PointF) { finger = p; step(); step(); finger = null }

    private fun press(p: PointF) {
        finger = p; fingerDown = true
        downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, p)
    }

    private fun release() {
        finger?.let { send(MotionEvent.ACTION_UP, it) }
        fingerDown = false
        step(); finger = null
    }

    private fun glide(a: PointF, b: PointF, frames: Int) {
        for (i in 1..frames) {
            val t = i / frames.toFloat()
            val e = t * t * (3 - 2 * t)
            val p = PointF(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
            finger = p
            send(MotionEvent.ACTION_MOVE, p)
            step()
        }
    }

    private var fieldFinger = false
    private fun fingerOnField(on: Boolean) { fieldFinger = on }

    private fun hold(frames: Int) = repeat(frames) { step() }

    private fun send(action: Int, p: PointF) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
        pad.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun step() {
        idle(42)
        if (dryRun) { frame++; return }
        save()
    }
    private val dryRun = System.getProperty("walk.dry") != null || File("build/walkthrough/DRY").exists()

    // ------------------------------------------------------------------ the mock chat app

    private val ink = Color.rgb(0x1A, 0x1C, 0x20)
    private val muted = Color.rgb(0x44, 0x47, 0x4E)
    private val primary = Color.rgb(0x41, 0x5F, 0x91)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    private var frameBmp: Bitmap? = null
    private var smallBmp: Bitmap? = null
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun save() {
        val w = pad.width
        val h = pad.height
        if (frameBmp == null) {
            frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            smallBmp = Bitmap.createBitmap(720, (h * 720f / w).toInt() / 2 * 2, Bitmap.Config.ARGB_8888)
        }
        val bmp = frameBmp!!
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xF9, 0xF9, 0xFF))
        // top app bar
        paint.color = Color.rgb(0xEE, 0xF0, 0xF8); c.drawRect(0f, 0f, w.toFloat(), 64 * d, paint)
        paint.color = Color.rgb(0xD6, 0xE3, 0xFF); c.drawCircle(36 * d, 32 * d, 18 * d, paint)
        tp.color = Color.rgb(0x00, 0x1B, 0x3E); tp.textSize = 16 * d; tp.typeface = Typeface.DEFAULT_BOLD; tp.textAlign = Paint.Align.CENTER
        c.drawText("S", 36 * d, 38 * d, tp)
        tp.textAlign = Paint.Align.LEFT; tp.color = ink; tp.textSize = 18 * d
        c.drawText("Sam", 66 * d, 38 * d, tp)
        tp.typeface = Typeface.DEFAULT
        // the conversation
        var y = 88 * d
        sent.forEachIndexed { i, msg ->
            val mine = i > 0
            tp.textSize = 15 * d
            val lines = wrap(msg, 250 * d)
            val bw = lines.maxOf { tp.measureText(it) } + 28 * d
            val bh = lines.size * 21 * d + 18 * d
            val x0 = if (mine) w - 16 * d - bw else 16 * d
            paint.color = if (mine) Color.rgb(0xD6, 0xE3, 0xFF) else Color.rgb(0xE7, 0xE8, 0xEE)
            c.drawRoundRect(RectF(x0, y, x0 + bw, y + bh), 18 * d, 18 * d, paint)
            tp.color = ink
            lines.forEachIndexed { k, l -> c.drawText(l, x0 + 14 * d, y + 24 * d + k * 21 * d, tp) }
            y += bh + 10 * d
        }
        drawField(c, w, h)
        pad.draw(c)
        drawFinger(c)
        val small = smallBmp!!
        val sc = Canvas(small)
        sc.drawBitmap(bmp, android.graphics.Rect(0, 0, w, h), android.graphics.Rect(0, 0, small.width, small.height), scalePaint)
        File(out, "f_%05d.jpg".format(frame++)).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun wrap(s: String, width: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in s.split(" ")) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (tp.measureText(next) > width && line.isNotEmpty()) { out.add(line); line = word } else line = next
        }
        out.add(line)
        return out
    }

    /** The compose field: text with the cursor, a selection, the dictation draft underline, the joystick's marker. */
    private fun drawField(c: Canvas, w: Int, h: Int) {
        val top = h - 92 * d
        paint.color = Color.rgb(0xEE, 0xF0, 0xF8); c.drawRect(0f, top - 12 * d, w.toFloat(), h.toFloat(), paint)
        val box = RectF(16 * d, top, w - 16 * d, top + 64 * d)
        paint.color = Color.WHITE; c.drawRoundRect(box, 32 * d, 32 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Color.rgb(0xC4, 0xC6, 0xD0)
        c.drawRoundRect(box, 32 * d, 32 * d, paint); paint.style = Paint.Style.FILL
        tp.textSize = 16 * d; tp.color = ink
        val x0 = box.left + 22 * d
        val base = box.centerY() + 6 * d
        val s = text.toString()
        if (s.isEmpty()) { tp.color = muted; c.drawText("Message", x0, base, tp); tp.color = ink }
        // fit: show the tail when long
        var start = 0
        val maxW = box.width() - 44 * d
        while (tp.measureText(s, start, s.length) > maxW && start < s.length) start++
        fun xAt(i: Int) = x0 + tp.measureText(s, start, i.coerceIn(start, s.length))
        fun band(a: Int, b: Int, color: Int) {
            val l = xAt(min(a, b)); val r = xAt(max(a, b))
            paint.color = color; c.drawRect(l, base - 18 * d, r, base + 6 * d, paint)
        }
        if (hasSel) band(selA, selB, Color.argb(90, 0x41, 0x5F, 0x91))
        if (markA >= 0 && markA < s.length) band(markA, markA + 1, Color.argb(90, 0x41, 0x5F, 0x91))
        c.drawText(s, start, s.length, x0, base, tp)
        if (draftStart >= 0 && draftEnd > draftStart) {
            paint.color = ink; c.drawRect(xAt(draftStart), base + 4 * d, xAt(draftEnd), base + 5.5f * d, paint)
        }
        if (!hasSel && markA < 0 && (frame / 12) % 2 == 0) {
            paint.color = primary; val x = xAt(cursor); c.drawRect(x, base - 18 * d, x + 2 * d, base + 6 * d, paint)
        }
        if (fieldFinger) {
            val p = PointF((xAt(selA) + xAt(selB)) / 2, base - 6 * d)
            drawTip(c, p)
        }
    }

    private fun drawFinger(c: Canvas) { finger?.let { drawTip(c, it) } }

    private fun drawTip(c: Canvas, f: PointF) {
        val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(95, 150, 104, 84) }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(140, 110, 72, 56) }
        val r = 15 * d
        val body = RectF(f.x - r * 1.05f, f.y - r * 0.75f, f.x + r * 1.05f, f.y + r * 6f)
        c.save(); c.rotate(-10f, f.x, f.y)
        c.drawRoundRect(body, r, r, skin); c.drawRoundRect(body, r, r, edge)
        c.restore()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(f.x, f.y, 2.5f * d, dot)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun json(): String {
        val ch = chapters.joinToString(",\n") { "  {\"frame\": ${it.first}, \"title\": \"${esc(it.second)}\", \"caption\": \"${esc(it.third)}\"}" }
        val cp = captions.joinToString(",\n") { "  {\"frame\": ${it.first}, \"text\": \"${esc(it.second)}\"}" }
        return "{\"fps\": 24, \"frames\": $frame,\n\"chapters\": [\n$ch\n],\n\"captions\": [\n$cp\n]}\n"
    }

    @Suppress("unused") private val unusedPi = PI
}
