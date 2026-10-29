package io.github.shaonialife.cardeck.iap2

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class Iap2InterruptibleWaitTest {
    @Test fun normalWaitReturnsWithoutCancellation() {
        Iap2InterruptibleWait.sleep(1L)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun interruptionIsConvertedToIoAndInterruptFlagIsRestored() {
        val thrown = AtomicReference<Throwable?>()
        val restored = AtomicBoolean(false)
        val worker = Thread {
            Thread.currentThread().interrupt()
            try {
                Iap2InterruptibleWait.sleep(1500L)
                thrown.set(AssertionError("cancelled sleep unexpectedly returned"))
            } catch (error: Throwable) {
                thrown.set(error)
                restored.set(Thread.currentThread().isInterrupted)
            }
        }
        worker.start()
        worker.join(2500L)
        assertFalse("worker should finish on interrupt", worker.isAlive)
        assertTrue(thrown.get() is IOException)
        assertTrue(thrown.get()?.cause is InterruptedException)
        assertTrue("interrupt flag must remain set for stop semantics", restored.get())
    }

    @Test fun interruptionDuringActiveWaitIsIoWithCauseAndRestoredFlag() {
        val thrown = AtomicReference<Throwable?>()
        val restored = AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try {
                started.countDown()
                Iap2InterruptibleWait.sleep(3000L)
                thrown.set(AssertionError("interrupted wait unexpectedly returned"))
            } catch (error: IOException) {
                thrown.set(error)
                restored.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                thrown.set(error)
            }
        }
        worker.start()
        assertTrue(started.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        // Ensure this is a mid-sleep cancellation, not just the pre-interrupted case.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1L)
        }
        assertEquals("worker must enter the cancellable wait", Thread.State.TIMED_WAITING, worker.state)
        worker.interrupt()
        worker.join(1500L)
        assertFalse("cancelled worker must exit promptly", worker.isAlive)
        assertTrue("interruption must reach the IOException boundary", thrown.get() is IOException)
        assertTrue("original interruption must be preserved", thrown.get()?.cause is InterruptedException)
        assertTrue("worker interrupt flag must be restored", restored.get())
    }
}
