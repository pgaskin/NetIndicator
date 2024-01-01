package net.pgaskin.netindicator;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;

public class NetIndicatorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!NetIndicatorService.startForegroundService(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (NetIndicatorService.startForegroundService(this)) {
            finish();
        }
    }
}
