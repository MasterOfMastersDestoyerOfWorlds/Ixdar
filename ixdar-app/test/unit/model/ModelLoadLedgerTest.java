package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.scenes.model.ModelLoadLedger;

/**
 * The ledger a model switch waits on: a waiter off the render thread returns only once its switch
 * has finished, and reads a load failure's message and wall time from it.
 */
class ModelLoadLedgerTest {

    private static final long LOAD_MILLIS = 60L;

    private static final long GENEROUS_WAIT_MILLIS = 10_000L;

    private static final long SHORT_WAIT_MILLIS = 20L;

    private static final double LOAD_SECONDS_FLOOR = 0.05;

    @Test
    void waiterReturnsOnlyAfterTheLoadFinishedAndReadsItsTime() throws InterruptedException {
        ModelLoadLedger ledger = new ModelLoadLedger();
        int serial = ledger.request();
        boolean[] loadRan = new boolean[1];
        Thread renderThread = new Thread(() -> {
            long started = System.nanoTime();
            sleepQuietly(LOAD_MILLIS);
            loadRan[0] = true;
            ledger.finish(serial, null, started);
        });
        renderThread.start();

        assertTrue(ledger.awaitFinished(serial, GENEROUS_WAIT_MILLIS));
        assertTrue(loadRan[0], "the waiter returned before the load had run");
        assertEquals(serial, ledger.finishedSerial);
        assertNull(ledger.finishedFailure);
        assertTrue(ledger.finishedSeconds >= LOAD_SECONDS_FLOOR, "seconds " + ledger.finishedSeconds);
        renderThread.join();
    }

    @Test
    void failedLoadReportsItsMessageAndTheNextSwitchStillLoads() throws InterruptedException {
        ModelLoadLedger ledger = new ModelLoadLedger();
        int failing = ledger.request();
        Thread renderThread = new Thread(() -> ledger.finish(failing, new IOException("unreadable bolt.off"),
                System.nanoTime()));
        renderThread.start();
        assertTrue(ledger.awaitFinished(failing, GENEROUS_WAIT_MILLIS));
        assertEquals("java.io.IOException: unreadable bolt.off", ledger.finishedFailure);
        renderThread.join();

        int next = ledger.request();
        ledger.finish(next, null, System.nanoTime());
        assertTrue(ledger.awaitFinished(next, GENEROUS_WAIT_MILLIS));
        assertNull(ledger.finishedFailure);
    }

    @Test
    void waiterGivesUpWhenNothingFinishes() {
        ModelLoadLedger ledger = new ModelLoadLedger();
        int serial = ledger.request();
        assertFalse(ledger.awaitFinished(serial, SHORT_WAIT_MILLIS));
    }

    @Test
    void requestReplacedBeforeItWasAppliedEndsTheWaitUnderTheLaterSerial() {
        ModelLoadLedger ledger = new ModelLoadLedger();
        int replaced = ledger.request();
        int later = ledger.request();
        ledger.finish(later, null, System.nanoTime());
        assertTrue(ledger.awaitFinished(replaced, GENEROUS_WAIT_MILLIS));
        assertNotEquals(replaced, ledger.finishedSerial);
    }

    /**
     * Stand in for a load that takes a while.
     *
     * @param millis how long the load takes
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
