package com.pixtane.holos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.pixtane.holos.ui.screens.CreateScreen
import com.pixtane.holos.ui.screens.LibraryScreen
import com.pixtane.holos.ui.screens.PlayerScreen
import com.pixtane.holos.ui.screens.SettingsScreen
import com.pixtane.holos.ui.theme.HolosColors

sealed interface Screen {
    data object Library : Screen
    data class Create(val text: String = "") : Screen
    data class Player(val id: String) : Screen
    data object Settings : Screen
}

class Nav(initial: Screen) {
    var stack by mutableStateOf(listOf(initial))
        private set
    val current get() = stack.last()
    var forward by mutableStateOf(true)
        private set

    fun push(s: Screen) { forward = true; stack = stack + s }
    fun replace(s: Screen) { forward = true; stack = stack.dropLast(1) + s }
    fun pop() { if (stack.size > 1) { forward = false; stack = stack.dropLast(1) } }
}

/** Thin helpers over the MediaController so screens stay declarative. */
object PlayerActions {
    fun load(c: MediaController?, id: String) {
        c ?: return
        if (c.currentMediaItem?.mediaId != id) {
            c.setMediaItem(MediaItem.Builder().setMediaId(id).build())
            c.prepare()
        }
    }

    fun play(c: MediaController?, id: String) {
        c ?: return
        load(c, id)
        if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun toggle(c: MediaController?, id: String) {
        c ?: return
        if (c.currentMediaItem?.mediaId == id && c.playWhenReady) c.pause() else play(c, id)
    }
}

@Composable
fun HolosRoot(controller: MediaController?, sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val nav = remember { Nav(Screen.Library) }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.push(Screen.Create(sharedText))
            onSharedTextConsumed()
        }
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Box(Modifier.fillMaxSize().background(HolosColors.Bg)) {
        AnimatedContent(
            targetState = nav.current,
            transitionSpec = {
                val dir = if (nav.forward) 1 else -1
                (slideInHorizontally(tween(280)) { it / 5 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(280)) { -it / 5 * dir } + fadeOut(tween(180)))
            },
            label = "nav",
        ) { screen ->
            when (screen) {
                Screen.Library -> LibraryScreen(nav, controller)
                is Screen.Create -> CreateScreen(nav, controller, screen.text)
                is Screen.Player -> PlayerScreen(nav, controller, screen.id)
                Screen.Settings -> SettingsScreen(nav)
            }
        }
    }
}
