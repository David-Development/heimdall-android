package de.luhmer.heimdall;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import rx.functions.Action1;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getCanonicalName();



    MqttAndroidClient mqttAndroidClient;
    String mqttServerUri;
    final String mqttClientId = "heimdall-android-";
    final String mqttSubscriptionTopic = "recognitions/#";
    SharedPreferences mPrefs;
    static final String MQTT_SERVER_IP = "MQTT_URL";

    TextView tvName;


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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);


        setContentView(R.layout.activity_main);


        mPrefs =  PreferenceManager.getDefaultSharedPreferences(this);
        setMqttServerIP(mPrefs.getString(MQTT_SERVER_IP, ""));

        /*
        PowerManager mPowerManager = ((PowerManager)getSystemService(POWER_SERVICE));
        PowerManager.WakeLock mWakeLock;
        mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "tag");
        mWakeLock.acquire();
        */

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEnterMqttServerIpDialog();
            }
        });


        final ImageView imgView = findViewById(R.id.imgView);

        tvName = findViewById(R.id.tvName);
        tvName.setText("Verbinde..");

        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d(TAG, "onTouch() called with: v = [" + v + "], event = [" + event + "]");
                turnScreenOn();
                return false;
            }
        });

        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), mqttServerUri, mqttClientId + System.currentTimeMillis());
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "connectComplete() called with: reconnect = [" + reconnect + "], serverURI = [" + serverURI + "]");

                if (reconnect) {

                } else {

                }

                tvName.setText("Verbindung hergestellt");
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.d(TAG, "connectionLost() called with: cause = [" + cause + "]");

                tvName.setText("Verbindung verloren");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d(TAG, "messageArrived() called with: topic = [" + topic + "], message = [" + message + "]");

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
                        byte[] decodedString = Base64.decode(message.getPayload(), Base64.DEFAULT);
                        Glide.with(MainActivity.this).load(decodedString).into(imgView);
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
                Log.d(TAG, "onFailure() called with: asyncActionToken = [" + asyncActionToken + "], exception = [" + exception + "]");
            }
        });


        new CountDownTimer(10000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                Log.d(TAG, "onTick() called with: millisUntilFinished = [" + millisUntilFinished + "]");
            }

            @Override
            public void onFinish() {
                turnScreenOff();
            }
        }.start();



        // If no ip has been configured yet
        if(mPrefs.getString(MQTT_SERVER_IP, "").isEmpty()) {
            showEnterMqttServerIpDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        turnScreenOn();
    }

    private void turnScreenOn() {
        Log.d(TAG, "turnScreenOn() called");
        WindowManager.LayoutParams params = getWindow().getAttributes();

        params.screenBrightness = -1; // Back to previous
        //params.screenBrightness = 1; // Full brightness
        getWindow().setAttributes(params);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    private void turnScreenOff() {
        Log.d(TAG, "turnScreenOff() called");

        WindowManager.LayoutParams params = getWindow().getAttributes();
        //params.screenBrightness = 0;
        params.screenBrightness = 0.1f;
        getWindow().setAttributes(params);
    }

    public void subscribeToTopic(){

        mqttAndroidClient.subscribe(mqttSubscriptionTopic, 0, null, new IMqttActionListener() {
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


    private void showEnterMqttServerIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("MQTT Server IP");

        // Set up the input
        final EditText input = new EditText(MainActivity.this);
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Speichern", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String serverIP = input.getText().toString();
                // TODO make some kind of regex check for valid IP
                setMqttServerIP(serverIP);
                mPrefs.edit().putString(MQTT_SERVER_IP, serverIP).apply();
            }
        });
        builder.setNegativeButton("Abbrechen", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void setMqttServerIP(String serverIP) {
        this.mqttServerUri = "tcp://" + serverIP + ":1883";
    }
}
