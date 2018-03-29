package de.luhmer.heimdall;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getCanonicalName();

    private MqttAndroidClient mqttAndroidClient;
    private String mqttServerUri;
    private String mqttClientId = "heimdall-android-";
    private String mqttSubscriptionTopic = "recognitions/#";
    private SharedPreferences mPrefs;
    static final String SETTING_MQTT_SERVER_IP_STRING = "MQTT_URL";
    static final String SETTING_LIVE_VIEW_BOOLEAN = "LIVE_VIEW";

    @BindView(R.id.tvName)      TextView tvName;
    @BindView(R.id.btnSettings) Button btnSettings;
    @BindView(R.id.imgView)     ImageView imgView;

    private Debouncer<Integer> debouncerScreenOff;
    private Debouncer<Integer> debouncerReconnect;
    private static final int SCREEN_OFF_DEBOUNCE = 10 * 1000; // X Seconds

    // https://stackoverflow.com/questions/9966506/programmatically-turn-screen-on-in-android/11708129#11708129
    final Object wakeLockSync = new Object();
    private PowerManager.WakeLock mWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Remove title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if(getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        //Remove notification bar
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        final KeyguardManager.KeyguardLock kl = km.newKeyguardLock("MyKeyguardLock");
        kl.disableKeyguard();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                                                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                                                | PowerManager.ON_AFTER_RELEASE,
                                                        "MyWakeLock");

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        debouncerScreenOff = new Debouncer<>(new Callback<Integer>() {
            @Override
            public void call(Integer arg) {
                Log.d(TAG, "debouncerScreenOff() called with: arg = [" + arg + "]");
                turnScreenOff();
            }
        }, SCREEN_OFF_DEBOUNCE);

        debouncerReconnect = new Debouncer<>(new Callback<Integer>() {
            @Override
            public void call(Integer arg) {
                Log.d(TAG, "debouncerReconnect() called with: arg = [" + arg + "]");
                connectToMqtt();
            }
        }, 5 * 1000); // Allow only one reconnect in 5 seconds

        mqttClientId += System.currentTimeMillis();

        // Load MQTT Server IP from preferences
        mPrefs =  PreferenceManager.getDefaultSharedPreferences(this);
        setMqttServerIP(mPrefs.getString(SETTING_MQTT_SERVER_IP_STRING, ""));


        // If no ip has been configured yet
        if(mPrefs.getString(SETTING_MQTT_SERVER_IP_STRING, "").isEmpty()) {
            showEnterMqttServerIpDialog();
        } else {
            connectToMqtt();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        turnScreenOn();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.e(TAG, "onPause() called");

        turnScreenOff();
        disconnectFromMqtt();
    }

    private void disconnectFromMqtt() {
        if(mqttAndroidClient != null) {
            Log.d(TAG, "Disconnecting from existing MQTT-Client");
            //mqttAndroidClient.unsubscribe(new String[] {"camera", "liveview"});
            //mqttAndroidClient.disconnect();
            try {
                mqttAndroidClient.disconnect(0, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        Log.d(TAG, "onSuccess() called with: asyncActionToken = [" + asyncActionToken + "]");
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Log.e(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
                    }
                }); // 1 second timeout
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // mqttAndroidClient.close(); // Throws "IllegalArgumentException: Invalid ClientHandle"
            mqttAndroidClient = null;
            Log.d(TAG, "Disconnect done");
        }
    }

    private void connectToMqtt() {
        disconnectFromMqtt();

        tvName.setText(R.string.mqtt_connecting);

        //mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + System.currentTimeMillis());
        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + getDeviceIMEI());
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "connectComplete() called with: reconnect = [" + reconnect + "], serverURI = [" + serverURI + "]");
                tvName.setText(R.string.mqtt_connected);
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.d(TAG, "connectionLost() called with: cause = [" + cause + "]");
                tvName.setText(R.string.mqtt_connection_lost);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                //Log.d(TAG, "messageArrived() called with: topic = [" + topic + "], message = [" + message + "]");
                Log.d(TAG, "messageArrived() called with: topic = [" + topic + "]");

                turnScreenOn();

                switch(topic) {
                    case "recognitions/person":
                        JSONObject jObject = new JSONObject(message.toString());
                        List<String> names = new ArrayList<>();
                        JSONArray predictions = jObject.getJSONArray("predictions");
                        for (int i = 0; i < predictions.length(); i++) {
                            names.add(predictions.getJSONObject(i).getString("highest"));
                        }
                        String namesString = android.text.TextUtils.join(", ", names);
                        tvName.setText(namesString);
                        break;
                    case "recognitions/image":
                        parseImage(message.getPayload());
                        break;
                    case "camera":
                        parseImage(message.getPayload());
                        break;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d(TAG, "deliveryComplete() called with: token = [" + token + "]");
            }
        });

        final MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(true);
        //mqttConnectOptions.setCleanSession(false);
        int keepAliveInterval = 5; // Seconds
        int connectTimeout   = 30; // Seconds
        mqttConnectOptions.setKeepAliveInterval(keepAliveInterval);
        mqttConnectOptions.setConnectionTimeout(connectTimeout);

        //addToHistory("Connecting to " + serverUri);
        mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                Log.d(TAG, "onSuccess() called with: asyncActionToken = [" + asyncActionToken + "]");
                DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                disconnectedBufferOptions.setBufferEnabled(true);
                disconnectedBufferOptions.setBufferSize(100);
                disconnectedBufferOptions.setPersistBuffer(false);
                disconnectedBufferOptions.setDeleteOldestMessages(false);
                mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                subscribeToTopic();
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                // Workaround for bug #209 (https://github.com/eclipse/paho.mqtt.android/issues/209)
                if(exception instanceof MqttException) {
                    MqttException ex = (MqttException) exception;
                    if(ex.getReasonCode() == MqttException.REASON_CODE_CLIENT_CONNECTED || ex.getReasonCode() == MqttException.REASON_CODE_CONNECT_IN_PROGRESS) {
                        Log.e(TAG, "Bug #209 detected - Debouncing reconnect!");
                        debouncerReconnect.call(0);
                    }
                } else {
                    turnScreenOn();

                    if(exception != null && exception.getCause() != null) {
                        tvName.setText(exception.getCause().getMessage());
                    } else {
                        tvName.setText("Fehler: " + exception.getMessage());
                    }
                    Log.e(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
                }
            }
        });
    }

    private void parseImage(byte[] image) {
        byte[] decodedString = Base64.decode(image, Base64.DEFAULT);
        Glide.with(MainActivity.this).load(decodedString).into(imgView);
    }

    void turnScreenOn() {
        Log.d(TAG, "turnScreenOn() called");

        synchronized (wakeLockSync) {
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.screenBrightness = 1F;
            getWindow().setAttributes(layout);

            if (mWakeLock != null && !mWakeLock.isHeld()) {  // if we have a WakeLock but we don't hold it
                //Log.v(TAG, "acquire mWakeLock");
                mWakeLock.acquire();
            }
        }

        debouncerScreenOff.call(0); // Debounce turnOffScreen
    }

    private void turnScreenOff() {
        Log.d(TAG, "turnScreenOff() called");

        synchronized (wakeLockSync) {
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.screenBrightness = -1F;
            getWindow().setAttributes(layout);

            if (mWakeLock != null && mWakeLock.isHeld()) {
                //Log.v(TAG, "release mWakeLock");
                mWakeLock.release();
            }
        }
    }

    public void subscribeToTopic() {
        boolean showLiveView = mPrefs.getBoolean(SETTING_LIVE_VIEW_BOOLEAN, false);
        String topic = mqttSubscriptionTopic;
        if(showLiveView) {
            topic = "camera";
        }

        mqttAndroidClient.subscribe(topic, 0, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                Log.d(TAG, "onSuccess() called with: asyncActionToken = [" + asyncActionToken + "]");
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                Log.d(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
            }
        });
    }


    @OnClick(R.id.btnSettings)
    void showEnterMqttServerIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.dialog_settings_title);

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        final EditText eTMqttIP = dialogView.findViewById(R.id.etMqttIP);
        final Switch swLiveView = dialogView.findViewById(R.id.swLiveView);
        eTMqttIP.setText(mPrefs.getString(SETTING_MQTT_SERVER_IP_STRING, ""));
        swLiveView.setChecked(mPrefs.getBoolean(SETTING_LIVE_VIEW_BOOLEAN, false));

        // Set up the buttons
        builder.setPositiveButton(R.string.dialog_settings_save, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String serverIP = eTMqttIP.getText().toString();
                boolean enableLiveView = swLiveView.isChecked();
                // TODO make some kind of regex check for valid IP
                setMqttServerIP(serverIP);
                mPrefs.edit()
                        .putString(SETTING_MQTT_SERVER_IP_STRING, serverIP)
                        .putBoolean(SETTING_LIVE_VIEW_BOOLEAN, enableLiveView)
                        .apply();

                connectToMqtt();
            }
        });
        builder.setNegativeButton(R.string.dialog_settings_abort, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog dialog = builder.create();
        dialog.getWindow().setLayout(300, 300); //Controlling width and height.
        dialog.show();
    }

    private void setMqttServerIP(String serverIP) {
        this.mqttServerUri = "tcp://" + serverIP + ":1883";
    }



    /**
     * Returns the unique identifier for the device
     *
     * @return unique identifier for the device
     */
    public String getDeviceIMEI() {
        String deviceUniqueIdentifier = null;
        TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        if (null != tm) {
            deviceUniqueIdentifier = tm.getDeviceId();
        }
        if (null == deviceUniqueIdentifier || 0 == deviceUniqueIdentifier.length()) {
            deviceUniqueIdentifier = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        return deviceUniqueIdentifier;
    }
}
