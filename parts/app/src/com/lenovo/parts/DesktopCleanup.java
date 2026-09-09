package com.lenovo.parts;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.SystemProperties;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class DesktopCleanup {
    private static final String TAG = "DesktopCleanup";
    private final Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private Map<Integer, Set<Integer>> previous = new HashMap<>();
    private int checks;

    private DesktopCleanup(Context context) {
        this.context = context;
    }

    static void start(Context context) {
        DesktopCleanup cleanup = new DesktopCleanup(context.getApplicationContext());
        cleanup.worker.scheduleWithFixedDelay(cleanup::check, 60, 30, TimeUnit.SECONDS);
    }

    private void check() {
        if (!"1".equals(SystemProperties.get("sys.boot_completed"))
                || !context.getSystemService(UserManager.class).isUserUnlocked()
                || !context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                || ActivityManager.getCurrentUser() != UserHandle.myUserId()) {
            return;
        }
        try {
            Map<Integer, Set<Integer>> desks = DesktopState.orphanCandidates(
                    shell(), UserHandle.myUserId());
            Set<Integer> live = liveTasks();
            desks.entrySet().removeIf(entry -> !java.util.Collections.disjoint(
                    entry.getValue(), live));
            for (Map.Entry<Integer, Set<Integer>> entry : desks.entrySet()) {
                if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                    continue;
                }
                Map<Integer, Set<Integer>> fresh = DesktopState.orphanCandidates(
                        shell(), UserHandle.myUserId());
                if (entry.getValue().equals(fresh.get(entry.getKey()))
                        && java.util.Collections.disjoint(entry.getValue(), liveTasks())) {
                    shell("desktopmode", "removeDesk", Integer.toString(entry.getKey()));
                    if (DesktopState.orphanCandidates(shell(), UserHandle.myUserId())
                            .containsKey(entry.getKey())) {
                        throw new IllegalStateException("Desktop removal was not applied");
                    }
                    Log.i(TAG, "Removed orphan desktop " + entry.getKey()
                            + " with missing tasks " + entry.getValue());
                }
            }
            previous = desks;
        } catch (Exception e) {
            previous.clear();
            Log.w(TAG, "Desktop cleanup deferred", e);
        }
        if (++checks >= 5) {
            worker.shutdown();
        }
    }

    private Set<Integer> liveTasks() {
        ActivityTaskManager manager = ActivityTaskManager.getInstance();
        List<ActivityManager.RecentTaskInfo> recent = manager.getRecentTasks(
                1000, 0, UserHandle.myUserId());
        List<ActivityManager.RunningTaskInfo> running = manager.getTasks(1000);
        if (recent.size() >= 1000 || running.size() >= 1000) {
            throw new IllegalStateException("Task inventory may be truncated");
        }
        Set<Integer> tasks = new HashSet<>();
        for (ActivityManager.RecentTaskInfo task : recent) {
            tasks.add(task.taskId);
        }
        for (ActivityManager.RunningTaskInfo task : running) {
            tasks.add(task.taskId);
        }
        try {
            for (ActivityTaskManager.RootTaskInfo root
                    : ActivityTaskManager.getService().getAllRootTaskInfos()) {
                tasks.add(root.taskId);
                if (root.childTaskIds != null) {
                    for (int id : root.childTaskIds) tasks.add(id);
                }
            }
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("Root task inventory unavailable", e);
        }
        if (tasks.isEmpty()) {
            throw new IllegalStateException("Task inventory unavailable");
        }
        return tasks;
    }

    private String shell(String... args) throws Exception {
        File output = File.createTempFile("desktop-cleanup-", ".txt", context.getCacheDir());
        Process process = null;
        try {
            List<String> command = new ArrayList<>(List.of("/system/bin/dumpsys", "-t", "5",
                    "activity", "service", "com.android.systemui/.SystemUIService", "WMShell"));
            java.util.Collections.addAll(command, args);
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output).start();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0
                    || output.length() > 4 * 1024 * 1024) {
                throw new IllegalStateException("Desktop command failed or timed out");
            }
            String result = Files.readString(output.toPath());
            if (result.contains("Permission Denial") || result.contains("Error:")
                    || result.contains("Not supported") || result.contains("Not implemented")) {
                throw new IllegalStateException("Desktop command rejected");
            }
            return result;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            output.delete();
        }
    }
}
