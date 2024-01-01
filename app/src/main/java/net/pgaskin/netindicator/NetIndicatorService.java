package net.pgaskin.netindicator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;

public class NetIndicatorService extends Service {
    private static final String TAG = "NetIndicatorService";

    private static final String NOTIFICATION_CHANNEL_THROUGHPUT = "THROUGHPUT";
    private static final String NOTIFICATION_CHANNEL_SERVICE = "SERVICE";

    private static final int NOTIFICATION_ID_THROUGHPUT = 1;
    private static final int NOTIFICATION_ID_SERVICE = 2;

    private static final int UPDATE_INTERVAL_SECONDS = 2;

    private static final class ThroughputNotification {
        private final NotificationManager notificationManager;

        private final Canvas icon;
        private final Bitmap iconBitmap;
        private final Paint iconPaint;
        private final float iconTextX, iconTextY1, iconTextY2;
        private final StringBuilder text = new StringBuilder();
        private final Notification.Builder notification;

        public ThroughputNotification(Context context) {
            notificationManager = context.getSystemService(NotificationManager.class);

            // https://stackoverflow.com/questions/4265595/android-status-bar-expects-icons-of-size-25x25dp-while-guidelines-recommend-32x3
            // - statusbar icon is 24x24
            // - 2dp inset, so 20x20 safe area
            // - note: density is coarse (iconSize will always be a whole number)
            final int iconSize = (int) context.getResources().getDisplayMetrics().density * 24;

            iconPaint = new Paint();
            iconPaint.setColor(Color.WHITE);
            iconPaint.setAntiAlias(true);
            iconPaint.setTextSize((float) iconSize / 2);
            iconPaint.setTextAlign(Paint.Align.CENTER);
            iconPaint.setTypeface(context.getResources().getFont(R.font.asap_subset));
            // https://fonts.googleapis.com/css2?family=Asap:wdth,wght@75,450&text=1234567890%E2%86%93%E2%86%91%20GMKgmkBbs/

            final Paint.FontMetrics icPaintMetrics = iconPaint.getFontMetrics();
            final float iconTextSpaceTop = icPaintMetrics.top - icPaintMetrics.ascent; // extra space above numbers
            final float iconTextSpaceBottom = icPaintMetrics.bottom - icPaintMetrics.descent; // extra space below numbers
            final float iconTextSpace = iconTextSpaceTop + iconTextSpaceBottom;
            iconTextX = (float) iconSize/2;
            iconTextY1 = (float) iconSize/2*1 - iconTextSpaceTop + iconTextSpace/3;
            iconTextY2 = (float) iconSize/2*2 + iconTextSpaceBottom - iconTextSpace/3;

            iconBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
            icon = new Canvas(iconBitmap);

            notification = new Notification.Builder(context, NOTIFICATION_CHANNEL_THROUGHPUT)
                    .setSortKey(String.valueOf(NOTIFICATION_ID_THROUGHPUT))
                    .setOnlyAlertOnce(true)
                    .setOngoing(true);
        }

        private boolean lastVisible = false;
        private long lastBytesUp = -1;
        private long lastBytesDown = -1;

        public void update() {
            if (lastVisible) {
                notificationManager.cancel(NOTIFICATION_ID_THROUGHPUT);
            }
            lastVisible = false;
        }

        public void update(long bytesUp, long bytesDown) {
            if (bytesUp != lastBytesUp || bytesDown != lastBytesDown) {
                lastBytesUp = bytesUp;
                lastBytesDown = bytesDown;

                // to bits
                long nU = bytesUp*8;
                long nD = bytesDown*8;

                // to kilobits, rounding up
                nU = (nU + 999) / 1000;
                nD = (nD + 999) / 1000;

                // to megabits if large enough
                boolean mU = nU >= 1000;
                boolean mD = nD >= 1000;

                // to megabits, rounding normally
                nU = mU ? (nU + 500) / 1000 : nU;
                nD = mD ? (nD + 500) / 1000 : nD;

                icon.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                text.setLength(0);
                text.append(nU);
                text.append(nU < 100 ? " " : "");
                text.append(mU ? "M" : "K");
                icon.drawText(text, 0, text.length(), iconTextX, iconTextY1, iconPaint);

                text.setLength(0);
                text.append(nD);
                text.append(nD < 100 ? " " : "");
                text.append(mD ? "M" : "K");
                icon.drawText(text, 0, text.length(), iconTextX, iconTextY2, iconPaint);

                text.setLength(0);
                text.append("Network • ");
                text.append("T: ");
                text.append(nU);
                text.append(mU ? " Mb/s" : " Kb/s");
                text.append(" • ");
                text.append("R: ");
                text.append(nD);
                text.append(mD ? " Mb/s" : " Kb/s");
            }

            final Bitmap ic = iconBitmap.copy(iconBitmap.getConfig(), false); // need to copy the bitmap into an immutable one so Icon doesn't do its own copy
            notification.setContentTitle(text);
            notification.setSmallIcon(Icon.createWithBitmap(ic));
            notificationManager.notify(NOTIFICATION_ID_THROUGHPUT, notification.build());
            ic.recycle(); // we can recycle the bitmap after it's been parceled
            lastVisible = true;
        }
    }

    private static final class ThroughputTracker {
        public final String iface;
        public long txRate, rxRate; // B/s
        private long lastMillis, lastTxBytes, lastRxBytes;

        public ThroughputTracker(String iface) {
            this.iface = iface;
        }

        public void update() {
            try {
                final long millis = SystemClock.elapsedRealtime();
                final long txBytes = TrafficStats.getTxBytes(iface);
                final long rxBytes = TrafficStats.getRxBytes(iface);
                if (millis <= lastMillis || txBytes <= lastTxBytes || rxBytes <= lastRxBytes) {
                    txRate = rxRate = 0;
                } else {
                    txRate = (long)((double)(txBytes - lastTxBytes) / ((double)(millis - lastMillis)/1000));
                    rxRate = (long)((double)(rxBytes - lastRxBytes) / ((double)(millis - lastMillis)/1000));
                }
                lastMillis = millis;
                lastTxBytes = txBytes;
                lastRxBytes = rxBytes;
            } catch (Exception ex) {
                txRate = rxRate = 0;
                lastMillis = lastTxBytes = lastRxBytes = 0;
            }
        }
    }

    private static final class NetworkThroughputTracker {
        public long txRate, rxRate;

        private final static int MAX_NETWORKS = 64;
        private final Network[] networks = new Network[MAX_NETWORKS];
        private final ThroughputTracker[] throughputTrackers = new ThroughputTracker[MAX_NETWORKS];

        public void set(Network net, LinkProperties lp) {
            int idx = -1;
            for (int i = 0; i < MAX_NETWORKS; i++) {
                if (networks[i] == null) {
                    idx = i;
                } else if (networks[i].equals(net)) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) {
                Log.w(TAG, "max networks for NetworkThroughputTracker reached, ignoring network " + net);
            } else {
                final String iface = lp == null ? null : lp.getInterfaceName();
                if (iface == null) {
                    networks[idx] = null;
                    throughputTrackers[idx] = null;
                } else {
                    networks[idx] = net;
                    if (throughputTrackers[idx] == null || !throughputTrackers[idx].iface.equals(iface)) {
                        throughputTrackers[idx] = new ThroughputTracker(iface);
                    }
                }
            }
        }

        public void update() {
            txRate = 0;
            rxRate = 0;
            for (int i = 0; i < MAX_NETWORKS; i++) {
                if (throughputTrackers[i] != null) {
                    throughputTrackers[i].update();
                    txRate += throughputTrackers[i].txRate;
                    rxRate += throughputTrackers[i].rxRate;
                }
            }
        }
    }

    private Handler handler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ThroughputNotification notification;
    private NetworkThroughputTracker tracker;
    private BroadcastReceiver pauseReceiver;
    private Runnable updateRunnable;

    public static boolean startForegroundService(Context context) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            context.startForegroundService(new Intent(context.getApplicationContext(), NetIndicatorService.class));
            return true;
        } else {
            Log.d(TAG, "Notification permission not granted; not starting service.");
            return false;
        }
    }

    @Override
    public void onCreate() {
        final NotificationManager notificationManager = getSystemService(NotificationManager.class);

        final NotificationChannel nchServiceChannel = new NotificationChannel(NOTIFICATION_CHANNEL_SERVICE, "Service running", NotificationManager.IMPORTANCE_NONE);
        nchServiceChannel.setShowBadge(false);
        nchServiceChannel.enableLights(false);
        nchServiceChannel.enableVibration(false);
        nchServiceChannel.setSound(null, null);
        nchServiceChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(nchServiceChannel);

        final NotificationChannel nchServiceThroughput = new NotificationChannel(NOTIFICATION_CHANNEL_THROUGHPUT, "Throughput indicator", NotificationManager.IMPORTANCE_DEFAULT);
        nchServiceThroughput.setShowBadge(false);
        nchServiceThroughput.enableLights(false);
        nchServiceThroughput.enableVibration(false);
        nchServiceThroughput.setSound(null, null);
        nchServiceThroughput.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(nchServiceThroughput);

        final Intent nchServiceSettings = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        nchServiceSettings.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        nchServiceSettings.putExtra(Settings.EXTRA_CHANNEL_ID, NOTIFICATION_CHANNEL_SERVICE);

        startForeground(NOTIFICATION_ID_SERVICE, new Notification.Builder(this, NOTIFICATION_CHANNEL_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Net Indicator")
                .setContentText("Service is running.")
                .setSortKey(String.valueOf(NOTIFICATION_ID_SERVICE))
                .setContentIntent(PendingIntent.getActivity(this, 0, nchServiceSettings, PendingIntent.FLAG_IMMUTABLE))
                .build());

        notification = new ThroughputNotification(this);

        tracker = new NetworkThroughputTracker();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network net) {
                Log.d(TAG, "NetworkCallback.onAvailable (" + net + ")");
            }

            @Override
            public void onLinkPropertiesChanged(Network net, LinkProperties lp) {
                // note: this will always be called for every new network (i.e., after first seen, or seen after lost)
                Log.d(TAG, "NetworkCallback.onLinkPropertiesChanged (" + net + ")");
                final String iface = lp.getInterfaceName();
                Log.d(TAG, "... LinkProperties.getInterfaceName = " + (iface != null ? iface : "(null)"));
                tracker.set(net, lp);
            }

            @Override
            public void onLost(Network net) {
                Log.d(TAG, "NetworkCallback.onLost (" + net + ")");
                tracker.set(net, null);
            }
        };
        getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(), networkCallback);

        updateRunnable = new Runnable() {
            @Override
            public void run() {
                tracker.update();
                notification.update(tracker.txRate, tracker.rxRate);
                handler.postDelayed(this, UPDATE_INTERVAL_SECONDS * 1000);
            }
        };

        handler = new Handler(getMainLooper());
        final IntentFilter pauseIntentFilter = new IntentFilter();
        pauseIntentFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        pauseIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        pauseIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        pauseReceiver = new BroadcastReceiver() {
            final PowerManager powerManager = getSystemService(PowerManager.class);
            boolean lastPaused = true;

            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean isPowerSaveMode = powerManager.isPowerSaveMode();
                final boolean isScreenOn = powerManager.isInteractive();
                final boolean paused = isPowerSaveMode || !isScreenOn;
                if (paused != lastPaused) {
                    if (paused) {
                        Log.d(TAG, "pausing");
                        handler.removeCallbacks(updateRunnable);
                        if (isPowerSaveMode) {
                            Log.d(TAG, "hiding notification since power save mode was enabled");
                            notification.update();
                        }
                    } else {
                        Log.d(TAG, "resuming");
                        handler.post(updateRunnable);
                    }
                }
                lastPaused = paused;
            }
        };
        pauseReceiver.onReceive(this, null);
        registerReceiver(pauseReceiver, pauseIntentFilter);
    }

    @Override
    public void onDestroy() {
        if (pauseReceiver != null) {
            unregisterReceiver(pauseReceiver);
        }
        if (handler != null) {
            if (updateRunnable != null) {
                handler.removeCallbacks(updateRunnable);
            }
            handler = null;
        }
        if (notification != null) {
            notification.update();
            notification = null;
        }
        if (networkCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
