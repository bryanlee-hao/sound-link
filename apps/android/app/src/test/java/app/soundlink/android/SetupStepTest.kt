package app.soundlink.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepTest {
    @Test fun progressesAfterRecoverySelectionSyncAndAuthorization() {
        assertEquals(SetupStep.RECONNECT, SetupStep.next(false, false, false, "", "phone", false))
        assertEquals(SetupStep.HEADPHONES, SetupStep.next(true, false, false, "", "phone", false))
        assertEquals(SetupStep.WAITING, SetupStep.next(true, true, false, "mac", "phone", false))
        assertEquals(SetupStep.CONTROL, SetupStep.next(true, true, true, "", "phone", false))
        assertEquals(SetupStep.COMPLETE, SetupStep.next(true, true, true, "", "phone", true))
    }
    @Test fun remoteChoiceRequiresConfirmationBeforeOldAuthorizationCanComplete() {
        assertEquals(SetupStep.CONFIRM, SetupStep.next(true, true, true, "phone", "phone", true))
        assertEquals(SetupStep.WAITING, SetupStep.next(true, true, false, "", "phone", true))
        assertEquals(SetupStep.RECONNECT, SetupStep.next(false, true, true, "phone", "phone", true))
    }
}
