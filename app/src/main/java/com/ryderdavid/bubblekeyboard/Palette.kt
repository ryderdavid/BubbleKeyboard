package com.ryderdavid.bubblekeyboard

import android.content.Context

/**
 * Material 3 tonal roles for the keyboard. On Android 12+ the resources point at the system's
 * dynamic (wallpaper) palette; older versions get a fixed blue palette. Light and dark come from
 * the values / values-night resource split.
 */
class Palette(context: Context) {
    val disc = context.getColor(R.color.bk_surface_container)
    val cardinal = context.getColor(R.color.bk_surface_container_highest)
    val onCardinal = context.getColor(R.color.bk_on_surface_variant)
    val diagonal = context.getColor(R.color.bk_secondary_container)
    val onDiagonal = context.getColor(R.color.bk_on_secondary_container)
    val letters = context.getColor(R.color.bk_tertiary_container)
    val onLetters = context.getColor(R.color.bk_on_tertiary_container)
    val center = context.getColor(R.color.bk_primary_container)
    val onCenter = context.getColor(R.color.bk_on_primary_container)
    val pressed = context.getColor(R.color.bk_primary)
    val onPressed = context.getColor(R.color.bk_on_primary)
    val bloomKey = context.getColor(R.color.bk_surface_bright)
    val onBloomKey = context.getColor(R.color.bk_on_surface)
}
