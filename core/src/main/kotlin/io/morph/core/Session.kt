package io.morph.core

import java.security.SecureRandom

enum class Role { SENDER, RECEIVER }

/** Session state machine from §8 of the spec. */
class DropSession(
    val sessionId: ByteArray,
    val role: Role,
) {
    var state: SessionState = SessionState.IDLE
        private set

    /** Returns false when the event is not legal in the current state. */
    fun transition(event: SessionEvent): Boolean {
        val next = when (state) {
            SessionState.IDLE -> when (event) {
                SessionEvent.NFC_TAP -> SessionState.HANDSHAKE
                else -> null
            }
            SessionState.HANDSHAKE -> when (event) {
                SessionEvent.HELLO_OK -> SessionState.NEGOTIATED
                else -> null
            }
            SessionState.NEGOTIATED -> when (event) {
                SessionEvent.CAPS_OK -> SessionState.NEGOTIATED
                SessionEvent.OFFER -> SessionState.OFFERED
                else -> null
            }
            SessionState.OFFERED -> when (event) {
                SessionEvent.ACCEPT -> SessionState.TRANSFERRING
                else -> null
            }
            SessionState.TRANSFERRING -> when (event) {
                SessionEvent.LINK_LOST -> SessionState.RESUMING
                SessionEvent.DONE -> SessionState.VERIFYING
                else -> null
            }
            SessionState.RESUMING -> when (event) {
                SessionEvent.RESUME -> SessionState.TRANSFERRING
                else -> null
            }
            SessionState.VERIFYING -> when (event) {
                SessionEvent.HASH_OK -> SessionState.COMPLETE
                else -> null
            }
            SessionState.COMPLETE, SessionState.ABORTED -> null
        }

        if (next != null) {
            state = next
            return true
        }
        // ABORT and TIMEOUT are legal from every active state.
        if (event == SessionEvent.ABORT || event == SessionEvent.TIMEOUT) {
            if (state != SessionState.COMPLETE && state != SessionState.ABORTED) {
                state = SessionState.ABORTED
                return true
            }
        }
        return false
    }

    companion object {
        fun newSessionId(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}
