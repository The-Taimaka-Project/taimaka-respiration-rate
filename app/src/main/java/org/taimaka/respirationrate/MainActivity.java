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

import java.util.Arrays;
import java.util.Locale;

/**
 * Counts a child's breaths and returns the respiratory rate to the calling form.
 *
 * <p>There are two modes, chosen by the form from the child's age (extra {@code mode}):
 * <ul>
 * <li>{@code full}: breaths are counted for 60 seconds from the first tap. The quick-count
 * estimate for the same taps is also returned, as validation data.</li>
 * <li>{@code quick}: the tap-interval method in {@link QuickCount}. If breathing is too irregular
 * for it, the count carries on as a full 60-second count from the same first tap.</li>
 * </ul>
 * The mode can't be changed on screen. Opened from the launcher, the app asks for the child's age
 * group once; closing the app is the only way to choose again.
 *
 * <p>On "Save result" the rate goes back as extras {@code value} (ODK single question) and
 * {@code odk_intent_data} (CommCare). For an ODK intent group it also goes back under the field
 * names {@code <prefix>}, {@code <prefix>_method}, {@code <prefix>_quick} and
 * {@code <prefix>_taps}, where {@code prefix} is an extra defaulting to {@code resp_rate}.
 */
public class MainActivity extends Activity {

    static final String ACTION_COUNT = "org.taimaka.respirationrate.COUNT";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PREFIX = "prefix";
    static final String DEFAULT_PREFIX = "resp_rate";

    static final String METHOD_FULL = "full";
    static final String METHOD_QUICK = "quick";
    static final String METHOD_FULL_AFTER_QUICK = "full_after_quick";

    private static final long COUNT_DURATION_MS = 60_000;
    private static final long TICK_MS = 100;
    /** Enough for 60 seconds at any plausible rate; later taps still count but aren't timed. */
    private static final int MAX_RECORDED_TAPS = 300;

    private static final int MODE_UNSET = 0; // opened from the launcher, age not chosen yet
    private static final int MODE_FULL = 1;
    private static final int MODE_QUICK = 2;
    private static final int MODE_INVALID = 3; // the form sent a mode we don't know

    private static final int STATE_READY = 0;
    private static final int STATE_COUNTING = 1;
    private static final int STATE_DONE = 2;

    private static final String KEY_MODE = "mode";
    private static final String KEY_STATE = "state";
    private static final String KEY_BREATHS = "breaths";
    private static final String KEY_START_TIME = "start_time";
    private static final String KEY_TAP_TIMES = "tap_times";
    private static final String KEY_SWITCHED = "switched";
    private static final String KEY_QUICK_RATE = "quick_rate";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;

    private int mode;
    private String invalidMode;
    private String prefix;

    private int state = STATE_READY;
    private int breaths;
    /** {@link SystemClock#elapsedRealtime()} at the first breath. */
    private long startTime;
    /** Milliseconds from the first tap to each tap, for the first {@link #MAX_RECORDED_TAPS}. */
    private long[] tapTimes = new long[MAX_RECORDED_TAPS];
    /** Quick mode only: breathing was too irregular, so this became a full 60-second count. */
    private boolean switched;
    /** Quick-count rate: the result in quick mode, validation data in full mode. */
    private int quickRate = QuickCount.NO_RESULT;

    private TextView modeBanner;
    private View pickerPane;
    private View errorPane;
    private View countingPane;
    private View resultPane;
    private TextView breathCountView;
    private View secondsLeftColumn;
    private TextView secondsLeftView;
    private ProgressBar timeProgress;
    private TextView hintView;
    private Button tapButton;
    private TextView resultTitleView;
    private TextView resultValueView;
    private TextView resultMethodView;
    private Button restartButton;

    private Vibrator vibrator;
    private AudioTrack beepTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root));

        modeBanner = findViewById(R.id.mode_banner);
        pickerPane = findViewById(R.id.picker_pane);
        errorPane = findViewById(R.id.error_pane);
        countingPane = findViewById(R.id.counting_pane);
        resultPane = findViewById(R.id.result_pane);
        breathCountView = findViewById(R.id.breath_count);
        secondsLeftColumn = findViewById(R.id.seconds_left_column);
        secondsLeftView = findViewById(R.id.seconds_left);
        timeProgress = findViewById(R.id.time_progress);
        hintView = findViewById(R.id.hint);
        tapButton = findViewById(R.id.tap_button);
        resultTitleView = findViewById(R.id.result_title);
        resultValueView = findViewById(R.id.result_value);
        resultMethodView = findViewById(R.id.result_method);
        restartButton = findViewById(R.id.restart_button);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        tapButton.setOnClickListener(v -> onBreath());
        restartButton.setOnClickListener(v -> reset());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.pick_full_button).setOnClickListener(v -> chooseMode(MODE_FULL));
        findViewById(R.id.pick_quick_button).setOnClickListener(v -> chooseMode(MODE_QUICK));
        findViewById(R.id.close_button).setOnClickListener(v -> finish());

        readLaunchExtras();
        if (savedInstanceState != null) {
            mode = savedInstanceState.getInt(KEY_MODE, mode);
            state = savedInstanceState.getInt(KEY_STATE, STATE_READY);
            breaths = savedInstanceState.getInt(KEY_BREATHS);
            startTime = savedInstanceState.getLong(KEY_START_TIME);
            long[] saved = savedInstanceState.getLongArray(KEY_TAP_TIMES);
            if (saved != null) {
                System.arraycopy(saved, 0, tapTimes, 0, saved.length);
            }
            switched = savedInstanceState.getBoolean(KEY_SWITCHED);
            quickRate = savedInstanceState.getInt(KEY_QUICK_RATE, QuickCount.NO_RESULT);
        }
        render();
        if (state == STATE_COUNTING) {
            tick();
        }
    }

    @SuppressWarnings("deprecation")
    private void readLaunchExtras() {
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        Object prefixExtra = extras == null ? null : extras.get(EXTRA_PREFIX);
        prefix = prefixExtra == null || prefixExtra.toString().trim().isEmpty()
                ? DEFAULT_PREFIX : prefixExtra.toString().trim();

        if (!ACTION_COUNT.equals(intent.getAction())) {
            mode = MODE_UNSET;
            return;
        }
        // ODK sends extras as strings; no mode at all means a form written before modes existed.
        Object modeExtra = extras == null ? null : extras.get(EXTRA_MODE);
        String requested = modeExtra == null ? "" : modeExtra.toString().trim();
        switch (requested.toLowerCase(Locale.ROOT)) {
            case "":
            case "full":
                mode = MODE_FULL;
                break;
            case "quick":
                mode = MODE_QUICK;
                break;
            default:
                mode = MODE_INVALID;
                invalidMode = requested;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_MODE, mode);
        outState.putInt(KEY_STATE, state);
        outState.putInt(KEY_BREATHS, breaths);
        outState.putLong(KEY_START_TIME, startTime);
        outState.putLongArray(KEY_TAP_TIMES, Arrays.copyOf(tapTimes, recordedTaps()));
        outState.putBoolean(KEY_SWITCHED, switched);
        outState.putInt(KEY_QUICK_RATE, quickRate);
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

    private void chooseMode(int chosen) {
        mode = chosen;
        render();
    }

    private void onBreath() {
        if (state == STATE_DONE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (state == STATE_COUNTING && now - startTime >= COUNT_DURATION_MS) {
            tick(); // the minute is already over; this tap is too late to count
            return;
        }
        if (state == STATE_READY) {
            state = STATE_COUNTING;
            startTime = now;
        }
        if (breaths < MAX_RECORDED_TAPS) {
            tapTimes[breaths] = now - startTime;
        }
        breaths++;
        vibrate(40);

        if (mode == MODE_QUICK && !switched) {
            quickRate = QuickCount.firstConsistentRate(tapTimes, recordedTaps());
            if (quickRate != QuickCount.NO_RESULT) {
                finishCount();
                return;
            }
            if (QuickCount.gaveUp(tapTimes, recordedTaps())) {
                switched = true;
            }
        }
        render();
        tick();
    }

    private void tick() {
        handler.removeCallbacks(tick);
        if (state != STATE_COUNTING) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - startTime;
        if (elapsed >= COUNT_DURATION_MS) {
            // In quick mode, reaching a minute without a result also makes it a full count.
            switched = mode == MODE_QUICK;
            finishCount();
            return;
        }
        showTime(elapsed);
        handler.postDelayed(tick, TICK_MS);
    }

    private void finishCount() {
        handler.removeCallbacks(tick);
        state = STATE_DONE;
        if (mode == MODE_FULL) {
            quickRate = QuickCount.firstConsistentRate(tapTimes, recordedTaps());
        }
        if (isQuickResult()) {
            playBeep(2);
            vibrate(400);
        } else {
            playBeep(3);
            vibrate(800);
        }
        render();
    }

    private void reset() {
        handler.removeCallbacks(tick);
        state = STATE_READY;
        breaths = 0;
        switched = false;
        quickRate = QuickCount.NO_RESULT;
        render();
    }

    private void save() {
        String rate = String.valueOf(result());
        Intent data = new Intent();
        data.putExtra("value", rate);           // ODK Collect, single question
        data.putExtra("odk_intent_data", rate); // CommCare
        // ODK Collect intent group: extras fill the group's fields with the same names.
        data.putExtra(prefix, rate);
        data.putExtra(prefix + "_method", method());
        // Always sent, even empty, so a repeat launch clears an earlier value.
        data.putExtra(prefix + "_quick", mode == MODE_FULL && quickRate != QuickCount.NO_RESULT
                ? String.valueOf(quickRate) : "");
        data.putExtra(prefix + "_taps", tapTimesText());
        setResult(RESULT_OK, data);
        finish();
    }

    private boolean isQuickResult() {
        return mode == MODE_QUICK && !switched;
    }

    private int result() {
        return isQuickResult() ? quickRate : breaths;
    }

    private String method() {
        if (mode == MODE_FULL) {
            return METHOD_FULL;
        }
        return switched ? METHOD_FULL_AFTER_QUICK : METHOD_QUICK;
    }

    private int recordedTaps() {
        return Math.min(breaths, MAX_RECORDED_TAPS);
    }

    /** Tap times in ms from the first tap, space-separated so XPath's selected-at() can read them. */
    private String tapTimesText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < recordedTaps(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(tapTimes[i]);
        }
        return text.toString();
    }

    private void render() {
        boolean modeKnown = mode == MODE_FULL || mode == MODE_QUICK;
        boolean done = state == STATE_DONE;
        pickerPane.setVisibility(mode == MODE_UNSET ? View.VISIBLE : View.GONE);
        errorPane.setVisibility(mode == MODE_INVALID ? View.VISIBLE : View.GONE);
        countingPane.setVisibility(modeKnown && !done ? View.VISIBLE : View.GONE);
        resultPane.setVisibility(modeKnown && done ? View.VISIBLE : View.GONE);
        restartButton.setVisibility(modeKnown ? View.VISIBLE : View.GONE);
        modeBanner.setVisibility(modeKnown ? View.VISIBLE : View.GONE);
        if (mode == MODE_INVALID) {
            ((TextView) findViewById(R.id.error_message))
                    .setText(getString(R.string.error_unknown_mode, invalidMode));
        }
        if (!modeKnown) {
            return;
        }

        boolean timed = mode == MODE_FULL || switched;
        modeBanner.setText(mode == MODE_FULL ? R.string.mode_full
                : switched ? R.string.mode_switched : R.string.mode_quick);
        modeBanner.setBackgroundColor(color(timed ? R.color.tap : R.color.quick));
        tapButton.setBackgroundResource(timed ? R.drawable.btn_tap : R.drawable.btn_tap_quick);
        secondsLeftColumn.setVisibility(timed ? View.VISIBLE : View.GONE);
        timeProgress.setVisibility(timed ? View.VISIBLE : View.GONE);
        restartButton.setEnabled(state != STATE_READY);

        boolean ready = state == STATE_READY;
        breathCountView.setText(String.valueOf(breaths));
        tapButton.setText(ready ? R.string.tap_to_start : R.string.tap_each_breath);
        if (ready) {
            hintView.setText(mode == MODE_FULL ? R.string.hint_ready_full : R.string.hint_ready_quick);
            showTime(0);
        } else {
            hintView.setText(mode == MODE_FULL ? R.string.hint_counting_full
                    : switched ? R.string.hint_counting_switched : R.string.hint_counting_quick);
        }

        if (!done) {
            return;
        }
        resultTitleView.setText(isQuickResult() ? R.string.quick_done : R.string.times_up);
        resultValueView.setText(String.valueOf(result()));
        if (isQuickResult()) {
            long seconds = Math.round(tapTimes[recordedTaps() - 1] / 1000.0);
            resultMethodView.setText(getString(R.string.method_quick, breaths, seconds));
        } else {
            resultMethodView.setText(switched ? R.string.method_switched : R.string.method_full);
        }
    }

    private void showTime(long elapsed) {
        long secondsLeft = (COUNT_DURATION_MS - elapsed + 999) / 1000;
        secondsLeftView.setText(String.valueOf(secondsLeft));
        timeProgress.setProgress((int) elapsed);
    }

    @SuppressWarnings("deprecation")
    private int color(int id) {
        return getResources().getColor(id);
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
     * Plays short beeps on the alarm stream, so they are heard even when the phone is on
     * silent. The tone is generated here rather than shipped as a sound file.
     */
    private void playBeep(int beeps) {
        final int sampleRate = 16000;
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
