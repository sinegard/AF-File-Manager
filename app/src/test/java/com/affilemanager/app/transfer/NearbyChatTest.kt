package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NearbyChatTest {
    @Test fun messagesAreBoundedValidatedAndNeverCrossSessions() {
        NearbyChatController.clear()
        val first = NearbyPairing("192.168.1.2", 8080, "12345678", "First")
        NearbyChatController.beginSession(first)
        repeat(NearbyChatController.MAX_VISIBLE_MESSAGES + 4) {
            NearbyChatController.received("First", "message-$it")
        }
        assertEquals(NearbyChatController.MAX_VISIBLE_MESSAGES, NearbyChatController.state.value.messages.size)
        assertEquals("message-4", NearbyChatController.state.value.messages.first().body)
        assertFalse(NearbyChatController.state.value.messages.first().outgoing)

        NearbyChatController.beginSession(NearbyPairing("192.168.1.3", 8080, "87654321", "Second"))
        assertEquals(emptyList<NearbyChatMessage>(), NearbyChatController.state.value.messages)
    }

    @Test fun blankOversizedAndControlCharacterMessagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { NearbyChatController.validate("  ") }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyChatController.validate("x".repeat(NearbyChatController.MAX_MESSAGE_CHARACTERS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { NearbyChatController.validate("hello\u0000world") }
        assertEquals("hello\nworld", NearbyChatController.validate("  hello\nworld  "))
    }
}
