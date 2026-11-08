package org.taimaka.respirationrate;

import java.util.Arrays;

/**
 * Tap-interval respiratory rate estimate from Karlen et al. 2014, "Improving the accuracy and
 * efficiency of respiratory rate measurements in children using mobile devices" (PLOS ONE
 * 9(6): e99266), as used by the RRate app.
 *
 * The user taps at each breath in. Once there are {@link #INTERVALS} intervals, the most recent
 * ones are checked: if every interval is within {@link #CONSISTENCY} of their median, the rate is
 * 60 s divided by that median. Otherwise the oldest interval is dropped and the next tap is
 * awaited, up to {@link #MAX_TAPS} taps.
 */
final class QuickCount {

    /** Intervals per window (the paper's z); a result needs at least this many plus one taps. */
    static final int INTERVALS = 4;
    /** Largest allowed deviation of an interval from the window median (the paper's Th_C). */
    static final double CONSISTENCY = 0.13;
    /** Taps after which the quick count gives up. */
    static final int MAX_TAPS = 12;

    static final int NO_RESULT = -1;

    private QuickCount() {
    }

    /**
     * Returns the rate in breaths per minute from the first consistent window among the first
     * {@code min(tapCount, MAX_TAPS)} taps, or {@link #NO_RESULT} if there is none.
     *
     * @param tapTimes tap times in milliseconds, in order
     * @param tapCount number of valid entries in {@code tapTimes}
     */
    static int firstConsistentRate(long[] tapTimes, int tapCount) {
        int lastTap = Math.min(tapCount, MAX_TAPS) - 1;
        for (int end = INTERVALS; end <= lastTap; end++) {
            int rate = rateForWindowEndingAt(tapTimes, end);
            if (rate != NO_RESULT) {
                return rate;
            }
        }
        return NO_RESULT;
    }

    /** True once {@code tapCount} taps have been made without a consistent window. */
    static boolean gaveUp(long[] tapTimes, int tapCount) {
        return tapCount >= MAX_TAPS && firstConsistentRate(tapTimes, tapCount) == NO_RESULT;
    }

    private static int rateForWindowEndingAt(long[] tapTimes, int end) {
        double[] intervals = new double[INTERVALS];
        for (int i = 0; i < INTERVALS; i++) {
            int tap = end - INTERVALS + 1 + i;
            intervals[i] = tapTimes[tap] - tapTimes[tap - 1];
        }
        double median = median(intervals);
        if (median <= 0) {
            return NO_RESULT;
        }
        for (double interval : intervals) {
            if (Math.abs(interval - median) / median > CONSISTENCY) {
                return NO_RESULT;
            }
        }
        return (int) Math.round(60_000 / median);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
