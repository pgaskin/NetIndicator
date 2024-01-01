package net.pgaskin.netindicator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NetIndicatorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context.getApplicationContext(), NetIndicatorService.class));
        }
    }
}
