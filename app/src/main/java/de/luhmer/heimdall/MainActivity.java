package de.luhmer.heimdall;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
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
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnTouch;

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

    private Debouncer<Integer> debouncer;
    private static final int SCREEN_OFF_DEBOUNCE = 2 * 1000; // 10 Seconds

    // https://stackoverflow.com/questions/9966506/programmatically-turn-screen-on-in-android/11708129#11708129
    final Object wakeLockSync = new Object();
    private PowerManager.WakeLock wakeLock;

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
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                                                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                                                | PowerManager.ON_AFTER_RELEASE,
                                                        "MyWakeLock");

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        debouncer = new Debouncer<>(new Callback<Integer>() {
            @Override
            public void call(Integer arg) {
                Log.d(TAG, "debounced() called with: arg = [" + arg + "]");
                turnScreenOff();
            }
        }, SCREEN_OFF_DEBOUNCE);

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


    private void connectToMqtt() {
        if(mqttAndroidClient != null) {
            mqttAndroidClient.disconnect();
            // mqttAndroidClient.close(); // Throws "IllegalArgumentException: Invalid ClientHandle"
            mqttAndroidClient = null;
        }

        tvName.setText(R.string.mqtt_connecting);

        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + System.currentTimeMillis());
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "connectComplete() called with: reconnect = [" + reconnect + "], serverURI = [" + serverURI + "]");

                if (reconnect) {
                } else {
                }

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

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(false);

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
                if(exception != null && exception.getCause() != null) {
                    tvName.setText(exception.getCause().getMessage());
                } else {
                    tvName.setText("onFailure! - " + exception.getMessage());
                }
                Log.d(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
            }
        });
    }

    private void parseImage(byte[] image) {
        byte[] decodedString = Base64.decode(image, Base64.DEFAULT);
        Glide.with(MainActivity.this).load(decodedString).into(imgView);

        debouncer.call(0); // Debounce turnOffScreen
    }

    void turnScreenOn() {
        Log.d(TAG, "turnScreenOn() called");

        synchronized (wakeLockSync) {
            if (wakeLock != null && !wakeLock.isHeld()) {  // if we have a WakeLock but we don't hold it
                Log.v(TAG, "acquire wakeLock");
                wakeLock.acquire();
            }
        }
    }

    private void turnScreenOff() {
        Log.d(TAG, "turnScreenOff() called");

        synchronized (wakeLockSync) {
            if (wakeLock != null) {
                Log.v(TAG, "release wakeLock");
                wakeLock.release();
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
}
