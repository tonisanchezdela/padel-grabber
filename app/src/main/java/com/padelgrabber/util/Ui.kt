package com.padelgrabber.util

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Colores y pequeñas ayudas para construir pantallas sin XML. */
object Ui {
    val NAVY = 0xFF0B1D33.toInt()
    val NAVY2 = 0xFF132B4A.toInt()
    val NAVY3 = 0xFF1D3A60.toInt()
    val LIME = 0xFFC6FF00.toInt()
    val WHITE = Color.WHITE
    val GREY = 0xFF9FB3C8.toInt()
    val RED = 0xFFFF3B30.toInt()

    val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

fun Context.dp(v: Int): Int = Ui.dp(this, v.toFloat())

fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
    val scroll = ScrollView(this)
    scroll.setBackgroundColor(Ui.NAVY)
    scroll.isFillViewport = true
    scroll.fitsSystemWindows = true
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(24), dp(20), dp(32))
    scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    col.build()
    setContentView(scroll)
    return col
}

fun LinearLayout.lp(topMarginDp: Int = 8): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    p.topMargin = context.dp(topMarginDp)
    return p
}

fun LinearLayout.title(text: String, sizeSp: Float = 26f, color: Int = Ui.WHITE): TextView {
    val t = TextView(context)
    t.text = text
    t.setTextColor(color)
    t.textSize = sizeSp
    t.typeface = Ui.BOLD
    addView(t, lp(12))
    return t
}

fun LinearLayout.label(text: String, sizeSp: Float = 15f, color: Int = Ui.GREY): TextView {
    val t = TextView(context)
    t.text = text
    t.setTextColor(color)
    t.textSize = sizeSp
    addView(t, lp(10))
    return t
}

fun LinearLayout.button(text: String, primary: Boolean = false, onClick: (View) -> Unit): Button {
    val b = Button(context)
    b.text = text
    b.isAllCaps = false
    b.textSize = 17f
    b.typeface = Ui.BOLD
    b.setTextColor(if (primary) Ui.NAVY else Ui.WHITE)
    b.background = Ui.rounded(if (primary) Ui.LIME else Ui.NAVY3, context.dp(14).toFloat())
    b.setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
    b.minHeight = context.dp(56)
    b.setOnClickListener(onClick)
    addView(b, lp(12))
    return b
}

fun LinearLayout.input(hint: String, value: String = "", numeric: Boolean = false, password: Boolean = false): EditText {
    val e = EditText(context)
    e.hint = hint
    e.setText(value)
    e.setTextColor(Ui.WHITE)
    e.setHintTextColor(Ui.GREY)
    e.textSize = 17f
    e.isSingleLine = true
    e.background = Ui.rounded(Ui.NAVY2, context.dp(10).toFloat(), Ui.NAVY3, context.dp(1))
    e.setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    e.inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    addView(e, lp(6))
    return e
}

@Suppress("UseSwitchCompatOrMaterialCode")
fun LinearLayout.toggle(text: String, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch {
    val s = Switch(context)
    s.text = text
    s.isChecked = checked
    s.setTextColor(Ui.WHITE)
    s.textSize = 16f
    s.setPadding(0, context.dp(6), 0, context.dp(6))
    s.thumbTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Ui.LIME, Ui.GREY)
    )
    if (onChange != null) s.setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> onChange(c) }
    addView(s, lp(6))
    return s
}

/** Fila de opciones excluyentes (tipo pestañas). */
fun LinearLayout.choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    val buttons = ArrayList<TextView>()
    fun paint(sel: Int) {
        buttons.forEachIndexed { i, b ->
            b.background = Ui.rounded(if (i == sel) Ui.LIME else Ui.NAVY2, context.dp(10).toFloat())
            b.setTextColor(if (i == sel) Ui.NAVY else Ui.WHITE)
        }
    }
    options.forEachIndexed { i, txt ->
        val b = TextView(context)
        b.text = txt
        b.gravity = Gravity.CENTER
        b.textSize = 15f
        b.typeface = Ui.BOLD
        b.setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
        b.setOnClickListener { paint(i); onSelect(i) }
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (i > 0) p.leftMargin = context.dp(8)
        row.addView(b, p)
        buttons.add(b)
    }
    paint(selected)
    addView(row, lp(6))
    return row
}
