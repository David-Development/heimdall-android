package de.luhmer.heimdall;

import android.content.Context;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/**
 * Created by david on 29.03.18.
 */

public class Utils {

    private static final String TAG = Utils.class.getCanonicalName();

    /**
     * Returns the unique identifier for the device
     *
     * @return unique identifier for the device
     */
    public static String getDeviceIMEI(Context context) {
        String deviceUniqueIdentifier = null;
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (null != tm) {
            deviceUniqueIdentifier = tm.getDeviceId();
        }
        if (null == deviceUniqueIdentifier || 0 == deviceUniqueIdentifier.length()) {
            deviceUniqueIdentifier = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        return deviceUniqueIdentifier;
    }
}
