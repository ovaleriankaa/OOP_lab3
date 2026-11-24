package com.example.fitnesstracker;

import android.app.Application;
import timber.log.Timber;

public class FitnessTrackerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Timber.plant(new Timber.DebugTree());
        Timber.i("FitnessTrackerApp initialized. Logger is active.");
    }
}