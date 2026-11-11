/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class PenForeground {
    static final class State {
        final String packageName;
        final boolean fullscreen;
        final boolean game;
        State(String name, boolean fullscreen, boolean game) {
            packageName = name;
            this.fullscreen = fullscreen;
            this.game = game;
        }
    }
    private static PenForeground sInstance;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Handler mWorker;
    private final ArrayList<Consumer<State>> mListeners = new ArrayList<>();
    private State mState = new State(null, false, false);
    private final Runnable mQuery = this::query;
    private final TaskStackListener mTasks = new TaskStackListener() {
        @Override public void onTaskStackChanged() { refresh(); }
        @Override public void onTaskFocusChanged(int taskId, boolean focused) { refresh(); }
        @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) { refresh(); }
    };

    static PenForeground get(Context context) {
        if (sInstance == null) sInstance = new PenForeground(context.getApplicationContext());
        return sInstance;
    }
    private PenForeground(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("PenForeground");
        thread.start();
        mWorker = new Handler(thread.getLooper());
        mWorker.post(() -> {
            try { ActivityTaskManager.getService().registerTaskStackListener(mTasks); }
            catch (android.os.RemoteException | RuntimeException ignored) { }
            query();
        });
    }
    void observe(Consumer<State> listener) {
        mListeners.add(listener);
        listener.accept(mState);
    }
    void unobserve(Consumer<State> listener) { mListeners.remove(listener); }
    State current() { return mState; }
    void refresh() {
        mWorker.removeCallbacks(mQuery);
        mWorker.postDelayed(mQuery, 100);
    }
    private void query() {
        State state = new State(null, false, false);
        try {
            List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getInstance().getTasks(
                    1, false, false, Display.DEFAULT_DISPLAY);
            if (!tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo task = tasks.get(0);
                if (task.topActivity != null && task.userId == ActivityManager.getCurrentUser()) {
                    String name = task.topActivity.getPackageName();
                    boolean game = false;
                    try {
                        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(name, 0);
                        game = info.category == ApplicationInfo.CATEGORY_GAME;
                    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                    state = new State(name, task.getWindowingMode()
                            == WindowConfiguration.WINDOWING_MODE_FULLSCREEN, game);
                }
            }
        } catch (RuntimeException ignored) { }
        State result = state;
        mMain.post(() -> {
            mState = result;
            for (Consumer<State> listener : new ArrayList<>(mListeners)) listener.accept(result);
        });
    }
}
