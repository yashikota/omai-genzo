package com.yashikota.omaigenzo

import android.view.KeyEvent
import com.yashikota.omaigenzo.ui.SwipeCommand
import com.yashikota.omaigenzo.ui.SwipeKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeKeyMapTest {

    @Test
    fun arrowKeysFollowTheSwipeDirection() {
        assertEquals(SwipeCommand.Keep, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(SwipeCommand.Reject, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(SwipeCommand.Reject, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_RIGHT, reversedDirections = true))
        assertEquals(SwipeCommand.Keep, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_LEFT, reversedDirections = true))
        assertEquals(SwipeCommand.Undo, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(SwipeCommand.Skip, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun namedKeysDoNotDependOnTheSwipeDirection() {
        for (reversed in listOf(false, true)) {
            assertEquals(SwipeCommand.Keep, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_VOLUME_UP, reversed))
            assertEquals(SwipeCommand.Reject, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_VOLUME_DOWN, reversed))
            assertEquals(SwipeCommand.Keep, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_P, reversed))
            assertEquals(SwipeCommand.Reject, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_X, reversed))
            assertEquals(SwipeCommand.Undo, SwipeKeyMap.commandFor(KeyEvent.KEYCODE_Z, reversed))
        }
    }

    @Test
    fun digitsSetTheRating() {
        for (stars in 0..5) {
            assertEquals(SwipeCommand.Rate(stars), SwipeKeyMap.commandFor(KeyEvent.KEYCODE_0 + stars))
            assertEquals(SwipeCommand.Rate(stars), SwipeKeyMap.commandFor(KeyEvent.KEYCODE_NUMPAD_0 + stars))
        }
        assertNull(SwipeKeyMap.commandFor(KeyEvent.KEYCODE_6))
        assertNull(SwipeKeyMap.commandFor(KeyEvent.KEYCODE_NUMPAD_9))
    }

    @Test
    fun unrelatedKeysAreLeftToTheSystem() {
        assertNull(SwipeKeyMap.commandFor(KeyEvent.KEYCODE_BACK))
        assertNull(SwipeKeyMap.commandFor(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertNull(SwipeKeyMap.commandFor(KeyEvent.KEYCODE_A))
    }
}
