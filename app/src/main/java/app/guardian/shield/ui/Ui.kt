package app.guardian.shield.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.guardian.shield.R

/**
 * A small set of view builders.
 *
 * The screens here are built in code rather than XML: each one is a short
 * vertical list of cards, and keeping them in Kotlin means the status text and
 * the logic that computes it sit next to each other instead of in two files.
 */

enum class StatusLevel { OK, WARN, BAD }

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun Context.themeColor(resId: Int): Int = ContextCompat.getColor(this, resId)

// ---------------- text ----------------

private fun Context.text(
    value: CharSequence,
    sizeSp: Float,
    colorRes: Int,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(themeColor(colorRes))
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, 1.25f)
}

fun Context.titleText(value: CharSequence): TextView =
    text(value, 26f, R.color.on_surface, bold = true)

fun Context.headingText(value: CharSequence): TextView =
    text(value, 17f, R.color.on_surface, bold = true)

fun Context.bodyText(value: CharSequence): TextView =
    text(value, 15f, R.color.on_surface)

fun Context.mutedText(value: CharSequence): TextView =
    text(value, 13f, R.color.on_surface_muted)

fun Context.bigNumber(value: CharSequence): TextView =
    text(value, 40f, R.color.accent, bold = true)

// ---------------- containers ----------------

fun Context.verticalRoot(padding: Int = 20): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding + 20))
}

fun Context.card(accentRes: Int? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(themeColor(R.color.surface_card))
        if (accentRes != null) setStroke(dp(2), themeColor(accentRes))
    }
    setPadding(dp(16), dp(16), dp(16), dp(16))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }
}

fun Context.spacer(height: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(height)
    )
}

/** Wraps [content] in a scroll view and installs it as the activity's content. */
fun Activity.setScrollingContent(content: View) {
    setContentView(
        ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(themeColor(R.color.surface))
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    )
}

// ---------------- controls ----------------

fun Context.primaryButton(label: CharSequence, onClick: () -> Unit): Button =
    buildButton(label, themeColor(R.color.accent), themeColor(R.color.on_accent), onClick)

fun Context.secondaryButton(label: CharSequence, onClick: () -> Unit): Button =
    buildButton(label, themeColor(R.color.surface_card), themeColor(R.color.on_surface), onClick)

fun Context.dangerButton(label: CharSequence, onClick: () -> Unit): Button =
    buildButton(label, themeColor(R.color.bad), Color.WHITE, onClick)

private fun Context.buildButton(
    label: CharSequence,
    background: Int,
    textColour: Int,
    onClick: () -> Unit,
): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(textColour)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    val buttonShape = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(background)
    }
    this.background = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), buttonShape, null
    )
    minHeight = dp(48)
    setPadding(dp(18), dp(10), dp(18), dp(10))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }
    setOnClickListener { onClick() }
}

fun Context.editField(
    hint: String,
    initial: String = "",
    multiline: Boolean = false,
    numeric: Boolean = false,
): EditText = EditText(this).apply {
    this.hint = hint
    setText(initial)
    setTextColor(themeColor(R.color.on_surface))
    setHintTextColor(themeColor(R.color.on_surface_muted))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else -> InputType.TYPE_CLASS_TEXT
    }
    if (multiline) {
        minLines = 3
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
    }
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(themeColor(R.color.surface))
        setStroke(dp(1), themeColor(R.color.on_surface_muted))
    }
    setPadding(dp(14), dp(12), dp(14), dp(12))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }
}

/**
 * One protection layer, its state, and the button that fixes it.
 *
 * Every layer in the app reports through this, so a glance at the dashboard
 * shows exactly which part of the protection is not working.
 */
fun Context.statusCard(
    label: String,
    detail: String,
    level: StatusLevel,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
): View {
    val colourRes = when (level) {
        StatusLevel.OK -> R.color.ok
        StatusLevel.WARN -> R.color.warn
        StatusLevel.BAD -> R.color.bad
    }
    val marker = when (level) {
        StatusLevel.OK -> "Active"
        StatusLevel.WARN -> "Needs attention"
        StatusLevel.BAD -> "Not working"
    }

    return card(accentRes = if (level == StatusLevel.OK) null else colourRes).apply {
        addView(LinearLayout(this@statusCard).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(headingText(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(marker, 13f, colourRes, bold = true))
        })
        addView(spacer(6))
        addView(mutedText(detail))
        if (actionLabel != null && onAction != null) {
            addView(secondaryButton(actionLabel, onAction))
        }
    }
}
