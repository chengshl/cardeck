package io.github.shaonialife.cardeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapturePermissionFlowTest {
    @Test fun audioGrantMustArriveBeforeProjection() {
        val flow = AudioCapturePermissionFlow()
        assertEquals(AudioCapturePermissionFlow.Next.REQUEST_RECORD_AUDIO, flow.begin(false))
        assertEquals(AudioCapturePermissionFlow.Next.ALREADY_WAITING, flow.begin(false))
        assertTrue(flow.onPermissionResult(true))
        assertFalse(flow.onPermissionResult(true))
        assertEquals(AudioCapturePermissionFlow.Next.LAUNCH_MEDIA_PROJECTION, flow.begin(true))
    }

    @Test fun rejectionCannotOpenProjection() {
        val flow = AudioCapturePermissionFlow()
        assertEquals(AudioCapturePermissionFlow.Next.REQUEST_RECORD_AUDIO, flow.begin(false))
        assertFalse(flow.onPermissionResult(false))
        assertFalse(flow.onPermissionResult(true))
        assertEquals(AudioCapturePermissionFlow.Next.REQUEST_RECORD_AUDIO, flow.begin(false))
    }

    @Test fun previouslyGrantedPermissionSkipsAudioDialog() {
        val flow = AudioCapturePermissionFlow()
        assertEquals(AudioCapturePermissionFlow.Next.LAUNCH_MEDIA_PROJECTION, flow.begin(true))
        assertFalse(flow.onPermissionResult(true))
    }

    @Test fun secondCallWhileWaitingDoesNotRequestAnotherDialog() {
        val flow = AudioCapturePermissionFlow()
        assertEquals(AudioCapturePermissionFlow.Next.REQUEST_RECORD_AUDIO, flow.begin(false))
        assertEquals(AudioCapturePermissionFlow.Next.ALREADY_WAITING, flow.begin(true))
    }
}
