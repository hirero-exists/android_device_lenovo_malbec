/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lenovo.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AppPickerActivity extends Activity {
    private final List<ResolveInfo> mApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PackageManager packageManager = getPackageManager();
        Intent queryIntent = new Intent(Intent.ACTION_MAIN);
        queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mApps.addAll(packageManager.queryIntentActivities(queryIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL)));
        mApps.removeIf(info -> info.activityInfo == null
                || getPackageName().equals(info.activityInfo.packageName));
        mApps.sort(Comparator.comparing(
                info -> info.loadLabel(packageManager).toString(),
                String.CASE_INSENSITIVE_ORDER));

        if (mApps.isEmpty()) {
            Toast.makeText(this, R.string.app_picker_empty, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        CharSequence[] labels = new CharSequence[mApps.size()];
        for (int index = 0; index < mApps.size(); index++) {
            labels[index] = mApps.get(index).loadLabel(packageManager);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_picker_title)
                .setItems(labels, (dialog, index) -> launchApp(mApps.get(index)))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void launchApp(ResolveInfo info) {
        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launchIntent);
        finish();
    }
}
