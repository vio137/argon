package me.ik.argon.wax

/** Explicit user-facing management states, no service calls or secret content. */
enum class WaxConnection { OFF, BLOCKED, CONNECTING, PEER_CONNECTED }
data class WaxManagementState(
    val connection: WaxConnection=WaxConnection.OFF,
    val consentAccepted: Boolean=false,
    val hasProfile: Boolean=false,
    val hasAccount: Boolean=false,
    val processEnabled: Boolean=false,
    val wantedEnabled: Boolean=false,
    val restartRequired: Boolean=false,
)
enum class WaxAction { REVIEW_NOTICE, IMPORT_PROFILE, REQUEST_ENABLE, REQUEST_DISABLE, CONNECT, BLOCK, DELETE_PROFILE, CREATE_WARP_ACCOUNT }
enum class WaxEffect {
    SHOW_NOTICE, OPEN_DOCUMENT_PICKER, PERSIST_ENABLE_THEN_CLOSE, PERSIST_DISABLE_THEN_CLOSE,
    CONNECT_EXISTING_SESSION, BLOCK_EXISTING_SESSION, CONFIRM_DELETE_THEN_BLOCK_AND_DELETE,
    SHOW_REGISTRATION_DISCLOSURE, NO_OP,
}
/** Chooses UI effects only. No transition commits intent, registers accounts or enables a route.
 * Completion callbacks must update state from actual private-storage/native results. */
object WaxManagement {
    fun effect(s:WaxManagementState,a:WaxAction):WaxEffect=when(a) {
        WaxAction.REVIEW_NOTICE -> WaxEffect.SHOW_NOTICE
        WaxAction.IMPORT_PROFILE -> WaxEffect.OPEN_DOCUMENT_PICKER
        // Registration disclosure precedes any account creation; with a stored account and no
        // profile the same disclosure honestly offers RESUME of the interrupted setup.
        WaxAction.CREATE_WARP_ACCOUNT -> if(s.hasProfile) WaxEffect.NO_OP else WaxEffect.SHOW_REGISTRATION_DISCLOSURE
        WaxAction.REQUEST_ENABLE -> when {
            !s.consentAccepted -> WaxEffect.SHOW_NOTICE
            !s.hasProfile -> WaxEffect.OPEN_DOCUMENT_PICKER
            s.processEnabled && !s.restartRequired -> WaxEffect.CONNECT_EXISTING_SESSION
            else -> WaxEffect.PERSIST_ENABLE_THEN_CLOSE
        }
        WaxAction.REQUEST_DISABLE -> if(s.processEnabled || s.wantedEnabled)
            WaxEffect.PERSIST_DISABLE_THEN_CLOSE else WaxEffect.NO_OP
        WaxAction.CONNECT -> if(s.processEnabled && s.wantedEnabled && s.consentAccepted && s.hasProfile && !s.restartRequired && s.connection!=WaxConnection.CONNECTING)
            WaxEffect.CONNECT_EXISTING_SESSION else WaxEffect.NO_OP
        // Disconnect inside enabled process means BLOCK, not fall back to ordinary direct traffic.
        WaxAction.BLOCK -> if(s.processEnabled) WaxEffect.BLOCK_EXISTING_SESSION else WaxEffect.NO_OP
        WaxAction.DELETE_PROFILE -> if(s.hasProfile) WaxEffect.CONFIRM_DELETE_THEN_BLOCK_AND_DELETE else WaxEffect.NO_OP
    }
    fun status(s:WaxManagementState):String=when {
        s.restartRequired -> "Close and reopen Argon to apply this change"
        s.connection==WaxConnection.OFF -> "Off - ordinary browsing"
        s.connection==WaxConnection.BLOCKED -> "Disconnected - browser connections blocked"
        s.connection==WaxConnection.CONNECTING -> "Connecting - browser connections blocked"
        else -> "Connected to peer - internet routing not verified"
    }
}
