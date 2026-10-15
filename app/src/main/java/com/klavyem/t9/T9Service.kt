package com.klavyem.t9

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

class T9Service : InputMethodService() {

    private val tr = Locale("tr", "TR")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val teal = Color.parseColor("#26C6B0")
    private val handler = Handler(Looper.getMainLooper())

    private val keyMap = linkedMapOf(
        '2' to "abcç", '3' to "def", '4' to "ghıiğ", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuüv", '9' to "wxyz"
    )
    private val tapMap = HashMap<Char, String>()
    private val letterToDigit = HashMap<Char, Char>()
    private var words = ArrayList<String>()
    private var codes = ArrayList<String>()
    private var wordSet = HashSet<String>()

    private var digits = ""
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var shift = false
    private var autoShift = false
    private var autoCapAllowed = false
    private var t9On = true
    private var panel = 0

    private var pendingActive = false
    private var pendingKey = '2'
    private var pendingIdx = 0
    private val manualWord = StringBuilder()
    private val commitRunnable = Runnable { commitPending() }
    private val autoCapRunnable = Runnable { autoCap() }

    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > 15) deleteWord() else onBackspace()
            handler.postDelayed(this, if (repeatCount > 15) 160L else 60L)
        }
    }

    private val clips = ArrayList<String>()
    private var lastClip = ""
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private lateinit var candidateBar: LinearLayout
    private lateinit var shiftKey: TextView
    private lateinit var modeKey: TextView
    private lateinit var t9Key: TextView
    private lateinit var mainArea: LinearLayout
    private lateinit var symbolArea: LinearLayout
    private lateinit var numArea: LinearLayout
    private lateinit var emojiArea: LinearLayout
    private lateinit var clipArea: LinearLayout
    private lateinit var clipList: LinearLayout

    override fun onCreate() {
        super.onCreate()
        for ((d, letters) in keyMap) {
            tapMap[d] = letters
            for (c in letters) letterToDigit[c] = d
        }
        tapMap['1'] = ".,@?!'-"
        loadWords()
        loadClips()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(repeatRunnable)
        pendingActive = false
        manualWord.setLength(0)
        digits = ""
        candidates = emptyList()
        index = 0
        resetShift()

        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        autoCapAllowed = cls == InputType.TYPE_CLASS_TEXT &&
            v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI

        if (!restarting && ::mainArea.isInitialized && ::symbolArea.isInitialized &&
            ::numArea.isInitialized && ::emojiArea.isInitialized &&
            ::clipArea.isInitialized && ::modeKey.isInitialized
        ) {
            showPanel(
                if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 2 else 0
            )
        }
        captureClip()
        renderBar()
        autoCapSoon()
    }

    // ---------- Sözlük ----------
    private fun loadWords() {
        Thread {
            val w = ArrayList<String>()
            val c = ArrayList<String>()
            val seen = HashSet<String>()
            fun addWord(raw: String) {
                val word = raw.lowercase(tr)
                if (word.isEmpty() || !seen.add(word)) return
                val code = toCode(word) ?: return
                w.add(word)
                c.add(code)
            }
            try {
                val f = File(filesDir, "kullanici.txt")
                if (f.exists()) f.readLines().forEach { addWord(it.trim()) }
            } catch (e: Exception) {
            }
            try {
                val name = if (assets.list("")?.contains("sozluk.txt") == true)
                    "sozluk.txt" else "kelimeler.txt"
                assets.open(name).bufferedReader().use { r ->
                    r.lineSequence().forEach { line ->
                        line.split(Regex("\\s+")).forEach { addWord(it) }
                    }
                }
            } catch (e: Exception) {
            }
            handler.post {
                words = w
                codes = c
                wordSet = seen
            }
        }.start()
    }

    private fun toCode(w: String): String? {
        val sb = StringBuilder()
        for (ch in w) sb.append(letterToDigit[ch] ?: return null)
        return sb.toString()
    }

    private fun finishManualWord() {
        val w = manualWord.toString()
        manualWord.setLength(0)
        if (w.length < 2 || wordSet.contains(w)) return
        val code = toCode(w) ?: return
        words.add(0, w)
        codes.add(0, code)
        wordSet.add(w)
        try {
            File(filesDir, "kullanici.txt").appendText(w + "\n")
        } catch (e: Exception) {
        }
    }

    // ---------- Büyük harf ----------
    private fun setShift(on: Boolean) {
        shift = on
        if (::shiftKey.isInitialized) shiftKey.setTextColor(if (on) teal else Color.WHITE)
    }

    private fun resetShift() {
        autoShift = false
        setShift(false)
    }

    private fun autoCapSoon() {
        handler.removeCallbacks(autoCapRunnable)
        handler.postDelayed(autoCapRunnable, 60)
    }

    private fun autoCap() {
        if (!autoCapAllowed || digits.isNotEmpty() || pendingActive) return
        val mode = currentInputConnection?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0
        if (mode != 0) {
            autoShift = true
            setShift(true)
        } else if (autoShift) {
            autoShift = false
            setShift(false)
        }
    }
        // ---------- T9 mantığı ----------
    private fun cap(w: String) =
        if (w.isEmpty()) w else w.substring(0, 1).uppercase(tr) + w.substring(1)

    private fun current(): String {
        if (candidates.isEmpty() || index >= candidates.size) return ""
        val w = candidates[index]
        return if (shift) cap(w) else w
    }

    private fun updateCandidates() {
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (i in words.indices) {
            val c = codes[i]
            if (c == digits) {
                if (exact.size < 12) exact.add(words[i])
            } else if (prefix.size < 8 && c.length > digits.length && c.startsWith(digits)) {
                prefix.add(words[i])
            }
            if (exact.size >= 12 && prefix.size >= 8) break
        }
        val list = ArrayList<String>(exact)
        list.addAll(prefix)
        if (list.isEmpty()) list.add(digits.map { keyMap[it]!![0] }.joinToString(""))
        candidates = list
        index = 0
        showComposing()
        renderBar()
    }

    private fun showComposing() {
        currentInputConnection?.setComposingText(current(), 1)
    }

    private fun commitWord(trailing: String) {
        val ic = currentInputConnection ?: return
        if (digits.isNotEmpty()) {
            ic.commitText(current() + trailing, 1)
            digits = ""
            candidates = emptyList()
            index = 0
            resetShift()
            renderBar()
        } else if (trailing.isNotEmpty()) {
            ic.commitText(trailing, 1)
        }
    }

    // ---------- Manuel (çoklu dokunma) yazım ----------
    private fun letterFor(d: Char, i: Int): String {
        val s = tapMap[d] ?: return ""
        val ch = s[i % s.length].toString()
        return if (shift) ch.uppercase(tr) else ch
    }

    private fun onManualKey(d: Char) {
        val s = tapMap[d] ?: return
        handler.removeCallbacks(commitRunnable)
        if (pendingActive && pendingKey == d) {
            pendingIdx = (pendingIdx + 1) % s.length
        } else {
            commitPending()
            pendingKey = d
            pendingIdx = 0
            pendingActive = true
        }
        currentInputConnection?.setComposingText(letterFor(d, pendingIdx), 1)
        handler.postDelayed(commitRunnable, 900)
    }

    private fun commitPending() {
        handler.removeCallbacks(commitRunnable)
        if (!pendingActive) return
        val ch = letterFor(pendingKey, pendingIdx)
        pendingActive = false
        currentInputConnection?.commitText(ch, 1)
        if (ch.length == 1 && ch[0].isLetter()) {
            manualWord.append(ch.lowercase(tr))
        } else {
            finishManualWord()
        }
        resetShift()
    }

    private fun commitAll() {
        commitPending()
        commitWord("")
    }

    // ---------- Tuş işlemleri ----------
    private fun onDigit(d: Char) {
        if (!t9On) {
            onManualKey(d)
            return
        }
        if (d == '1') {
            punct("@")
            return
        }
        digits += d
        updateCandidates()
    }

    private fun onNext() {
        if (!t9On) {
            commitPending()
            return
        }
        if (candidates.size > 1) {
            index = (index + 1) % candidates.size
            showComposing()
            renderBar()
        }
    }

    private fun onBackspace() {
        if (pendingActive) {
            handler.removeCallbacks(commitRunnable)
            pendingActive = false
            currentInputConnection?.setComposingText("", 1)
            return
        }
        if (digits.isNotEmpty()) {
            digits = digits.dropLast(1)
            if (digits.isEmpty()) {
                currentInputConnection?.setComposingText("", 1)
                candidates = emptyList()
                index = 0
                renderBar()
            } else {
                updateCandidates()
            }
            return
        }
        if (manualWord.isNotEmpty()) manualWord.setLength(manualWord.length - 1)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        autoCapSoon()
    }

    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        if (pendingActive || digits.isNotEmpty()) {
            onBackspace()
            return
        }
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val count = before.length - i
        if (count > 0) ic.deleteSurroundingText(count, 0)
        manualWord.setLength(0)
        autoCapSoon()
    }

    private fun onEnter() {
        commitAll()
        finishManualWord()
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoCapSoon()
    }

    private fun punct(s: String) {
        commitAll()
        finishManualWord()
        currentInputConnection?.commitText(s, 1)
        resetShift()
        autoCapSoon()
    }

    private fun toggleShift() {
        autoShift = false
        setShift(!shift)
        if (digits.isNotEmpty()) {
            showComposing()
            renderBar()
        }
    }

    private fun toggleT9() {
        commitAll()
        t9On = !t9On
        t9Key.text = label("T9", if (t9On) "açık" else "kapalı")
        t9Key.setTextColor(if (t9On) teal else Color.WHITE)
    }

    // ---------- Paneller ----------
    // 0 = T9, 1 = simge, 2 = sayı, 3 = emoji, 4 = pano
    private fun showPanel(p: Int) {
        panel = p
        modeKey.text = if (p == 0) "12#" else "abc"
        mainArea.visibility = if (p == 0) View.VISIBLE else View.GONE
        symbolArea.visibility = if (p == 1) View.VISIBLE else View.GONE
        numArea.visibility = if (p == 2) View.VISIBLE else View.GONE
        emojiArea.visibility = if (p == 3) View.VISIBLE else View.GONE
        clipArea.visibility = if (p == 4) View.VISIBLE else View.GONE
        if (p == 4) renderClips()
    }

    private fun toggleMode() {
        commitAll()
        showPanel(if (panel == 0) 1 else 0)
    }

    private fun togglePano() {
        commitAll()
        showPanel(if (panel == 4) 0 else 4)
    }

    // ---------- Pano ----------
    private fun loadClips() {
        try {
            val f = File(filesDir, "pano.txt")
            if (f.exists()) {
                f.readText().split("\u001F").filter { it.isNotBlank() }.forEach { clips.add(it) }
            }
        } catch (e: Exception) {
        }
    }

    private fun saveClips() {
        try {
            File(filesDir, "pano.txt").writeText(clips.joinToString("\u001F"))
        } catch (e: Exception) {
        }
    }

    private fun captureClip() {
        try {
            val cm = clipboard ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (t == lastClip) return
            lastClip = t
            addClip(t)
        } catch (e: Exception) {
        }
    }

    private fun addClip(t: String) {
        val s = t.trim()
        if (s.isEmpty() || s.length > 2000) return
        clips.remove(s)
        clips.add(0, s)
        while (clips.size > 30) clips.removeAt(clips.size - 1)
        saveClips()
        if (panel == 4) renderClips()
    }

    private fun pasteClip(t: String) {
        commitAll()
        manualWord.setLength(0)
        currentInputConnection?.commitText(t, 1)
        showPanel(0)
        autoCapSoon()
    }

    private fun doAction(id: Int) {
        commitAll()
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun renderClips() {
        if (!::clipList.isInitialized) return
        clipList.removeAllViews()
        if (clips.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Pano boş. Kopyaladığınız metinler burada görünür."
            tv.setTextColor(Color.GRAY)
            tv.textSize = 14f
            tv.setPadding(dp(12), dp(12), dp(12), dp(12))
            clipList.addView(tv)
            return
        }
        for (c in ArrayList(clips)) {
            val tv = TextView(this)
            tv.text = c
            tv.textSize = 15f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(12), dp(8), dp(12), dp(8))
            tv.background = keyBg()
            tv.setOnClickListener { pasteClip(c) }
            tv.setOnLongClickListener {
                clips.remove(c)
                saveClips()
                renderClips()
                true
            }
            clipList.addView(tv, lp(MATCH, WRAP, 0f))
        }
    }
        // ---------- Arayüz ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun keyBg(): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply {
            setColor(c)
            cornerRadius = dp(6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.parseColor("#4A5256")))
            addState(intArrayOf(), shape(Color.parseColor("#2D3235")))
        }
    }

    private fun label(main: String, sub: String?): SpannableString {
        val text = if (sub != null) main + "\n" + sub else main
        val sp = SpannableString(text)
        if (sub != null) {
            val start = main.length + 1
            sp.setSpan(RelativeSizeSpan(0.55f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.GRAY), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun makeKey(
        main: String, sub: String?, size: Float = 20f, action: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label(main, sub)
        tv.setTextColor(Color.WHITE)
        tv.textSize = size
        tv.gravity = Gravity.CENTER
        tv.background = keyBg()
        tv.setOnClickListener { action() }
        return tv
    }

    private fun makeBackspaceKey(): TextView {
        val tv = makeKey("⌫", null, 22f) { }
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onBackspace()
                    repeatCount = 0
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatRunnable)
                }
            }
            true
        }
        return tv
    }

    private fun renderBar() {
        if (!::candidateBar.isInitialized) return
        candidateBar.removeAllViews()
        candidates.forEachIndexed { i, w ->
            val tv = TextView(this)
            tv.text = if (shift) cap(w) else w
            tv.textSize = 18f
            tv.setPadding(dp(14), 0, dp(14), 0)
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setTextColor(if (i == index) teal else Color.WHITE)
            tv.setOnClickListener {
                index = i
                commitWord(" ")
                autoCapSoon()
            }
            candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1E2124"))
        root.setPadding(dp(4), dp(4), dp(4), dp(8))

        // Üst çubuk: pano düğmesi + öneriler
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val panoKey = makeKey("📋", null, 18f) { togglePano() }
        top.addView(panoKey, lp(dp(48), MATCH, 0f))
        candidateBar = LinearLayout(this)
        candidateBar.orientation = LinearLayout.HORIZONTAL
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(candidateBar)
        top.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, dp(44)))

        // T9 alanı
        mainArea = LinearLayout(this)
        mainArea.orientation = LinearLayout.HORIZONTAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        for (p in listOf("!", "?", "'", "~")) {
            left.addView(makeKey(p, null) { punct(p) }, lp(MATCH, 0, 1f))
        }
        mainArea.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.6f))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        for (r in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (d in r) {
                val lab = if (d == '1') "@" else keyMap[d]!!
                row.addView(makeKey(lab, d.toString()) { onDigit(d) }, lp(0, MATCH, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        mainArea.addView(grid, LinearLayout.LayoutParams(0, MATCH, 3f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        right.addView(makeKey("Sonraki", "0", 13f) { onNext() }, lp(MATCH, 0, 1f))
        shiftKey = makeKey("⇧", null, 24f) { toggleShift() }
        right.addView(shiftKey, lp(MATCH, 0, 1f))
        mainArea.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(mainArea, LinearLayout.LayoutParams(MATCH, dp(176)))

        // Simge alanı
        symbolArea = LinearLayout(this)
        symbolArea.orientation = LinearLayout.HORIZONTAL
        val sScroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val simgeler = "1234567890*\"':;!?/)(+-&_₺#@£€\$¢~`|•√π÷×§∆\\}{=°%©®™✓[]<>^"
        var srow = LinearLayout(this)
        for ((i, ch) in simgeler.withIndex()) {
            if (i % 10 == 0) {
                srow = LinearLayout(this)
                srow.orientation = LinearLayout.HORIZONTAL
                srow.weightSum = 10f
                box.addView(srow, LinearLayout.LayoutParams(MATCH, dp(44)))
            }
            val s = ch.toString()
            srow.addView(makeKey(s, null, 18f) { punct(s) }, lp(0, MATCH, 1f))
        }
        sScroll.addView(box)
        symbolArea.addView(sScroll, LinearLayout.LayoutParams(0, MATCH, 6f))
        val sRight = LinearLayout(this)
        sRight.orientation = LinearLayout.VERTICAL
        sRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        sRight.addView(makeKey("😀", null, 22f) { showPanel(3) }, lp(MATCH, 0, 1f))
        sRight.addView(makeKey("123", null, 18f) { showPanel(2) }, lp(MATCH, 0, 1f))
        symbolArea.addView(sRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(symbolArea, LinearLayout.LayoutParams(MATCH, dp(176)))

        // Sayı alanı
        numArea = LinearLayout(this)
        numArea.orientation = LinearLayout.HORIZONTAL
        val nLeft = LinearLayout(this)
        nLeft.orientation = LinearLayout.VERTICAL
        for (p in listOf("+", "-", "*", "/")) {
            nLeft.addView(makeKey(p, null, 22f) { punct(p) }, lp(MATCH, 0, 1f))
        }
        numArea.addView(nLeft, LinearLayout.LayoutParams(0, MATCH, 0.7f))
        val nGrid = LinearLayout(this)
        nGrid.orientation = LinearLayout.VERTICAL
        val nrows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf(",", "0", ".")
        )
        for (r in nrows) {
            val nrow = LinearLayout(this)
            nrow.orientation = LinearLayout.HORIZONTAL
            for (s in r) {
                nrow.addView(makeKey(s, null, 24f) { punct(s) }, lp(0, MATCH, 1f))
            }
            nGrid.addView(nrow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        numArea.addView(nGrid, LinearLayout.LayoutParams(0, MATCH, 3f))
        val nRight = LinearLayout(this)
        nRight.orientation = LinearLayout.VERTICAL
        nRight.addView(makeKey("%", null, 22f) { punct("%") }, lp(MATCH, 0, 1f))
        nRight.addView(makeKey("=", null, 22f) { punct("=") }, lp(MATCH, 0, 1f))
        nRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        nRight.addView(makeKey("!?#", null, 16f) { showPanel(1) }, lp(MATCH, 0, 1f))
        numArea.addView(nRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(numArea, LinearLayout.LayoutParams(MATCH, dp(176)))

        // Emoji alanı
        emojiArea = LinearLayout(this)
        emojiArea.orientation = LinearLayout.HORIZONTAL
        val eScroll = ScrollView(this)
        val eBox = LinearLayout(this)
        eBox.orientation = LinearLayout.VERTICAL
        val emojiler = ("😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤗 🤭 🤔 " +
            "😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 😴 😷 🤒 🥵 🥶 😵 🤯 🤠 😎 🤓 😕 😟 🙁 😮 😲 😳 🥺 " +
            "😨 😰 😥 😢 😭 😱 😖 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖 " +
            "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 👋 👏 🙌 🤝 🙏 💪 " +
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💞 💓 💗 💖 💘 💝 " +
            "🔥 ✨ ⭐ 🌟 💥 💫 💦 🎉 🎊 🎁 🎂 🎈 🌹 🌸 🌞 🌙 ☀️ ❄️ 🌈 ☕ 🍕 🍔 🍟 🍰 🍎 🍌 🍉 🍺 " +
            "⚽ 🏀 🎵 🎶 📱 💻 📷 🎮 🚗 ✈️ 🏠 💰 💯 ✅ ❌ ⚠️ ❓ ❗ 🇹🇷").split(" ")
        var erow = LinearLayout(this)
        for ((i, em) in emojiler.withIndex()) {
            if (i % 8 == 0) {
                erow = LinearLayout(this)
                erow.orientation = LinearLayout.HORIZONTAL
                erow.weightSum = 8f
                eBox.addView(erow, LinearLayout.LayoutParams(MATCH, dp(46)))
            }
            erow.addView(makeKey(em, null, 22f) { punct(em) }, lp(0, MATCH, 1f))
        }
        eScroll.addView(eBox)
        emojiArea.addView(eScroll, LinearLayout.LayoutParams(0, MATCH, 6f))
        val eRight = LinearLayout(this)
        eRight.orientation = LinearLayout.VERTICAL
        eRight.addView(makeBackspaceKey(), lp(MATCH, 0, 1f))
        eRight.addView(makeKey("!?#", null, 16f) { showPanel(1) }, lp(MATCH, 0, 1f))
        eRight.addView(makeKey("123", null, 18f) { showPanel(2) }, lp(MATCH, 0, 1f))
        emojiArea.addView(eRight, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(emojiArea, LinearLayout.LayoutParams(MATCH, dp(176)))

        // Pano alanı
        clipArea = LinearLayout(this)
        clipArea.orientation = LinearLayout.VERTICAL
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(makeKey("Tümünü seç", null, 12f) { doAction(android.R.id.selectAll) }, lp(0, MATCH, 1.4f))
        actions.addView(makeKey("Kes", null, 13f) { doAction(android.R.id.cut) }, lp(0, MATCH, 1f))
        actions.addView(makeKey("Kopyala", null, 13f) { doAction(android.R.id.copy) }, lp(0, MATCH, 1.2f))
        actions.addView(makeKey("Yapıştır", null, 13f) { doAction(android.R.id.paste) }, lp(0, MATCH, 1.3f))
        actions.addView(makeBackspaceKey(), lp(0, MATCH, 0.9f))
        clipArea.addView(actions, LinearLayout.LayoutParams(MATCH, dp(44)))
        val cScroll = ScrollView(this)
        clipList = LinearLayout(this)
        clipList.orientation = LinearLayout.VERTICAL
        cScroll.addView(clipList)
        clipArea.addView(cScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(clipArea, LinearLayout.LayoutParams(MATCH, dp(176)))

        // Alt satır
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        modeKey = makeKey("12#", null, 16f) { toggleMode() }
        bottom.addView(modeKey, lp(0, MATCH, 1f))
        t9Key = makeKey("T9", if (t9On) "açık" else "kapalı", 15f) { toggleT9() }
        t9Key.setTextColor(if (t9On) teal else Color.WHITE)
        bottom.addView(t9Key, lp(0, MATCH, 1f))
        bottom.addView(makeKey(",", null) { punct(",") }, lp(0, MATCH, 0.7f))
        bottom.addView(makeKey("Türkçe", null, 14f) { punct(" ") }, lp(0, MATCH, 3f))
        bottom.addView(makeKey(".", null) { punct(".") }, lp(0, MATCH, 0.7f))
        bottom.addView(makeKey("↵", null, 22f) { onEnter() }, lp(0, MATCH, 1.2f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(52)))

        showPanel(panel)
        return root
    }
}
