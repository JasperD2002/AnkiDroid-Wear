package com.minimal.anki.mobile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.minimal.anki.shared.CommonIdentifiers;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "anki_minimal_settings";
    private static final String KEY_FONT_Q = "font_size_question";
    private static final String KEY_FONT_A = "font_size_answer";
    private static final String KEY_PREFETCH = "prefetch_count";

    private EditText mFontQ, mFontA, mPrefetch;
    private GoogleApiClient mGoogleApiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mFontQ = findViewById(R.id.et_font_size_q);
        mFontA = findViewById(R.id.et_font_size_a);
        mPrefetch = findViewById(R.id.et_prefetch);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mFontQ.setText(String.valueOf(prefs.getInt(KEY_FONT_Q, 15)));
        mFontA.setText(String.valueOf(prefs.getInt(KEY_FONT_A, 15)));
        mPrefetch.setText(String.valueOf(prefs.getInt(KEY_PREFETCH, 3)));

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();

        findViewById(R.id.btn_save).setOnClickListener(v -> saveAndSend());
    }

    private void saveAndSend() {
        int fsQ, fsA, pf;
        try {
            fsQ = clamp(Integer.parseInt(mFontQ.getText().toString()), 10, 40);
            fsA = clamp(Integer.parseInt(mFontA.getText().toString()), 10, 40);
            pf = clamp(Integer.parseInt(mPrefetch.getText().toString()), 1, 20);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter valid numbers", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_FONT_Q, fsQ)
                .putInt(KEY_FONT_A, fsA)
                .putInt(KEY_PREFETCH, pf)
                .apply();

        sendToWatch(fsQ, fsA, pf);
        Toast.makeText(this, "Saved & sent to watch", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void sendToWatch(int fsQ, int fsA, int pf) {
        if (!mGoogleApiClient.isConnected()) {
            Toast.makeText(this, "Wear API not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject settings = new JSONObject();
                settings.put("fontSizeQuestion", fsQ);
                settings.put("fontSizeAnswer", fsA);
                settings.put("prefetchCount", pf);

                NodeApi.GetConnectedNodesResult nodes =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                                .await(15, TimeUnit.SECONDS);
                if (nodes != null) {
                    for (Node node : nodes.getNodes()) {
                        MessageApi.SendMessageResult result =
                                Wearable.MessageApi.sendMessage(
                                        mGoogleApiClient, node.getId(),
                                        CommonIdentifiers.P2W_CHANGE_SETTINGS,
                                        settings.toString().getBytes()
                                ).await(15, TimeUnit.SECONDS);
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Error sending: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
