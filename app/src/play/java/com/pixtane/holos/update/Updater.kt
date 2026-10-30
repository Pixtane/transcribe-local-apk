package com.pixtane.holos.update

import android.content.Context

/** Google Play build: updates come from Play, so the self-updater is not included. */
object Updater {
    @Volatile var uiVisible = false
    fun init(context: Context) {}
}
