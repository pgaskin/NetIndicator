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
import android.os.StrictMode;
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
        private final Context context;
        private final NotificationManager notificationManager;

        private final Canvas icon;
        private final int iconSize;
        private final Bitmap iconBitmap;
        private final Paint iconPaint;
        private final int iconTextSpaceTop, iconTextSpaceBottom, iconTextSpace; // extra space above/below numbers
        private final StringBuilder text = new StringBuilder();

        public ThroughputNotification(Context context) {
            this.context = context;
            notificationManager = context.getSystemService(NotificationManager.class);

            iconSize = (int) context.getResources().getDisplayMetrics().density * 24;

            iconPaint = new Paint();
            iconPaint.setColor(Color.WHITE);
            iconPaint.setAntiAlias(true);
            iconPaint.setTextSize((float) iconSize / 2);
            iconPaint.setTextAlign(Paint.Align.CENTER);
            iconPaint.setTypeface(context.getResources().getFont(R.font.asap_subset));
            // https://fonts.googleapis.com/css2?family=Asap:wdth,wght@75,450&text=1234567890%E2%86%93%E2%86%91%20GMKgmkBbs/

            final Paint.FontMetrics icPaintMetrics = iconPaint.getFontMetrics();
            iconTextSpaceTop = (int) (icPaintMetrics.top - icPaintMetrics.ascent);
            iconTextSpaceBottom = (int) (icPaintMetrics.bottom - icPaintMetrics.descent);
            iconTextSpace = iconTextSpaceTop + iconTextSpaceBottom;

            iconBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
            icon = new Canvas(iconBitmap);
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

                text.setLength(0);
                text.append("Network • ");
                text.append("T: ");
                text.append(nU);
                text.append(mU ? " Mb/s" : " Kb/s");
                text.append(" • ");
                text.append("R: ");
                text.append(nD);
                text.append(mD ? " Mb/s" : " Kb/s");

                icon.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                icon.drawText(nU + (nU < 100 ? " " : "") + (mU ? "M" : "K"), (float) iconSize/2, (float) iconSize/2*1 - iconTextSpaceTop + (float) iconTextSpace/3, iconPaint);
                icon.drawText(nD + (nD < 100 ? " " : "") + (mD ? "M" : "K"), (float) iconSize/2, (float) iconSize/2*2 + iconTextSpaceBottom - (float) iconTextSpace/3, iconPaint);
            }

            notificationManager.notify(NOTIFICATION_ID_THROUGHPUT, new Notification.Builder(context, NOTIFICATION_CHANNEL_THROUGHPUT)
                    .setSmallIcon(Icon.createWithBitmap(iconBitmap))
                    .setContentTitle(text)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setSortKey(String.valueOf(NOTIFICATION_ID_THROUGHPUT))
                    .build());
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

    private Handler handler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ThroughputNotification notification;
    private HashMap<Network, ThroughputTracker> networks;
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

        networks = new HashMap<>();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network net) {
                Log.d(TAG, "NetworkCallback.onAvailable (" + net.getNetworkHandle() + ")");
            }

            @Override
            public void onLinkPropertiesChanged(Network net, LinkProperties lp) {
                // note: this will always be called for every new network (i.e., after first seen, or seen after lost)
                Log.d(TAG, "NetworkCallback.onLinkPropertiesChanged (" + net.getNetworkHandle() + ")");
                final String iface = lp.getInterfaceName();
                Log.d(TAG, "... LinkProperties.getInterfaceName = " + (iface != null ? iface : "(null)"));
                if (iface != null) {
                    networks.put(net, new ThroughputTracker(iface));
                }
            }

            @Override
            public void onLost(Network net) {
                Log.d(TAG, "NetworkCallback.onLost (" + net.getNetworkHandle() + ")");
                networks.remove(net);
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
                long txRate = 0;
                long rxRate = 0;
                for (final ThroughputTracker tracker : networks.values()) {
                    tracker.update();
                    txRate += tracker.txRate;
                    rxRate += tracker.rxRate;
                }
                notification.update(txRate, rxRate);
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
        if (networks != null) {
            networks.clear();
            networks = null;
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
