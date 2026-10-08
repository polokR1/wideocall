package pl.wideocall.app.call

import org.junit.Assert.*
import org.junit.Test

class CallScreenAwakePolicyTest {
    @Test fun everyVideoCallPhaseKeepsTheVisibleScreenAwake() {
        listOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)
            .forEach { assertTrue(it.name, CallScreenAwakePolicy.enabled(it)) }
    }
    @Test fun idleChatFilesAndSignalingAloneDoNotKeepTheScreenAwake() {
        CallPhase.entries.filter { it !in setOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL) }
            .forEach { assertFalse(it.name, CallScreenAwakePolicy.enabled(it)) }
    }
}
