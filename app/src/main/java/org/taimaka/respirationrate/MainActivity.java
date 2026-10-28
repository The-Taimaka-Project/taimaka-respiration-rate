package org.taimaka.respirationrate;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Counts breaths for 60 seconds and returns the count to the calling app.
 *
 * The first tap marks the first breath and starts the timer. When 60 seconds have passed a
 * beep sounds and the result is shown. "Save" returns the count to ODK Collect (extra
 * {@code value}) or CommCare (extra {@code odk_intent_data}).
 */
public class MainActivity extends Activity {

    private static final long COUNT_DURATION_MS = 60_000;
    private static final long TICK_MS = 100;

    private static final int STATE_READY = 0;
    private static final int STATE_COUNTING = 1;
    private static final int STATE_DONE = 2;

    private static final String KEY_STATE = "state";
    private static final String KEY_BREATHS = "breaths";
    private static final String KEY_START_TIME = "start_time";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;

    private int state = STATE_READY;
    private int breaths;
    /** {@link SystemClock#elapsedRealtime()} at the first breath. */
    private long startTime;

    private View countingPane;
    private View resultPane;
    private TextView breathCountView;
    private TextView secondsLeftView;
    private ProgressBar timeProgress;
    private TextView hintView;
    private Button tapButton;
    private TextView resultValueView;
    private Button restartButton;

    private Vibrator vibrator;
    private AudioTrack beepTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root));

        countingPane = findViewById(R.id.counting_pane);
        resultPane = findViewById(R.id.result_pane);
        breathCountView = findViewById(R.id.breath_count);
        secondsLeftView = findViewById(R.id.seconds_left);
        timeProgress = findViewById(R.id.time_progress);
        hintView = findViewById(R.id.hint);
        tapButton = findViewById(R.id.tap_button);
        resultValueView = findViewById(R.id.result_value);
        restartButton = findViewById(R.id.restart_button);
        Button saveButton = findViewById(R.id.save_button);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        tapButton.setOnClickListener(v -> onBreath());
        restartButton.setOnClickListener(v -> reset());
        saveButton.setOnClickListener(v -> save());

        if (savedInstanceState != null) {
            state = savedInstanceState.getInt(KEY_STATE, STATE_READY);
            breaths = savedInstanceState.getInt(KEY_BREATHS);
            startTime = savedInstanceState.getLong(KEY_START_TIME);
        }
        render();
        if (state == STATE_COUNTING) {
            tick();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_STATE, state);
        outState.putInt(KEY_BREATHS, breaths);
        outState.putLong(KEY_START_TIME, startTime);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the screen mid-count (phone call, home button, screen off) means breaths were
        // missed, so the count can't be trusted. Start again when the user comes back.
        if (state == STATE_COUNTING && !isChangingConfigurations()) {
            reset();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        releaseBeep();
        super.onDestroy();
    }

    private void onBreath() {
        if (state == STATE_DONE) {
            return;
        }
        breaths++;
        vibrate(40);
        if (state == STATE_READY) {
            state = STATE_COUNTING;
            startTime = SystemClock.elapsedRealtime();
            render();
            tick();
        } else {
            breathCountView.setText(String.valueOf(breaths));
        }
    }

    private void tick() {
        handler.removeCallbacks(tick);
        if (state != STATE_COUNTING) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - startTime;
        if (elapsed >= COUNT_DURATION_MS) {
            state = STATE_DONE;
            playBeep();
            vibrate(800);
            render();
            return;
        }
        showTime(elapsed);
        handler.postDelayed(tick, TICK_MS);
    }

    private void reset() {
        handler.removeCallbacks(tick);
        state = STATE_READY;
        breaths = 0;
        render();
    }

    private void save() {
        String value = String.valueOf(breaths);
        Intent data = new Intent();
        data.putExtra("value", value);           // ODK Collect
        data.putExtra("odk_intent_data", value); // CommCare
        setResult(RESULT_OK, data);
        finish();
    }

    private void render() {
        boolean done = state == STATE_DONE;
        countingPane.setVisibility(done ? View.GONE : View.VISIBLE);
        resultPane.setVisibility(done ? View.VISIBLE : View.GONE);
        restartButton.setEnabled(state != STATE_READY);

        breathCountView.setText(String.valueOf(breaths));
        resultValueView.setText(String.valueOf(breaths));
        boolean ready = state == STATE_READY;
        hintView.setText(ready ? R.string.hint_ready : R.string.hint_counting);
        tapButton.setText(ready ? R.string.tap_to_start : R.string.tap_each_breath);
        if (ready) {
            showTime(0);
        }
    }

    private void showTime(long elapsed) {
        long secondsLeft = (COUNT_DURATION_MS - elapsed + 999) / 1000;
        secondsLeftView.setText(String.valueOf(secondsLeft));
        timeProgress.setProgress((int) elapsed);
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long millis) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(millis);
        }
    }

    /**
     * Plays three short beeps on the alarm stream, so it is heard even when the phone is on
     * silent. The tone is generated here rather than shipped as a sound file.
     */
    private void playBeep() {
        final int sampleRate = 16000;
        final int beeps = 3;
        final int beepSamples = sampleRate * 250 / 1000;
        final int periodSamples = sampleRate * 400 / 1000;
        final int fadeSamples = sampleRate * 5 / 1000;
        final double frequency = 1500;

        short[] pcm = new short[periodSamples * beeps];
        for (int b = 0; b < beeps; b++) {
            for (int i = 0; i < beepSamples; i++) {
                // Short fade in/out on each beep avoids clicks.
                double envelope = Math.min(1.0, (double) Math.min(i, beepSamples - i) / fadeSamples);
                double sample = Math.sin(2 * Math.PI * frequency * i / sampleRate) * envelope;
                pcm[b * periodSamples + i] = (short) (sample * 0.9 * Short.MAX_VALUE);
            }
        }

        releaseBeep();
        try {
            beepTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    pcm.length * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            beepTrack.write(pcm, 0, pcm.length);
            beepTrack.play();
        } catch (RuntimeException e) {
            // No audio available: the vibration and result screen still signal the end.
            releaseBeep();
        }
    }

    private void releaseBeep() {
        if (beepTrack != null) {
            beepTrack.release();
            beepTrack = null;
        }
    }

    /**
     * Keeps content clear of the status and navigation bars. Android 15+ draws apps
     * edge-to-edge; on older versions the insets are zero and only the base padding applies.
     */
    @SuppressWarnings("deprecation")
    private void applySystemBarInsets(View root) {
        final int base = getResources().getDimensionPixelSize(R.dimen.screen_padding);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    base + insets.getSystemWindowInsetLeft(),
                    base + insets.getSystemWindowInsetTop(),
                    base + insets.getSystemWindowInsetRight(),
                    base + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
    }
}
