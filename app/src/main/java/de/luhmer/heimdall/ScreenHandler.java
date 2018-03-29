package de.luhmer.heimdall;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

/**
 * Created by david on 29.03.18.
 */

public class ScreenHandler {

    private static final String TAG = ScreenHandler.class.getCanonicalName();
    private Debouncer<Integer> debouncerScreenOff;


    // https://stackoverflow.com/questions/9966506/programmatically-turn-screen-on-in-android/11708129#11708129
    private final Object wakeLockSync = new Object();
    private PowerManager.WakeLock mWakeLock;

    public ScreenHandler(Context context, int screenOffDebounce) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                "MyWakeLock");

        debouncerScreenOff = new Debouncer<>(new Callback<Integer>() {
            @Override
            public void call(Integer arg) {
                Log.d(TAG, "debouncerScreenOff() called with: arg = [" + arg + "]");
                turnScreenOff();
            }
        }, screenOffDebounce);
    }

    void turnScreenOn(String loggingText, Activity activity) {
        Log.d(TAG, "turnScreenOn() called from: " + loggingText);

        synchronized (wakeLockSync) {
            WindowManager.LayoutParams layout = activity.getWindow().getAttributes();
            layout.screenBrightness = 1F;
            activity.getWindow().setAttributes(layout);

            if (mWakeLock != null && !mWakeLock.isHeld()) {  // if we have a WakeLock but we don't hold it
                //Log.v(TAG, "acquire mWakeLock");
                mWakeLock.acquire();
            }
        }

        debouncerScreenOff.call(0); // Debounce turnOffScreen
    }

    void turnScreenOff() {
        Log.d(TAG, "turnScreenOff() called");

        synchronized (wakeLockSync) {
            //WindowManager.LayoutParams layout = activity.getWindow().getAttributes();
            //layout.screenBrightness = -1F;
            //activity.getWindow().setAttributes(layout);

            if (mWakeLock != null && mWakeLock.isHeld()) {
                //Log.v(TAG, "release mWakeLock");
                mWakeLock.release();
            }
        }
    }

}
