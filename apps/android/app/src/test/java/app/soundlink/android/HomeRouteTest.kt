package app.soundlink.android

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRouteTest {
    @Test
    fun headphonesRestBesideTheConnectedDevice() {
        assertEquals(HomeRoute.LOCAL, HomeRoute.resolve(localConnected = true, peerConnected = false, switching = false))
        assertEquals(HomeRoute.PEER, HomeRoute.resolve(localConnected = false, peerConnected = true, switching = false))
        assertEquals(HomeRoute.BOTH, HomeRoute.resolve(localConnected = true, peerConnected = true, switching = false))
    }

    @Test
    fun unknownOrDisconnectedEvidenceStaysInTheMiddle() {
        assertEquals(HomeRoute.IDLE, HomeRoute.resolve(localConnected = false, peerConnected = false, switching = false))
    }

    @Test
    fun switchingOverridesConnectionEvidence() {
        assertEquals(HomeRoute.SWITCHING, HomeRoute.resolve(localConnected = true, peerConnected = false, switching = true))
        assertEquals(HomeRoute.SWITCHING, HomeRoute.resolve(localConnected = false, peerConnected = false, switching = true))
    }
}
