/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */

package com.lenovo.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
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

        StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);

        if (dolbyEnabled && showIcon) {
            if (sbm != null) {
                try {
                    sbm.setIcon("dolby", R.drawable.ic_dolby_status, 0, "Dolby Atmos");
                    sbm.setIconVisibility("dolby", true);
                } catch (Exception ignored) {
                }
            }
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
            if (sbm != null) {
                try {
                    sbm.setIconVisibility("dolby", false);
                    sbm.removeIcon("dolby");
                } catch (Exception ignored) {
                }
            }
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
    }

}
