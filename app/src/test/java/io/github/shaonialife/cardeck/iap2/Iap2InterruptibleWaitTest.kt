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

    @Test fun interruptDuringWaitDoesNotCrashUncaughtHandler() {
        val thrown = AtomicReference<Throwable?>()
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try {
                started.countDown()
                Iap2InterruptibleWait.sleep(3000L)
            } catch (error: IOException) {
                // Same IOException boundary used by Iap2BluetoothClient.runSession().
            } catch (error: Throwable) {
                thrown.set(error)
            }
        }
        worker.start()
        assertTrue(started.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(1500L)
        assertFalse(worker.isAlive)
        assertNull(thrown.get())
    }
}
