package app.soundlink.android

/**
 * Where the home screen places the headphones bubble between this phone and the Mac.
 * Callers pass `peerConnected = false` for stale or unknown samples, so unknown evidence
 * never places the headphones beside a device.
 */
internal enum class HomeRoute {
    LOCAL, PEER, BOTH, SWITCHING, IDLE;

    companion object {
        fun resolve(localConnected: Boolean, peerConnected: Boolean, switching: Boolean): HomeRoute = when {
            switching -> SWITCHING
            localConnected && peerConnected -> BOTH
            localConnected -> LOCAL
            peerConnected -> PEER
            else -> IDLE
        }
    }
}
