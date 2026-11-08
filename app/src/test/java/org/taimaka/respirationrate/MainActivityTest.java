package org.taimaka.respirationrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainActivityTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    // --- Full 60-second count (under 2 years, and forms that send no mode) ---

    @Test
    public void startsReadyWithFullMinute() {
        launch("full");
        assertEquals(activity.getString(R.string.mode_full), text(R.id.mode_banner));
        assertEquals("0", text(R.id.breath_count));
        assertEquals("60", text(R.id.seconds_left));
        assertEquals(activity.getString(R.string.tap_to_start), text(R.id.tap_button));
        assertFalse(view(R.id.restart_button).isEnabled());
        assertEquals(View.GONE, view(R.id.result_pane).getVisibility());
    }

    @Test
    public void noModeMeansFullCount() {
        launch(null);
        assertEquals(activity.getString(R.string.mode_full), text(R.id.mode_banner));
    }

    @Test
    public void firstTapCountsAndStartsTimer() {
        launch("full");
        tap(1);
        assertEquals("1", text(R.id.breath_count));
        assertEquals(activity.getString(R.string.tap_each_breath), text(R.id.tap_button));
        assertTrue(view(R.id.restart_button).isEnabled());

        advanceSeconds(10);
        assertEquals("50", text(R.id.seconds_left));
    }

    @Test
    public void fullCountShowsResultAfterSixtySeconds() {
        launch("full");
        tap(5);
        advanceSeconds(30);
        tap(7);
        advanceSeconds(29);
        assertEquals(View.VISIBLE, view(R.id.counting_pane).getVisibility());

        advanceSeconds(1);
        assertEquals(View.GONE, view(R.id.counting_pane).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.result_pane).getVisibility());
        assertEquals("12", text(R.id.result_value));
    }

    @Test
    public void fullCountKeepsCountingAfterRegularBreaths() {
        launch("full");
        tapEvery(2000, 10); // a quick-count result would be ready after 5 of these
        assertEquals(View.VISIBLE, view(R.id.counting_pane).getVisibility());
    }

    @Test
    public void fullCountReturnsCountAndQuickEstimate() {
        launch("full");
        tapEvery(2000, 30); // 30 breaths in 60 s, the first 5 regular enough for a quick count
        advanceSeconds(2);
        Intent result = save();

        assertEquals("30", result.getStringExtra("value"));
        assertEquals("30", result.getStringExtra("odk_intent_data"));
        assertEquals("30", result.getStringExtra("resp_rate"));
        assertEquals("full", result.getStringExtra("resp_rate_method"));
        assertEquals("30", result.getStringExtra("resp_rate_quick"));
        assertTrue(result.getStringExtra("resp_rate_taps").startsWith("0 2000 4000 6000 "));
    }

    @Test
    public void fullCountReturnsEmptyQuickEstimateWhenIrregular() {
        launch("full");
        tapAlternating(14);
        advanceSeconds(60);
        Intent result = save();

        assertEquals("14", result.getStringExtra("resp_rate"));
        assertEquals("", result.getStringExtra("resp_rate_quick"));
    }

    // --- Quick count (2 years and older) ---

    @Test
    public void quickModeHidesTimer() {
        launch("quick");
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));
        assertEquals(View.GONE, view(R.id.seconds_left_column).getVisibility());
        assertEquals(View.GONE, view(R.id.time_progress).getVisibility());
    }

    @Test
    public void quickCountFinishesAfterFiveRegularBreaths() {
        launch("quick");
        tapEvery(1500, 4);
        assertEquals(View.VISIBLE, view(R.id.counting_pane).getVisibility());

        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        tap(1);
        assertEquals(View.VISIBLE, view(R.id.result_pane).getVisibility());
        assertEquals(activity.getString(R.string.quick_done), text(R.id.result_title));
        assertEquals("40", text(R.id.result_value));

        Intent result = save();
        assertEquals("40", result.getStringExtra("value"));
        assertEquals("40", result.getStringExtra("resp_rate"));
        assertEquals("quick", result.getStringExtra("resp_rate_method"));
        assertEquals("", result.getStringExtra("resp_rate_quick"));
        assertEquals("0 1500 3000 4500 6000", result.getStringExtra("resp_rate_taps"));
    }

    @Test
    public void irregularQuickCountSwitchesToFullCount() {
        launch("quick");
        tapAlternating(11);
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));

        tap(1); // twelfth tap, still no regular stretch
        assertEquals(activity.getString(R.string.mode_switched), text(R.id.mode_banner));
        assertEquals(View.VISIBLE, view(R.id.seconds_left_column).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.counting_pane).getVisibility());

        advanceSeconds(60);
        assertEquals(View.VISIBLE, view(R.id.result_pane).getVisibility());
        assertEquals("12", text(R.id.result_value));
        Intent result = save();
        assertEquals("12", result.getStringExtra("resp_rate"));
        assertEquals("full_after_quick", result.getStringExtra("resp_rate_method"));
        assertEquals("", result.getStringExtra("resp_rate_quick"));
    }

    @Test
    public void quickCountWithoutResultInAMinuteBecomesFullCount() {
        launch("quick");
        tap(3);
        advanceSeconds(60);
        assertEquals(View.VISIBLE, view(R.id.result_pane).getVisibility());
        assertEquals("full_after_quick", save().getStringExtra("resp_rate_method"));
    }

    @Test
    public void startOverAfterSwitchReturnsToQuickCount() {
        launch("quick");
        tapAlternating(12);
        view(R.id.restart_button).performClick();
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));
        assertEquals(View.GONE, view(R.id.seconds_left_column).getVisibility());
    }

    @Test
    public void prefixNamesTheReturnedFields() {
        launch("quick", "resp_rate_2");
        tapEvery(1500, 5);
        Intent result = save();
        assertEquals("40", result.getStringExtra("resp_rate_2"));
        assertEquals("quick", result.getStringExtra("resp_rate_2_method"));
        assertEquals("", result.getStringExtra("resp_rate_2_quick"));
        assertTrue(result.hasExtra("resp_rate_2_taps"));
    }

    // --- Mode handling ---

    @Test
    public void modeIsCaseInsensitive() {
        launch(" Quick ");
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));
    }

    @Test
    public void unknownModeShowsErrorInsteadOfCounting() {
        launch("fast");
        assertEquals(View.VISIBLE, view(R.id.error_pane).getVisibility());
        assertEquals(View.GONE, view(R.id.counting_pane).getVisibility());
        assertEquals(View.GONE, view(R.id.restart_button).getVisibility());
        assertTrue(text(R.id.error_message).contains("\"fast\""));

        view(R.id.close_button).performClick();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
    }

    @Test
    public void launcherAsksForAgeGroupFirst() {
        controller = Robolectric.buildActivity(MainActivity.class,
                new Intent(Intent.ACTION_MAIN)).setup();
        activity = controller.get();
        assertEquals(View.VISIBLE, view(R.id.picker_pane).getVisibility());
        assertEquals(View.GONE, view(R.id.mode_banner).getVisibility());
        assertEquals(View.GONE, view(R.id.counting_pane).getVisibility());

        view(R.id.pick_quick_button).performClick();
        assertEquals(View.GONE, view(R.id.picker_pane).getVisibility());
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));

        // Start over keeps the chosen age group; there is no way back to the picker.
        tap(2);
        view(R.id.restart_button).performClick();
        assertEquals(View.GONE, view(R.id.picker_pane).getVisibility());
        assertEquals(activity.getString(R.string.mode_quick), text(R.id.mode_banner));
    }

    // --- Lifecycle ---

    @Test
    public void startOverResetsCount() {
        launch("full");
        tap(3);
        advanceSeconds(60);
        view(R.id.restart_button).performClick();

        assertEquals(View.VISIBLE, view(R.id.counting_pane).getVisibility());
        assertEquals("0", text(R.id.breath_count));
        assertEquals("60", text(R.id.seconds_left));
        assertFalse(view(R.id.restart_button).isEnabled());
    }

    @Test
    public void leavingMidCountResets() {
        launch("full");
        tap(4);
        advanceSeconds(10);
        controller.pause().stop().restart().start().resume();

        assertEquals("0", text(R.id.breath_count));
        assertEquals("60", text(R.id.seconds_left));
    }

    @Test
    public void rotationKeepsCountTimerAndMode() {
        launch("full");
        tap(6);
        advanceSeconds(20);
        controller.recreate();
        activity = controller.get();

        assertEquals("6", text(R.id.breath_count));
        assertEquals("40", text(R.id.seconds_left));
        advanceSeconds(40);
        assertEquals("6", text(R.id.result_value));
    }

    @Test
    public void rotationKeepsQuickCountTaps() {
        launch("quick");
        tapEvery(1500, 3);
        controller.recreate();
        activity = controller.get();
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        tapEvery(1500, 2);

        assertEquals(View.VISIBLE, view(R.id.result_pane).getVisibility());
        assertEquals("40", text(R.id.result_value));
    }

    @Test
    public void launcherChoiceSurvivesRotation() {
        controller = Robolectric.buildActivity(MainActivity.class,
                new Intent(Intent.ACTION_MAIN)).setup();
        activity = controller.get();
        view(R.id.pick_full_button).performClick();
        controller.recreate();
        activity = controller.get();
        assertEquals(activity.getString(R.string.mode_full), text(R.id.mode_banner));
    }

    // --- Helpers ---

    private void launch(String mode) {
        launch(mode, null);
    }

    private void launch(String mode, String prefix) {
        Intent intent = new Intent(MainActivity.ACTION_COUNT);
        if (mode != null) {
            intent.putExtra(MainActivity.EXTRA_MODE, mode);
        }
        if (prefix != null) {
            intent.putExtra(MainActivity.EXTRA_PREFIX, prefix);
        }
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        activity = controller.get();
    }

    private Intent save() {
        view(R.id.save_button).performClick();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
        return shadowOf(activity).getResultIntent();
    }

    private void tap(int times) {
        for (int i = 0; i < times; i++) {
            view(R.id.tap_button).performClick();
        }
    }

    /** Taps {@code times} times, {@code millis} apart (no wait after the last tap). */
    private void tapEvery(long millis, int times) {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
            }
            tap(1);
        }
    }

    /** Taps with gaps alternating 1.4 s and 0.6 s, which never passes the consistency check. */
    private void tapAlternating(int times) {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                ShadowLooper.idleMainLooper(i % 2 == 1 ? 1400 : 600, TimeUnit.MILLISECONDS);
            }
            tap(1);
        }
    }

    private void advanceSeconds(int seconds) {
        ShadowLooper.idleMainLooper(seconds, TimeUnit.SECONDS);
    }

    private View view(int id) {
        return activity.findViewById(id);
    }

    private String text(int id) {
        return ((TextView) view(id)).getText().toString();
    }
}
