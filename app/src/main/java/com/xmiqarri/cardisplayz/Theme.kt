package com.xmiqarri.cardisplayz

/**
 * The colours of the app, in one place. Every screen takes its colours from here,
 * so the look stays consistent and can be changed in a single file.
 */
object Theme {
    /** Screen background: a dark navy that fades to near black at the bottom. */
    const val BACKGROUND_TOP = 0xFF0C1526.toInt()
    const val BACKGROUND_BOTTOM = 0xFF05080F.toInt()
    const val BACKGROUND_SOLID = 0xFF0A1220.toInt()

    /** Cards, rows and tiles. */
    const val SURFACE = 0xFF131D30.toInt()
    const val SURFACE_PRESSED = 0x332BB3C0
    const val SURFACE_BORDER = 0x2E2BB3C0

    /** Brand accent (teal): section titles, selected chips, toggles. */
    const val ACCENT = 0xFF2BB3C0.toInt()
    const val ON_ACCENT = 0xFF03131A.toInt()

    /** Text. */
    const val TEXT_PRIMARY = 0xFFF2F6FB.toInt()
    const val TEXT_SECONDARY = 0xFF8FA3BF.toInt()
    const val TEXT_ON_SURFACE_CHIP = 0xFFB8C7DA.toInt()

    /** Search box and unselected chips. */
    const val INPUT = 0xFF17233A.toInt()
    const val HINT = 0x66FFFFFF
}
