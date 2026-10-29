package com.sarv.auth.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sarv.auth.R

/**
 * "Login with Sarv" button, styled from Sarv's design tokens so it matches the
 * web and iOS buttons. It only draws and reports clicks — call
 * `SarvAuthClient.signIn()` from `setOnClickListener`.
 *
 * XML: `app:sarvVariant="brand|surface"`, `app:sarvSize="small|medium|large"`,
 * `app:sarvTheme="auto|light|dark"`, `android:text`.
 */
class SarvLoginButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Variant { BRAND, SURFACE }

    enum class Size(val heightDp: Int, val textSp: Float, val iconDp: Int, val paddingDp: Int) {
        SMALL(30, 12f, 18, 12), MEDIUM(38, 13f, 24, 16), LARGE(44, 14f, 28, 20)
    }

    enum class Theme { AUTO, LIGHT, DARK }

    private val icon = ImageView(context)
    private val label = TextView(context)

    var text: CharSequence
        get() = label.text
        set(value) { label.text = value; contentDescription = value }

    var variant: Variant = Variant.BRAND
        set(value) { field = value; render() }

    var size: Size = Size.MEDIUM
        set(value) { field = value; render(); requestLayout() }

    var theme: Theme = Theme.AUTO
        set(value) { field = value; render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        icon.setImageResource(R.drawable.sarv_auth_mark)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        label.typeface = Typeface.DEFAULT_BOLD
        label.maxLines = 1
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon)
        addView(label)

        val a = context.obtainStyledAttributes(attrs, R.styleable.SarvLoginButton, defStyleAttr, 0)
        try {
            text = a.getText(R.styleable.SarvLoginButton_android_text) ?: context.getString(R.string.sarv_auth_login)
            variant = Variant.entries[a.getInt(R.styleable.SarvLoginButton_sarvVariant, 0)]
            size = Size.entries[a.getInt(R.styleable.SarvLoginButton_sarvSize, 1)]
            theme = Theme.entries[a.getInt(R.styleable.SarvLoginButton_sarvTheme, 0)]
        } finally {
            a.recycle()
        }
        render()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(size.heightDp), MeasureSpec.EXACTLY))
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (theme == Theme.AUTO) render()
    }

    private fun render() {
        val palette = when (theme) {
            Theme.LIGHT -> Palette.LIGHT
            Theme.DARK -> Palette.DARK
            Theme.AUTO -> {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
            }
        }
        val fill = if (variant == Variant.BRAND) palette.brand else palette.surface
        val shape = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(fill)
            if (variant == Variant.SURFACE) setStroke(dp(1), palette.border)
        }
        val pressed = if (variant == Variant.BRAND) palette.brandStrong else palette.border
        background = RippleDrawable(ColorStateList.valueOf(pressed), shape, null)

        label.setTextColor(if (variant == Variant.BRAND) Color.WHITE else palette.ink)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.textSp)
        icon.layoutParams = LayoutParams(dp(size.iconDp), dp(size.iconDp)).apply { marginEnd = dp(8) }
        setPadding(dp(size.paddingDp), 0, dp(size.paddingDp), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class Palette(val brand: Int, val brandStrong: Int, val surface: Int, val border: Int, val ink: Int) {
        companion object {
            val LIGHT = Palette(0xFF3069B0.toInt(), 0xFF275695.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6E9F1.toInt(), 0xFF2A3450.toInt())
            val DARK = Palette(0xFF5B92D2.toInt(), 0xFF84B0E0.toInt(), 0xFF131826.toInt(), 0xFF232838.toInt(), 0xFFD8DDE9.toInt())
        }
    }
}
