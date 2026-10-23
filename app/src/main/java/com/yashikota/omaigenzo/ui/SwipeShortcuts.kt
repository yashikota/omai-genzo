package com.yashikota.omaigenzo.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.data.SelectionShareText

/** What a hardware key does on the swipe screen. */
sealed interface SwipeCommand {
    data object Keep : SwipeCommand
    data object Reject : SwipeCommand
    data object Skip : SwipeCommand
    data object Undo : SwipeCommand
    data class Rate(val stars: Int) : SwipeCommand
}

/**
 * Keyboard, D-pad and volume-key shortcuts, so a whole folder can be culled one-handed or from a
 * Bluetooth keyboard. Arrow keys follow the on-screen swipe direction, including when it is swapped;
 * P / X match Lightroom's pick / reject keys, and the digits set the star rating.
 */
object SwipeKeyMap {
    fun commandFor(keyCode: Int, reversedDirections: Boolean = false): SwipeCommand? {
        val right = if (reversedDirections) SwipeCommand.Reject else SwipeCommand.Keep
        val left = if (reversedDirections) SwipeCommand.Keep else SwipeCommand.Reject
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> right
            KeyEvent.KEYCODE_DPAD_LEFT -> left
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_K -> SwipeCommand.Keep
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_X -> SwipeCommand.Reject
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_S -> SwipeCommand.Skip
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_DEL -> SwipeCommand.Undo
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_5 -> SwipeCommand.Rate(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_5 -> SwipeCommand.Rate(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> null
        }
    }
}

/** Distinct, system-tuned feedback for each decision, so a swipe can be confirmed without looking. */
object SwipeHaptics {
    fun perform(view: View, command: SwipeCommand) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val constant = when (command) {
            SwipeCommand.Keep -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            SwipeCommand.Reject -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            SwipeCommand.Skip, SwipeCommand.Undo -> HapticFeedbackConstants.KEYBOARD_TAP
            is SwipeCommand.Rate -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }
}

/** Opens the share sheet with the kept file names, one per line. */
fun shareKeptFileList(context: Context, photos: List<PhotoItem>) {
    val text = SelectionShareText.build(photos)
    if (text.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "キープした写真の一覧")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "キープ一覧を共有"))
}
