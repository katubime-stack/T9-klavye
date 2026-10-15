package com.klavyem.t9

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class T9Service : InputMethodService() {

    private val tr = Locale("tr", "TR")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val teal = Color.parseColor("#26C6B0")

    private val keyMap = linkedMapOf(
        '2' to "abcç", '3' to "def", '4' to "ghıiğ", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuüv", '9' to "wxyz"
    )
    private val letterToDigit = HashMap<Char, Char>()
    private val words = ArrayList<String>()
    private val codes = ArrayList<String>()

    private var digits = ""
    private var candidates: List<String> = emptyList()
    private var index = 0
    private var shift = false
    private var numberMode = false

    private lateinit var candidateBar: LinearLayout
    private lateinit var shiftKey: TextView
    private lateinit var modeKey: TextView

    override fun onCreate() {
        super.onCreate()
        for ((d, letters) in keyMap) for (c in letters) letterToDigit[c] = d
        loadWords()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        digits = ""
        candidates = emptyList()
        index = 0
        shift = false
        if (::shiftKey.isInitialized) shiftKey.setTextColor(Color.WHITE)
        renderBar()
    }

    private fun loadWords() {
        try {
            val text = assets.open("kelimeler.txt").bufferedReader().use { it.readText() }
            val seen = HashSet<String>()
            for (w in text.split(Regex("\\s+"))) {
                val word = w.lowercase(tr)
                if (word.isEmpty() || !seen.add(word)) continue
                val code = toCode(word) ?: continue
                words.add(word)
                codes.add(code)
            }
        } catch (e: Exception) {
        }
    }

    private fun toCode(w: String): String? {
        val sb = StringBuilder()
        for (c in w) sb.append(letterToDigit[c] ?: return null)
        return sb.toString()
    }

    private fun cap(w: String) =
        if (w.isEmpty()) w else w.substring(0, 1).uppercase(tr) + w.substring(1)

    private fun current(): String {
        if (candidates.isEmpty()) return ""
        val w = candidates[index]
        return if (shift) cap(w) else w
    }

    private fun updateCandidates() {
        val exact = ArrayList<String>()
        val prefix = ArrayList<String>()
        for (i in words.indices) {
            val c = codes[i]
            if (c == digits) exact.add(words[i])
            else if (c.length > digits.length && c.startsWith(digits)) prefix.add(words[i])
        }
        val list = ArrayList<String>(exact)
        list.addAll(prefix.take(8))
        if (list.isEmpty()) list.add(digits.map { keyMap[it]!![0] }.joinToString(""))
        candidates = list.take(15)
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
            shift = false
            shiftKey.setTextColor(Color.WHITE)
            renderBar()
        } else if (trailing.isNotEmpty()) {
            ic.commitText(trailing, 1)
        }
    }

    private fun onDigit(d: Char) {
        if (numberMode) {
            commitWord("")
            currentInputConnection?.commitText(d.toString(), 1)
            return
        }
        if (d == '1') {
            commitWord("")
            currentInputConnection?.commitText("@", 1)
            return
        }
        digits += d
        updateCandidates()
    }

    private fun onNext() {
        if (numberMode) {
            currentInputConnection?.commitText("0", 1)
            return
        }
        if (candidates.size > 1) {
            index = (index + 1) % candidates.size
            showComposing()
            renderBar()
        }
    }

    private fun onBackspace() {
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
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun onEnter() {
        commitWord("")
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
    }

    private fun punct(s: String) {
        commitWord("")
        currentInputConnection?.commitText(s, 1)
    }

    private fun toggleShift() {
        shift = !shift
        shiftKey.setTextColor(if (shift) teal else Color.WHITE)
        if (digits.isNotEmpty()) {
            showComposing()
            renderBar()
        }
    }

    private fun toggleMode() {
        commitWord("")
        numberMode = !numberMode
        modeKey.text = if (numberMode) "abc" else "12#"
    }

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

    private fun makeKey(
        main: String, sub: String?, size: Float = 20f, action: () -> Unit
    ): TextView {
        val text = if (sub != null) main + "\n" + sub else main
        val sp = SpannableString(text)
        if (sub != null) {
            val start = main.length + 1
            sp.setSpan(RelativeSizeSpan(0.55f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.GRAY), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val tv = TextView(this)
        tv.text = sp
        tv.setTextColor(Color.WHITE)
        tv.textSize = size
        tv.gravity = Gravity.CENTER
        tv.background = keyBg()
        tv.setOnClickListener { action() }
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
            }
            candidateBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1E2124"))
        root.setPadding(dp(4), dp(4), dp(4), dp(8))

        candidateBar = LinearLayout(this)
        candidateBar.orientation = LinearLayout.HORIZONTAL
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(candidateBar)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(44)))

        val main = LinearLayout(this)
        main.orientation = LinearLayout.HORIZONTAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        for (p in listOf("!", "?", "'", "~")) {
            left.addView(makeKey(p, null) { punct(p) }, lp(MATCH, 0, 1f))
        }
        main.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.6f))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        for (r in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (d in r) {
                val label = if (d == '1') "@" else keyMap[d]!!
                row.addView(makeKey(label, d.toString()) { onDigit(d) }, lp(0, MATCH, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        main.addView(grid, LinearLayout.LayoutParams(0, MATCH, 3f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.addView(makeKey("⌫", null, 22f) { onBackspace() }, lp(MATCH, 0, 1f))
        right.addView(makeKey("Sonraki", "0", 13f) { onNext() }, lp(MATCH, 0, 1f))
        shiftKey = makeKey("⇧", null, 24f) { toggleShift() }
        right.addView(shiftKey, lp(MATCH, 0, 1f))
        main.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))

        root.addView(main, LinearLayout.LayoutParams(MATCH, dp(176)))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        modeKey = makeKey("12#", null, 16f) { toggleMode() }
        bottom.addView(modeKey, lp(0, MATCH, 1f))
        bottom.addView(makeKey(",", null) { punct(",") }, lp(0, MATCH, 0.8f))
        bottom.addView(makeKey("Türkçe", null, 14f) { punct(" ") }, lp(0, MATCH, 3.5f))
        bottom.addView(makeKey(".", null) { punct(".") }, lp(0, MATCH, 0.8f))
        bottom.addView(makeKey("↵", null, 22f) { onEnter() }, lp(0, MATCH, 1.2f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(52)))

        return root
    }
}
