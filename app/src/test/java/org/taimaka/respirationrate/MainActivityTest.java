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

import org.junit.Before;
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

    @Before
    public void setUp() {
        Intent intent = new Intent("org.taimaka.respirationrate.COUNT");
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        activity = controller.get();
    }

    @Test
    public void startsReadyWithFullMinute() {
        assertEquals("0", text(R.id.breath_count));
        assertEquals("60", text(R.id.seconds_left));
        assertEquals(activity.getString(R.string.tap_to_start), text(R.id.tap_button));
        assertFalse(view(R.id.restart_button).isEnabled());
        assertEquals(View.GONE, view(R.id.result_pane).getVisibility());
    }

    @Test
    public void firstTapCountsAndStartsTimer() {
        tap(1);
        assertEquals("1", text(R.id.breath_count));
        assertEquals(activity.getString(R.string.tap_each_breath), text(R.id.tap_button));
        assertTrue(view(R.id.restart_button).isEnabled());

        advanceSeconds(10);
        assertEquals("50", text(R.id.seconds_left));
    }

    @Test
    public void showsResultAfterSixtySeconds() {
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
    public void saveReturnsCountToCaller() {
        tap(42);
        advanceSeconds(60);
        view(R.id.save_button).performClick();

        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
        Intent result = shadowOf(activity).getResultIntent();
        assertEquals("42", result.getStringExtra("value"));
        assertEquals("42", result.getStringExtra("odk_intent_data"));
    }

    @Test
    public void startOverResetsCount() {
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
        tap(4);
        advanceSeconds(10);
        controller.pause().stop().restart().start().resume();

        assertEquals("0", text(R.id.breath_count));
        assertEquals("60", text(R.id.seconds_left));
    }

    @Test
    public void rotationKeepsCountAndTimer() {
        tap(6);
        advanceSeconds(20);
        controller.recreate();
        activity = controller.get();

        assertEquals("6", text(R.id.breath_count));
        assertEquals("40", text(R.id.seconds_left));
        advanceSeconds(40);
        assertEquals("6", text(R.id.result_value));
    }

    private void tap(int times) {
        for (int i = 0; i < times; i++) {
            view(R.id.tap_button).performClick();
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
