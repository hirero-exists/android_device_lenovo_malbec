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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

final class DolbyStatusNotification {
    private static final String CHANNEL_ID = "dolby_status_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static DolbyStatusNotification sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final ContentObserver mObserver;

    private DolbyStatusNotification(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.dolby_enabled_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        mNotificationManager.createNotificationChannel(channel);

        mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("dlb_dap_state"), false, mObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("persist.sys.dolby.show_icon"), false, mObserver);
        update();
    }

    static synchronized void init(Context context) {
        if (sInstance == null) {
            sInstance = new DolbyStatusNotification(context);
        }
    }

    static synchronized void updateNotification(Context context) {
        init(context);
        sInstance.update();
    }

    private void update() {
        boolean dolbyEnabled = DolbyMode.isEnabled(mContext.getContentResolver());
        boolean showIcon = DolbyMode.isIconEnabled(mContext.getContentResolver());

        if (dolbyEnabled && showIcon) {
            Intent intent = new Intent(mContext, LenovoPartsActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_dolby_status)
                    .setContentTitle(mContext.getString(R.string.dolby_enabled_title))
                    .setContentText(mContext.getString(R.string.dolby_enabled_summary))
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build();

            mNotificationManager.notify(NOTIFICATION_ID, notification);
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
    }
}
