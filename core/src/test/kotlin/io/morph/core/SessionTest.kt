package io.morph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun `happy path reaches complete`() {
        val session = DropSession(DropSession.newSessionId(), Role.SENDER)
        assertEquals(SessionState.IDLE, session.state)
        assertTrue(session.transition(SessionEvent.NFC_TAP))
        assertEquals(SessionState.HANDSHAKE, session.state)
        assertTrue(session.transition(SessionEvent.HELLO_OK))
        assertEquals(SessionState.NEGOTIATED, session.state)
        assertTrue(session.transition(SessionEvent.OFFER))
        assertEquals(SessionState.OFFERED, session.state)
        assertTrue(session.transition(SessionEvent.ACCEPT))
        assertEquals(SessionState.TRANSFERRING, session.state)
        assertTrue(session.transition(SessionEvent.DONE))
        assertEquals(SessionState.VERIFYING, session.state)
        assertTrue(session.transition(SessionEvent.HASH_OK))
        assertEquals(SessionState.COMPLETE, session.state)
    }

    @Test
    fun `resume path returns to transferring`() {
        val session = DropSession(DropSession.newSessionId(), Role.RECEIVER)
        listOf(
            SessionEvent.NFC_TAP,
            SessionEvent.HELLO_OK,
            SessionEvent.OFFER,
            SessionEvent.ACCEPT,
        ).forEach { assertTrue(session.transition(it)) }
        assertTrue(session.transition(SessionEvent.LINK_LOST))
        assertEquals(SessionState.RESUMING, session.state)
        assertTrue(session.transition(SessionEvent.RESUME))
        assertEquals(SessionState.TRANSFERRING, session.state)
    }

    @Test
    fun `illegal event is rejected`() {
        val session = DropSession(DropSession.newSessionId(), Role.SENDER)
        assertFalse(session.transition(SessionEvent.ACCEPT))
        assertEquals(SessionState.IDLE, session.state)
    }

    @Test
    fun `abort works from active states`() {
        val session = DropSession(DropSession.newSessionId(), Role.SENDER)
        session.transition(SessionEvent.NFC_TAP)
        assertTrue(session.transition(SessionEvent.TIMEOUT))
        assertEquals(SessionState.ABORTED, session.state)
        assertFalse(session.transition(SessionEvent.HELLO_OK))
    }
}
