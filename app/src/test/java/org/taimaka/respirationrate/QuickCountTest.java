package org.taimaka.respirationrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuickCountTest {

    @Test
    public void regularTapsGiveRateFromMedianInterval() {
        // 1.5 s between breaths = 40 breaths/min
        assertEquals(40, QuickCount.firstConsistentRate(taps(0, 1500, 3000, 4500, 6000), 5));
    }

    @Test
    public void needsFiveTaps() {
        assertEquals(QuickCount.NO_RESULT,
                QuickCount.firstConsistentRate(taps(0, 1500, 3000, 4500), 4));
    }

    @Test
    public void acceptsDeviationUpToThirteenPercent() {
        // Intervals 1000, 1000, 1130, 1000: median 1000, largest deviation exactly 13%.
        assertEquals(60, QuickCount.firstConsistentRate(taps(0, 1000, 2000, 3130, 4130), 5));
    }

    @Test
    public void rejectsDeviationOverThirteenPercent() {
        // Intervals 1000, 1000, 1140, 1000: 14% off the median.
        assertEquals(QuickCount.NO_RESULT,
                QuickCount.firstConsistentRate(taps(0, 1000, 2000, 3140, 4140), 5));
    }

    @Test
    public void dropsOldestIntervalUntilWindowIsConsistent() {
        // A long first gap, then steady 2 s breaths: the window ending at tap 6 is consistent.
        long[] t = taps(0, 4000, 6000, 8000, 10000, 12000);
        assertEquals(QuickCount.NO_RESULT, QuickCount.firstConsistentRate(t, 5));
        assertEquals(30, QuickCount.firstConsistentRate(t, 6));
    }

    @Test
    public void usesMedianOfEvenWindow() {
        // Intervals 1000, 1100, 1000, 1100: median 1050 ms -> 57.1 -> 57.
        assertEquals(57, QuickCount.firstConsistentRate(taps(0, 1000, 2100, 3100, 4200), 5));
    }

    @Test
    public void ignoresTapsAfterTheTwelfth() {
        long[] t = new long[16];
        for (int i = 0; i < 12; i++) {
            t[i] = i % 2 == 0 ? i * 1000L : i * 1000L + 400; // alternating, never consistent
        }
        for (int i = 12; i < 16; i++) {
            t[i] = t[11] + (i - 11) * 1000L; // regular, but too late
        }
        assertEquals(QuickCount.NO_RESULT, QuickCount.firstConsistentRate(t, 16));
        assertTrue(QuickCount.gaveUp(t, 12));
    }

    @Test
    public void doesNotGiveUpBeforeTwelveTaps() {
        long[] t = new long[11];
        for (int i = 0; i < 11; i++) {
            t[i] = i % 2 == 0 ? i * 1000L : i * 1000L + 400;
        }
        assertFalse(QuickCount.gaveUp(t, 11));
    }

    @Test
    public void simultaneousTapsAreNotARate() {
        assertEquals(QuickCount.NO_RESULT, QuickCount.firstConsistentRate(taps(0, 0, 0, 0, 0), 5));
    }

    private static long[] taps(long... times) {
        return times;
    }
}
