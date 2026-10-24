package io.github.shaonialife.cardeck

internal class AudioCapturePermissionFlow {
    enum class Next { REQUEST_RECORD_AUDIO, LAUNCH_MEDIA_PROJECTION, ALREADY_WAITING }
    private var awaitingRecordPermission = false
    fun begin(hasRecordPermission: Boolean): Next {
        if (awaitingRecordPermission) return Next.ALREADY_WAITING
        if (hasRecordPermission) return Next.LAUNCH_MEDIA_PROJECTION
        awaitingRecordPermission = true
        return Next.REQUEST_RECORD_AUDIO
    }
    fun onPermissionResult(granted: Boolean): Boolean {
        if (!awaitingRecordPermission) return false
        awaitingRecordPermission = false
        return granted
    }
}
