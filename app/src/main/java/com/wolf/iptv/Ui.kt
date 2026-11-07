package com.wolf.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val BG = Color.parseColor("#0D0E12")
    val PANEL = Color.parseColor("#16181F")
    val FIELD = Color.parseColor("#1E2029")
    val LINE = Color.parseColor("#2A2D3A")
    val ACC = Color.parseColor("#3B82F6")
    val SEL = Color.parseColor("#263453")
    val FOCUS = Color.parseColor("#2F5BD3")
    val FG = Color.parseColor("#ECEEF4")
    val MUTED = Color.parseColor("#9AA0B4")
    val PLAY = Color.parseColor("#7CC4FF")
    val RED = Color.parseColor("#EF4444")
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

/** Kumanda/gamepad ile gezilirken odaklanan öğe belirgin olsun. */
fun states(ctx: Context, normal: Int, radius: Float = ctx.dp(8).toFloat()): StateListDrawable =
    StateListDrawable().apply {
        val st = ctx.dp(3)
        addState(intArrayOf(android.R.attr.state_focused), round(C.FOCUS, radius, st, C.ACC))
        addState(intArrayOf(android.R.attr.state_pressed), round(C.SEL, radius))
        addState(intArrayOf(android.R.attr.state_activated), round(C.ACC, radius))
        addState(intArrayOf(), round(normal, radius))
    }

fun Context.text(s: String, size: Float = 15f, color: Int = C.FG, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(s: String, normal: Int = C.FIELD, onClick: (View) -> Unit): Button =
    Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(C.FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = states(context, normal)
        stateListAnimator = null
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        setOnClickListener(onClick)
    }

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Satır listesi: numara + başlık + (isteğe bağlı) alt satır; oynayan öğe vurgulanır. */
class RowAdapter(private val ctx: Context) : BaseAdapter() {
    data class Row(val num: String, val title: String, val sub: String = "", val playing: Boolean = false)

    var rows: List<Row> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }
    var selected: Int = -1
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val box: LinearLayout
        val num: TextView
        val title: TextView
        val sub: TextView
        if (convertView is LinearLayout && convertView.tag is Array<*>) {
            box = convertView
            @Suppress("UNCHECKED_CAST")
            val t = convertView.tag as Array<TextView>
            num = t[0]; title = t[1]; sub = t[2]
        } else {
            box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(10), ctx.dp(11), ctx.dp(10), ctx.dp(11))
            }
            num = ctx.text("", 13f, C.MUTED).apply { minWidth = ctx.dp(34); gravity = Gravity.END }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            title = ctx.text("", 15.5f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            sub = ctx.text("", 12.5f, C.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            col.addView(title)
            col.addView(sub)
            box.addView(num, lp(WRAP, WRAP))
            box.addView(col, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(12) })
            box.tag = arrayOf(num, title, sub)
        }
        val r = rows[position]
        num.text = r.num
        num.visibility = if (r.num.isEmpty()) View.GONE else View.VISIBLE
        title.text = if (r.playing) "▶ " + r.title else r.title
        title.setTextColor(if (r.playing) C.PLAY else C.FG)
        title.typeface = if (r.playing) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        sub.text = r.sub
        sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        box.setBackgroundColor(if (position == selected) C.SEL else Color.TRANSPARENT)
        return box
    }
}
