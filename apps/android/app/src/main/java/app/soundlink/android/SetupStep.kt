package app.soundlink.android

internal enum class SetupStep {
    RECONNECT, HEADPHONES, CONFIRM, WAITING, CONTROL, COMPLETE;

    companion object {
        fun next(reconnected: Boolean, localSelected: Boolean, remoteSelected: Boolean,
                 pendingSide: String, localSide: String, controlReady: Boolean): SetupStep = when {
            !reconnected -> RECONNECT
            pendingSide == localSide -> CONFIRM
            pendingSide.isNotEmpty() -> WAITING
            !localSelected -> HEADPHONES
            !remoteSelected -> WAITING
            controlReady -> COMPLETE
            else -> CONTROL
        }
    }
}
