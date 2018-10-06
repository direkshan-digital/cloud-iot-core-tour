// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.fancontrol;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;

import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;

import com.google.android.things.iotcore.ConnectionParams;
import com.google.android.things.iotcore.IotCoreClient;
import com.google.android.things.iotcore.TelemetryEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;


import java.io.IOException;

public class FanControlActivity extends Activity {

    private static final String TAG = FanControlActivity.class.getSimpleName();

    private boolean m_fanOn = false;
    private float m_currTemp = 0.0f;
    private int FRAME_DELAY_MS = 100;

    private ButtonInputDriver mButtonInputDriver;
    private AlphanumericDisplay mDisplay;

    private Apa102 mLedstrip;
    private int[] mRainbow = new int[7];
    private static final int LEDSTRIP_BRIGHTNESS = 1;

    private Gpio mLed;
    private int alphaTweak = 0;
    private int animCounter = 0;
    private boolean mIsConnected;
    private boolean mIsSimulated = false;

    private static final int MSG_UPDATE_BAROMETER_UI = 1;
    private IotCoreClient client;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_BAROMETER_UI:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Started Fan Control Station");

        ConnectivityManager cm =
                (ConnectivityManager)getApplicationContext().getSystemService(
                        Context.CONNECTIVITY_SERVICE
                );

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        mIsConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();


        setContentView(R.layout.activity_main);

        // GPIO button that generates 'A' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriver = new ButtonInputDriver(BoardDefaults.getButtonGpioPin(),
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_A);
            mButtonInputDriver.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_A");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        try {
            mDisplay = new AlphanumericDisplay(BoardDefaults.getI2cBus());
            mDisplay.setEnabled(true);
            mDisplay.clear();
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            Log.d(TAG, "Display disabled");
            mDisplay = null;
        }

        // SPI ledstrip
        try {
            mLedstrip = new Apa102(BoardDefaults.getSpiBus(), Apa102.Mode.BGR);
            mLedstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
            for (int i = 0; i < mRainbow.length; i++) {
                mRainbow[i] = Color.rgb(0, 0, 0);
            }
        } catch (IOException e) {
            mLedstrip = null; // Led strip is optional.
        }

        mHandler.post(mAnimateRunnable);

        // GPIO led
        try {
            PeripheralManager pioManager = PeripheralManager.getInstance();
            mLed = pioManager.openGpio(BoardDefaults.getLedGpioPin());
            mLed.setEdgeTriggerType(Gpio.EDGE_NONE);
            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        // Configure the Cloud IoT Connector --
        int pkId = getResources().getIdentifier("privatekey", "raw", getPackageName());
        try {
            if (pkId != 0) {
                InputStream privateKey = getApplicationContext()
                        .getResources().openRawResource(pkId);
                byte[] keyBytes = inputStreamToBytes(privateKey);

                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                KeyPair keys = new KeyPair(null, kf.generatePrivate(spec));

                // Configure Cloud IoT Core project information
                ConnectionParams connectionParams = new ConnectionParams.Builder()
                        .setProjectId("<your Google Cloud project ID>")
                        .setRegistry("<your Cloud IoT Core registry ID>", "<your registry's cloud region>")
                        .setDeviceId("<the device's ID in the registry>")
                        .build();

                // Initialize the IoT Core client
                client = new IotCoreClient.Builder()
                        .setConnectionParams(connectionParams)
                        .setKeyPair(keys)
                        .setOnConfigurationListener(this::onConfigurationReceived)
                        .build();

                // Connect to Cloud IoT Core
                client.connect();

                mHandler.post(mTempReportRunnable);
            }
        } catch (InvalidKeySpecException ikse) {
            Log.e(TAG, "INVALID Key spec", ikse);
        } catch (NoSuchAlgorithmException nsae) {
            Log.e(TAG, "Algorithm not supported", nsae);
        } catch (IOException ioe) {
            Log.e(TAG, "Could not load key from file", ioe);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_A) {
            Log.d(TAG, "A Click");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_A) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mButtonInputDriver != null) {
            try {
                mButtonInputDriver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonInputDriver = null;
        }

        if (mLedstrip != null) {
            try {
                mLedstrip.setBrightness(0);
                mLedstrip.write(new int[7]);
                mLedstrip.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling ledstrip", e);
            } finally {
                mLedstrip = null;
            }
        }

        if (mLed != null) {
            try {
                mLed.setValue(false);
                mLed.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                mLed = null;
            }
        }

        // clean up Cloud publisher.
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }
    private static byte[] inputStreamToBytes(InputStream is) throws IOException{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }
    private void onConfigurationReceived(byte[] bytes) {
        if (bytes.length == 0) {
            Log.d(TAG, "Ignoring empty device config event");
            return;
        }

        try {
            JSONObject message = new JSONObject(new String(bytes, "UTF-8"));
            m_fanOn = message.getBoolean("fan_on");
            Log.d(TAG, String.format("Config: %s", new String(bytes, "UTF-8")));
        } catch (JSONException je) {
            Log.d(TAG, "Could not decode JSON body for config", je);
        } catch (UnsupportedEncodingException iee) {
            Log.e(TAG, "Could not decode configuration message", iee);
        }
    }
    private Runnable mTempReportRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG,"Publishing telemetry event");

            String payload = String.format("{\"temperature\": %d}", (int)m_currTemp);
            TelemetryEvent event = new TelemetryEvent(payload.getBytes(),
                    null, TelemetryEvent.QOS_AT_LEAST_ONCE);
            client.publishTelemetry(event);

            mHandler.postDelayed(mTempReportRunnable, 2000); // Delay 2 secs, repost temp
        }
    };
    private Runnable mAnimateRunnable = new Runnable() {
        @Override
        public void run() {
            int[] colors = new int[mRainbow.length];
            animCounter = animCounter + 1;

            if (mIsSimulated) {
                // For testing
                if (m_currTemp > 40) {
                    m_fanOn = true;
                }
                if (m_currTemp < 10) {
                    m_fanOn = false;
                }
            } else {
                // Configuration messages are used to set fan state in another runnable.
            }

            if (!mIsConnected) {
                if ((animCounter & 1) == 0) {
                    for (int i = 0; i < colors.length; i++) {
                        colors[6 - i] = Color.rgb(0, 0, 0);
                    }
                    m_currTemp = 0f;
                } else {
                    for (int i = 0; i < colors.length; i++) {
                        colors[6 - i] = Color.rgb(0, 255, 0);
                    }
                    m_currTemp = -999f;
                }
            } else {
                if (m_fanOn) {
                    m_currTemp -= .03;
                    for (int i = 0; i < colors.length; i++) {
                        int a = alphaTweak + (i * (255 / 7));
                        colors[6 - i] = Color.rgb(0, 0, a % 255);
                    }
                } else {
                    alphaTweak += 255 / 7;
                    m_currTemp += .03;
                    for (int i = 0; i < colors.length; i++) {
                        int a = alphaTweak + (i * (255 / 7));
                        colors[i] = Color.rgb(a % 255, 0, 0);
                    }
                }
            }

            if (mDisplay != null) {
                try {
                    mDisplay.display(m_currTemp);
                } catch (IOException e) {
                    Log.e(TAG, "Error setting display", e);
                }
            }

            try {
                mLedstrip.write(colors);
            } catch (IOException e) {
                Log.e(TAG, "Error setting ledstrip", e);
            }

            // Trigger loop again in future.
            if (!mIsConnected) {
                if (animCounter < 6) { // Green blink animation
                    mHandler.postDelayed(mAnimateRunnable, 250);
                } else {
                    animCounter = 0;
                    mHandler.postDelayed(mAnimateRunnable, 1000);
                }
            } else {
                mHandler.postDelayed(mAnimateRunnable, FRAME_DELAY_MS); // Normal delay
            }

        }
    };
}
