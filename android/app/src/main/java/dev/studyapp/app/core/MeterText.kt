package dev.studyapp.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Text for the seven-segment "meter" readouts (balance, fares, tier rates). Pure Kotlin so the
 * formatting is unit-tested; the drawing lives in ui/Meter.kt.
 */
object MeterText {
    /**
     * Two decimals, left-padded with spaces to [minIntDigits] integer digits. The display draws a
     * space as an unlit cell, so a balance of ₹19.98 on a 4-digit meter reads "  19.98" with two
     * dim 8s in front, like a real fare meter. Grows past [minIntDigits] rather than truncating.
     */
    fun of(amount: Double, minIntDigits: Int = 1): String {
        val r = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        val digits = r.abs().toPlainString()
        val sign = if (r.signum() < 0) "-" else ""
        val intLen = digits.substringBefore('.').length + sign.length
        return " ".repeat((minIntDigits - intLen).coerceAtLeast(0)) + sign + digits
    }

    /** A real charge too small to show at paise precision (the readout says 0.00, the caption says "under ₹0.01"). */
    fun isSubPaisa(amount: Double): Boolean = amount > 0.0 && amount < 0.005
}
