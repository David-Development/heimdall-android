package de.luhmer.heimdall;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;


class WakeLockHandler {

    private PowerManager.WakeLock mWakeLock;

    void onCreate(Activity activity) {
        PowerManager powerManager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Heimdall-BackgroundWakelock");
        if (mWakeLock != null) {
            mWakeLock.acquire();
        }
    }


    void onDestroy() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }
}
