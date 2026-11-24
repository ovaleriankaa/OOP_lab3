package com.example.fitnesstracker;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Locale;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int PERMISSION_REQUEST_ACTIVITY_RECOGNITION = 100;
    private static final String PREFS_NAME = "fitness_tracker_prefs";
    private static final String KEY_STEPS = "total_steps";
    private static final String KEY_SECONDS = "elapsed_seconds";
    private static final String KEY_IS_TRACKING = "is_tracking";

    private static final int DAILY_GOAL_STEPS = 1000;
    private static final double STEP_LENGTH_METERS = 0.75;
    private static final double CALORIES_PER_STEP = 0.04;

    private SensorManager sensorManager;
    private Sensor activeSensor;
    private boolean usingStepCounter = false;

    private boolean isTracking = false;
    private int totalSteps = 0;
    private int elapsedSeconds = 0;
    private float previousCounterValue = -1f;

    private long lastAccelStepTime = 0;
    private static final float ACCEL_THRESHOLD = 12.5f;
    private static final long STEP_DELAY_MS = 350;

    private SharedPreferences prefs;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTracking) {
                elapsedSeconds++;
                saveData();
                updateTimeUI();
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private TextView tvSteps;
    private TextView tvGoal;
    private TextView tvDistance;
    private TextView tvCalories;
    private TextView tvTime;
    private TextView tvStatus;
    private LinearProgressIndicator progressSteps;
    private Button btnStartPause;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Timber.d("MainActivity created");

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        totalSteps = prefs.getInt(KEY_STEPS, 0);
        elapsedSeconds = prefs.getInt(KEY_SECONDS, 0);

        tvSteps = findViewById(R.id.tvSteps);
        tvGoal = findViewById(R.id.tvGoal);
        tvDistance = findViewById(R.id.tvDistance);
        tvCalories = findViewById(R.id.tvCalories);
        tvTime = findViewById(R.id.tvTime);
        tvStatus = findViewById(R.id.tvStatus);
        progressSteps = findViewById(R.id.progressSteps);
        btnStartPause = findViewById(R.id.btnStartPause);
        Button btnReset = findViewById(R.id.btnReset);
        Button btnSwitchLang = findViewById(R.id.btnSwitchLang);
        Button btnSimulateStep = findViewById(R.id.btnSimulateStep);

        progressSteps.setMax(DAILY_GOAL_STEPS);

        initSensors();

        btnStartPause.setOnClickListener(v -> toggleTracking());
        btnReset.setOnClickListener(v -> resetStats());
        btnSwitchLang.setOnClickListener(v -> switchLanguage());
        btnSimulateStep.setOnClickListener(v -> {
            totalSteps += 50;
            saveData();
            Timber.d("Simulated +50 steps (total: %d)", totalSteps);
            updateUI();
        });

        updateUI();
        updateTimeUI();

        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_IS_TRACKING, false)) {
            if (activeSensor != null && hasActivityPermission()) {
                startTracking();
            }
        }
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            Timber.e("SensorManager is unavailable");
            return;
        }

        Sensor stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepCounter != null) {
            activeSensor = stepCounter;
            usingStepCounter = true;
            Timber.i("Hardware TYPE_STEP_COUNTER sensor detected");
        } else {
            Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                activeSensor = accelerometer;
                usingStepCounter = false;
                Timber.w("TYPE_STEP_COUNTER unavailable; falling back to TYPE_ACCELEROMETER");
            } else {
                Timber.e("No compatible sensors found on this device");
                tvStatus.setText(R.string.status_no_sensor);
            }
        }
    }

    private void toggleTracking() {
        if (!isTracking) {
            if (activeSensor == null) {
                tvStatus.setText(R.string.status_no_sensor);
                Timber.w("Attempted to start tracking without available sensor");
                return;
            }
            if (hasActivityPermission()) {
                startTracking();
            } else {
                requestActivityPermission();
            }
        } else {
            pauseTracking();
        }
    }

    private boolean hasActivityPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Timber.i("Requesting ACTIVITY_RECOGNITION permission");
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                    PERMISSION_REQUEST_ACTIVITY_RECOGNITION
            );
        }
    }

    private void startTracking() {
        isTracking = true;
        previousCounterValue = -1f;
        sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_UI);
        timerHandler.postDelayed(timerRunnable, 1000);

        String sensorName = getString(usingStepCounter ? R.string.sensor_step_counter : R.string.sensor_accelerometer);
        tvStatus.setText(getString(R.string.status_active, sensorName));
        btnStartPause.setText(R.string.btn_pause);
        Timber.i("Tracking started using %s", sensorName);
    }

    private void pauseTracking() {
        isTracking = false;
        sensorManager.unregisterListener(this);
        timerHandler.removeCallbacks(timerRunnable);
        tvStatus.setText(R.string.status_paused);
        btnStartPause.setText(R.string.btn_start);
        Timber.i("Tracking paused. Steps: %d, Time: %ds", totalSteps, elapsedSeconds);
    }

    private void resetStats() {
        if (isTracking) {
            pauseTracking();
        }
        totalSteps = 0;
        elapsedSeconds = 0;
        previousCounterValue = -1f;
        saveData();
        updateUI();
        updateTimeUI();
        Timber.i("Activity statistics reset to zero");
    }

    private void switchLanguage() {
        LocaleListCompat currentLocales = AppCompatDelegate.getApplicationLocales();
        String currentLang = currentLocales.isEmpty()
                ? Locale.getDefault().getLanguage()
                : currentLocales.get(0).getLanguage();

        String targetLang = "uk".equals(currentLang) ? "en" : "uk";
        Timber.i("Switching application language from '%s' to '%s'", currentLang, targetLang);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(targetLang));
    }

    private void saveData() {
        prefs.edit()
                .putInt(KEY_STEPS, totalSteps)
                .putInt(KEY_SECONDS, elapsedSeconds)
                .apply();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_IS_TRACKING, isTracking);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isTracking) return;

        if (usingStepCounter && event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            float currentValue = event.values[0];
            if (previousCounterValue < 0) {
                previousCounterValue = currentValue;
            } else {
                int delta = Math.round(currentValue - previousCounterValue);
                if (delta > 0) {
                    totalSteps += delta;
                    previousCounterValue = currentValue;
                    saveData();
                    Timber.d("Step counter updated: +%d (total: %d)", delta, totalSteps);
                    updateUI();
                }
            }
        } else if (!usingStepCounter && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            double magnitude = Math.sqrt(x * x + y * y + z * z);

            long now = System.currentTimeMillis();
            if (magnitude > ACCEL_THRESHOLD && (now - lastAccelStepTime) > STEP_DELAY_MS) {
                lastAccelStepTime = now;
                totalSteps++;
                saveData();
                Timber.d("Accelerometer step detected (total: %d)", totalSteps);
                updateUI();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Timber.v("Sensor accuracy changed: %d", accuracy);
    }

    private void updateUI() {
        double distanceKm = (totalSteps * STEP_LENGTH_METERS) / 1000.0;
        double calories = totalSteps * CALORIES_PER_STEP;

        tvSteps.setText(String.valueOf(totalSteps));
        tvGoal.setText(getString(R.string.label_goal, totalSteps, DAILY_GOAL_STEPS));
        progressSteps.setProgressCompat(Math.min(totalSteps, DAILY_GOAL_STEPS), true);
        tvDistance.setText(getString(R.string.unit_km, distanceKm));
        tvCalories.setText(getString(R.string.unit_kcal, calories));
    }

    private void updateTimeUI() {
        int minutes = elapsedSeconds / 60;
        int seconds = elapsedSeconds % 60;
        tvTime.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_ACTIVITY_RECOGNITION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Timber.i("ACTIVITY_RECOGNITION permission granted");
                startTracking();
            } else {
                Timber.w("ACTIVITY_RECOGNITION permission denied by user");
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        Timber.d("MainActivity destroyed");
    }
}