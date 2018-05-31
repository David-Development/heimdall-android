package de.luhmer.heimdall;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
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
import android.widget.Toast;

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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getCanonicalName();

    private static final String SETTING_MQTT_SERVER_IP_STRING = "MQTT_URL";
    private static final String SETTING_LIVE_VIEW_BOOLEAN = "LIVE_VIEW";
    private static final int REQUEST_PERMISSION_PHONE_STATE = 1;
    private static final int SCREEN_OFF_DEBOUNCE = 10 * 1000; // X Seconds

    private MqttAndroidClient mqttAndroidClient;
    private String mqttServerUri;
    private String mqttClientId = "heimdall-android-";
    private String mqttSubscriptionTopic = "recognitions/#";
    private SharedPreferences mPrefs;


    @BindView(R.id.tvName) TextView tvName;
    @BindView(R.id.tvStatus) TextView tvStatus;
    @BindView(R.id.btnSettings) Button btnSettings;
    @BindView(R.id.imgView) ImageView imgView;

    private int colorConnected    = Color.parseColor("#65a9a9a9");
    private int colorNotConnected = Color.parseColor("#65ff0000");

    private WakeLockHandler wakeLockHandler;
    private Debouncer<Integer> debouncerReconnect;

    private ScreenHandler screenHandler;
    private String lastDetectedName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Remove title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        //Remove notification bar
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        final KeyguardManager.KeyguardLock kl = km.newKeyguardLock("MyKeyguardLock");
        kl.disableKeyguard();

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        screenHandler = new ScreenHandler(this, SCREEN_OFF_DEBOUNCE);
        wakeLockHandler = new WakeLockHandler();
        wakeLockHandler.onCreate(this);

        tvName.setBackgroundColor(colorNotConnected);

        debouncerReconnect = new Debouncer<>(new Callback<Integer>() {
            @Override
            public void call(Integer arg) {
                Log.d(TAG, "debouncerReconnect() called with: arg = [" + arg + "]");
                connectToMqtt();
            }
        }, 10 * 1000); // Allow only one reconnect in 5 seconds


        mqttClientId += System.currentTimeMillis();

        // Load MQTT Server IP from preferences
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        setMqttServerIP(mPrefs.getString(SETTING_MQTT_SERVER_IP_STRING, ""));


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.READ_PHONE_STATE }, REQUEST_PERMISSION_PHONE_STATE);
        } else {
            connectToMqtt();
        }
    }



    @Override
    protected void onResume() {
        super.onResume();

        screenHandler.turnScreenOn("onResume", this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.e(TAG, "onPause() called");

        screenHandler.turnScreenOff();
        wakeLockHandler.onDestroy();
        disconnectFromMqtt();
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_PHONE_STATE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToMqtt();
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.

                } else {
                    Toast.makeText(this, "You can't use the app if you don't give me the permission..", Toast.LENGTH_LONG).show();
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request.
        }
    }

    private void disconnectFromMqtt() {
        if (mqttAndroidClient != null) {
            Log.e(TAG, "Disconnecting from existing MQTT-Client");
            //mqttAndroidClient.unsubscribe(new String[] {"camera", "liveview"});
            //mqttAndroidClient.disconnect();
            try {
                mqttAndroidClient.disconnect(0, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        Log.d(TAG, "onSuccess() for disconnect called with: asyncActionToken = [" + asyncActionToken + "]");
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Log.e(TAG, "onFailure() for disconnect called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
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
        // If no ip has been configured yet
        if (mPrefs.getString(SETTING_MQTT_SERVER_IP_STRING, "").isEmpty()) {
            showEnterMqttServerIpDialog();
            return;
        }

        if(mqttAndroidClient != null && mqttAndroidClient.isConnected()) {
            Log.d(TAG, "Aborting reconnecting since client is still connected!");
            return;
        }

        disconnectFromMqtt();

        //tvName.setText(R.string.mqtt_connecting);
        tvStatus.setText(R.string.mqtt_connecting);

        //mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + System.currentTimeMillis());
        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + Utils.getDeviceIMEI(this));
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "connectComplete() called with: reconnect = [" + reconnect + "], serverURI = [" + serverURI + "]");
                tvStatus.setText(R.string.mqtt_connected);
                tvName.setBackgroundColor(colorConnected);

                subscribeToTopic();
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "connectionLost() for connection called with: cause = [" + cause + "]");
                tvStatus.setText(R.string.mqtt_connection_lost);
                tvName.setBackgroundColor(colorNotConnected);

                //Log.e(TAG, "Connection lost.. Trying to reconnect...");
                //debouncerReconnect.call(0);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d(TAG, "messageArrived() called with: topic = [" + topic + "] - retained: " + message.isRetained() + " - ID: " + message.getId() + " - Is Duplicate: " + message.isDuplicate() + " - QoS: " + message.getQos());


                if((topic.equals("recognitions/person") || topic.equals("recognitions/image")) && message.getPayload().length == 0) {
                    // Don't turn screen on when clearing the screen
                } else {
                    screenHandler.turnScreenOn("messageArrived: " + topic, MainActivity.this);
                }

                switch(topic) {
                    case "recognitions/person":
                        handlePersonName(message);
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
                Log.d(TAG, "onSuccess() for connect called with: asyncActionToken = [" + asyncActionToken + "] Exception:" + asyncActionToken.getException());

                DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                disconnectedBufferOptions.setBufferEnabled(true);
                disconnectedBufferOptions.setBufferSize(100);
                disconnectedBufferOptions.setPersistBuffer(false);
                disconnectedBufferOptions.setDeleteOldestMessages(false);
                mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                Log.w(TAG, "onFailure!");
                // Workaround for bug #209 (https://github.com/eclipse/paho.mqtt.android/issues/209)
                if(exception instanceof MqttException) {
                    MqttException ex = (MqttException) exception;
                    if(ex.getReasonCode() == MqttException.REASON_CODE_CLIENT_CONNECTED || ex.getReasonCode() == MqttException.REASON_CODE_CONNECT_IN_PROGRESS) {
                        Log.e(TAG, "Bug #209 detected - Debouncing reconnect!");
                        debouncerReconnect.call(0);
                    } else {
                        Log.e(TAG, "Exception catched.. don't know what to do with it.. Trying to reconnect... \n" + ex.getMessage());
                        debouncerReconnect.call(0);
                    }
                } else {
                    Log.e(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
                    debouncerReconnect.call(0);
                }
            }
        });
    }

    private void handlePersonName(MqttMessage message) throws JSONException {
        //Log.d(TAG, "message: \"" + message + "\"");
        List<String> names = new ArrayList<>();
        if(!message.toString().equals("")) {
            JSONObject jObject = new JSONObject(message.toString());
            JSONArray predictions = jObject.getJSONArray("predictions");
            for (int i = 0; i < predictions.length(); i++) {
                names.add(predictions.getJSONObject(i).getString("highest"));
            }
        } else {
            lastDetectedName = "";
        }
        String namesString = android.text.TextUtils.join(", ", names);

        Log.d(TAG, "NamesString: " + namesString);
        if(namesString.equals("unknown") && !lastDetectedName.equals("")) {
            // If the current detection is "unkown" and the lastDetectedName is not empty (that means that we detected another name before)
            Log.d(TAG, "Skipping unknown person name!!!");
            tvName.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        } else {
            lastDetectedName = namesString;
            tvName.setTextColor(getResources().getColor(android.R.color.primary_text_light));
        }
        tvName.setText(lastDetectedName);
    }

    private void parseImage(byte[] image) {
        //Log.d(TAG, "parseImage() called with: image = [" + image.length + "]");
        if(image.length > 0) {
            byte[] decodedString = Base64.decode(image, Base64.DEFAULT);


            Bitmap bmp = BitmapFactory.decodeByteArray(decodedString,0, decodedString.length);
            imgView.setImageBitmap(bmp);

            /*
            Glide
                .with(MainActivity.this)
                .load(decodedString)
                .into(imgView);
            */
        } else {
            Log.d(TAG, "clearing image");
            imgView.setImageResource(android.R.color.transparent);
        }
    }



    public void subscribeToTopic() {
        Log.d(TAG, "subscribeToTopic() called");

        boolean showLiveView = mPrefs.getBoolean(SETTING_LIVE_VIEW_BOOLEAN, false);
        String topic = mqttSubscriptionTopic;
        if(showLiveView) {
            topic = "camera";
        }

        mqttAndroidClient.subscribe(topic, 0, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                Log.d(TAG, "onSuccess() for subscribe called with: asyncActionToken = [" + asyncActionToken + "]");
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                Log.d(TAG, "onFailure() for subscribe called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
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
