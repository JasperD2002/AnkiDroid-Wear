package com.minimal.anki.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.minimal.anki.shared.CommonIdentifiers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class MobileActivity extends Activity {

    private static final String TAG = "AnkiDebug";
    private static final String PERM_ANKI_DB = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    private static final int REQUEST_ANKI = 100;
    private static final String AUTHORITY = "com.ichi2.anki.flashcards";
    private static final Uri DECKS_URI = Uri.parse("content://" + AUTHORITY + "/decks");

    private TextView mWearStatus, mWearResult, mAnkiStatus, mAnkiResult;
    private TextView mSelfTestResult, mDbResult, mQueueState, mLog;
    private StringBuilder mLogBuf = new StringBuilder();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private GoogleApiClient mGoogleApiClient;
    private OkHttpClient mAnkiConnectClient;
    private int mSelfTestCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mWearStatus = findViewById(R.id.tv_wear_status);
        mWearResult = findViewById(R.id.tv_wear_result);
        mAnkiStatus = findViewById(R.id.tv_anki_status);
        mAnkiResult = findViewById(R.id.tv_anki_result);
        mSelfTestResult = findViewById(R.id.tv_self_test_result);
        mDbResult = findViewById(R.id.tv_db_result);
        mQueueState = findViewById(R.id.tv_queue_state);
        mLog = findViewById(R.id.tv_log);
        mLog.setMovementMethod(new ScrollingMovementMethod());

        findViewById(R.id.btn_test_wear).setOnClickListener(v -> sendTestToWatch());
        findViewById(R.id.btn_query_decks).setOnClickListener(v -> queryDecks());
        findViewById(R.id.btn_test_ankiconnect).setOnClickListener(v -> testAnkiConnect());
        findViewById(R.id.btn_grant_perm).setOnClickListener(v -> requestAnkiPerm());
        findViewById(R.id.btn_self_test).setOnClickListener(v -> runSelfTest());
        findViewById(R.id.btn_debug_db).setOnClickListener(v -> debugDbAccess());
        findViewById(R.id.btn_grant_storage).setOnClickListener(v -> requestStorageAccess());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());
        findViewById(R.id.btn_service_logs).setOnClickListener(v -> loadServiceLogs());
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_queue_state).setOnClickListener(v -> loadQueueState());

        log("App started, initializing GoogleApiClient...");
        mAnkiConnectClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        initWearApi();
        checkAnkiStatus();
    }

    @Override
    protected void onDestroy() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            Wearable.MessageApi.removeListener(mGoogleApiClient, mMessageListener);
            mGoogleApiClient.disconnect();
        }
        super.onDestroy();
    }

    // --- Wear API ---

    private void initWearApi() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        log("GoogleApiClient connected");
                        mUiHandler.post(() -> mWearStatus.setText("Connected"));
                        Wearable.MessageApi.addListener(mGoogleApiClient, mMessageListener);
                        queryNodes();
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        log("Connection suspended: " + cause);
                        mUiHandler.post(() -> mWearStatus.setText("Suspended (" + cause + ")"));
                    }
                })
                .addOnConnectionFailedListener(result -> {
                    log("Connection FAILED: " + result.getErrorCode());
                    mUiHandler.post(() -> mWearStatus.setText("Failed: " + result.getErrorCode()));
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> mGoogleApiClient.connect(), 3000);
                })
                .build();
        mGoogleApiClient.connect();
    }

    private void queryNodes() {
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(
                nodes -> {
                    StringBuilder sb = new StringBuilder("Connected. Nodes: " + nodes.getNodes().size());
                    for (Node n : nodes.getNodes()) {
                        sb.append("\n  ").append(n.getDisplayName()).append(" (").append(n.getId()).append(")");
                    }
                    mUiHandler.post(() -> mWearStatus.setText(sb.toString()));
                });
    }

    // --- Message listener for self-test ---

    private final MessageApi.MessageListener mMessageListener = new MessageApi.MessageListener() {
        @Override
        public void onMessageReceived(MessageEvent event) {
            String path = event.getPath();
            String data = new String(event.getData());
            log("RECEIVED: " + path + " | " + data);
            if (CommonIdentifiers.W2P_REQUEST_DECKS.equals(path)) {
                log("  -> SIMULATES watch's deck request! (watch would see this)");
                mUiHandler.post(() -> {
                    mWearResult.setText("Watch WOULD have received a response now");
                    mSelfTestResult.setText("Self-test: phone received the message OK");
                });
            }
            if (path.equals("/minimal/debug/loopback")) {
                mUiHandler.post(() -> mSelfTestResult.setText("LOOPBACK OK! Message round-trip works."));
                log("  -> LOOPBACK SUCCESS");
            }
            if (path.equals("/minimal/debug/ping")) {
                mUiHandler.post(() -> {
                    mWearResult.setText("PING from self-test received!");
                    mSelfTestResult.setText("Self-test: message listener is working");
                });
            }
        }
    };

    // --- Send test to watch ---

    private void sendTestToWatch() {
        mWearResult.setText("Sending...");
        if (!mGoogleApiClient.isConnected()) {
            mWearResult.setText("Not connected to Wear API");
            return;
        }
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(nodes -> {
            if (nodes.getNodes().isEmpty()) {
                mWearResult.setText("No connected nodes (is watch paired?)");
                return;
            }
            for (Node node : nodes.getNodes()) {
                String payload = "{\"test\":true,\"from\":\"debug\"," +
                        "\"time\":" + System.currentTimeMillis() + "}";
                Wearable.MessageApi.sendMessage(
                        mGoogleApiClient, node.getId(),
                        "/minimal/debug/ping", payload.getBytes()
                ).setResultCallback(result -> {
                    String status = result.getStatus().isSuccess() ? "OK" : "FAIL: " + result.getStatus();
                    log("Sent ping to " + node.getDisplayName() + ": " + status);
                    mUiHandler.post(() -> mWearResult.setText(
                            "Sent to " + node.getDisplayName() + ": " + status));
                });
            }
        });
    }

    // --- Self-test: send a message to ourselves ---

    private void runSelfTest() {
        mSelfTestResult.setText("Running...");
        // Register a one-shot to detect the loopback
        // We send to our own node if available, otherwise we can't self-test properly
        if (!mGoogleApiClient.isConnected()) {
            mSelfTestResult.setText("Not connected");
            return;
        }
        Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(localNodeResult -> {
            final String localNodeId = localNodeResult.getNode().getId();
            log("Local node: " + localNodeId);

            // Send to self (tests listener registration)
            String payload = "{\"selfTest\":true,\"count\":" + (++mSelfTestCount) + "}";
            Wearable.MessageApi.sendMessage(
                    mGoogleApiClient, localNodeId,
                    "/minimal/debug/loopback", payload.getBytes()
            ).setResultCallback(result -> {
                if (result.getStatus().isSuccess()) {
                    log("Loopback message SENT to self, waiting for listener...");
                    mUiHandler.post(() -> mSelfTestResult.setText("Sent to self, waiting for listener..."));
                    // If listener doesn't fire in 5s, it's not registered
                    mUiHandler.postDelayed(() -> {
                        if (!mSelfTestResult.getText().toString().contains("OK")) {
                            mSelfTestResult.setText("TIMEOUT: listener not triggered!\n" +
                                    "Check that MessageApi.addListener() works.");
                            log("SELF-TEST TIMEOUT: listener never received the message");
                        }
                    }, 5000);
                } else {
                    log("Loopback send FAILED: " + result.getStatus());
                    mUiHandler.post(() -> mSelfTestResult.setText("Send failed: " + result.getStatus()));
                }
            });
        });
    }

    // --- AnkiDroid queries ---

    private void checkAnkiStatus() {
        boolean granted = checkSelfPermission(PERM_ANKI_DB) == PackageManager.PERMISSION_GRANTED;
        mAnkiStatus.setText(granted ? "Permission granted" : "Permission NOT granted");
        if (!granted) {
            log("AnkiDroid permission not granted, requesting...");
        }
    }

    private void requestAnkiPerm() {
        requestPermissions(new String[]{PERM_ANKI_DB}, REQUEST_ANKI);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQUEST_ANKI) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            mAnkiStatus.setText(granted ? "Permission GRANTED" : "Permission DENIED");
            log("Permission " + (granted ? "GRANTED" : "DENIED"));
        }
    }

    private String ankiConnectDebug(String action, JSONObject params) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", action);
            payload.put("version", 6);
            payload.put("params", params != null ? params : new JSONObject());
            RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder().url("http://localhost:8765").post(body).build();
            okhttp3.Response response = mAnkiConnectClient.newCall(request).execute();
            String bodyStr = response.body().string();
            response.close();
            JSONObject recv = new JSONObject(bodyStr);
            return ">> REQ: " + action + "\nSEND: " + payload.toString() + "\nRECV: " + recv.toString(2);
        } catch (Exception e) {
            return ">> REQ: " + action + "\nERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void testAnkiConnect() {
        mAnkiResult.setText("Testing version...");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            out.append(ankiConnectDebug("version", null)).append("\n\n");
            out.append(ankiConnectDebug("guiCurrentCard", null)).append("\n\n");
            out.append(ankiConnectDebug("guiAnswerCard", new JSONObject())).append("\n\n");
            final String result = out.toString();
            log(result);
            mUiHandler.post(() -> mAnkiResult.setText(result));
        }).start();
    }

    private void queryDecks() {
        mAnkiResult.setText("Querying...");
        if (checkSelfPermission(PERM_ANKI_DB) != PackageManager.PERMISSION_GRANTED) {
            mAnkiResult.setText("Permission not granted");
            return;
        }
        new Thread(() -> {
            try {
                Cursor cursor = getContentResolver().query(DECKS_URI, null, null, null, null);
                StringBuilder sb = new StringBuilder();
                if (cursor == null) {
                    sb.append("Cursor is NULL. Is AnkiDroid installed?\n");
                    sb.append("Content URI: ").append(DECKS_URI);
                } else {
                    sb.append("Columns: ");
                    String[] cols = cursor.getColumnNames();
                    for (int i = 0; i < cols.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(cols[i]);
                    }
                    sb.append("\n");
                    int count = 0;
                    while (cursor.moveToNext()) {
                        count++;
                        sb.append("\n").append(count).append(". ");
                        for (int i = 0; i < cols.length; i++) {
                            try {
                                String val = cursor.getString(i);
                                if (val != null && val.length() > 80) val = val.substring(0, 80) + "...";
                                sb.append(cols[i]).append("=").append(val).append(" ");
                            } catch (Exception e) {
                                sb.append(cols[i]).append("=<err> ");
                            }
                        }
                    }
                    cursor.close();
                    if (count == 0) {
                        sb.append("No decks found. Does AnkiDroid have data?");
                    }
                }
                log("Decks query:\n" + sb);
                mUiHandler.post(() -> mAnkiResult.setText(sb.toString()));
            } catch (Exception e) {
                String msg = "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log("Decks query error: " + msg);
                mUiHandler.post(() -> mAnkiResult.setText(msg));
            }
        }).start();
    }

    // --- Log ---

    private void log(String msg) {
        android.util.Log.d(TAG, msg);
        String ts = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
                .format(new java.util.Date());
        mLogBuf.insert(0, ts + " " + msg + "\n");
        if (mLogBuf.length() > 10000) {
            mLogBuf.setLength(10000);
        }
        mUiHandler.post(() -> {
            mLog.setText(mLogBuf.toString());
        });
    }

    private void clearLog() {
        mLogBuf.setLength(0);
        mLog.setText("");
    }

    private void loadQueueState() {
        String state = WearMessageListenerService.getStateSnapshot();
        log("--- Queue State ---\n" + state + "--- End Queue State ---");
        mQueueState.setText(state);
    }

    private void loadServiceLogs() {
        log("--- Service logs ---");
        List<String> logs = WearMessageListenerService.getServiceLogs();
        for (String line : logs) {
            log("  " + line);
        }
        if (logs.isEmpty()) {
            log("  (no service logs yet)");
        }
        log("--- End service logs ---");
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) {
                log("All files access already granted.");
                mDbResult.setText("All files access already granted.");
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                log("Launched settings for all files access. Grant permission and come back.");
                mDbResult.setText("Granted? Tap 'Test collection.anki2 Access' after granting.");
            }
        } else {
            log("All files access not needed on this Android version.");
            mDbResult.setText("Not needed on Android < 11.");
        }
    }

    private void debugDbAccess() {
        mDbResult.setText("Testing DB access...");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();

            if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                out.append("MISSING PERMISSION: MANAGE_EXTERNAL_STORAGE\n");
                out.append("Tap 'Grant All Files Access' button above, enable it, then come back.\n");
                final String result = out.toString();
                log("DB Debug (no perm):\n" + result);
                mUiHandler.post(() -> mDbResult.setText(result));
                return;
            }

            String[] paths = {
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/AnkiDroid/collection.anki2",
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2",
                "/storage/emulated/0/AnkiDroid/collection.anki2",
                "/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2"
            };
            String foundPath = null;
            for (String p : paths) {
                java.io.File f = new java.io.File(p);
                out.append("Check ").append(p).append(": ");
                if (f.exists()) {
                    out.append("EXISTS (r=").append(f.canRead()).append(", s=").append(f.length()).append(")\n");
                    if (foundPath == null) foundPath = p;
                } else {
                    out.append("not found\n");
                }
            }
            if (foundPath == null) {
                out.append("\nNO DB FILE FOUND at any path!");
            } else {
                out.append("\nUsing: ").append(foundPath).append("\n");
                SQLiteDatabase db = null;
                android.database.Cursor c = null;
                try {
                    db = SQLiteDatabase.openDatabase(foundPath, null,
                            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                    out.append("OPEN OK\n");
                    c = db.rawQuery("SELECT COUNT(*) FROM cards", null);
                    if (c != null && c.moveToFirst()) {
                        out.append("Cards table: ").append(c.getInt(0)).append(" rows\n");
                    }
                    c.close();
                    c = db.rawQuery("SELECT nid, ord, type, queue, flags FROM cards LIMIT 5", null);
                    out.append("Columns: ");
                    for (String col : c.getColumnNames()) {
                        out.append(col).append(" ");
                    }
                    out.append("\n");
                    int count = 0;
                    while (c.moveToNext()) {
                        count++;
                        out.append("  card ").append(count).append(": nid=").append(c.getLong(0));
                        out.append(" ord=").append(c.getInt(1));
                        out.append(" type=").append(c.getInt(2));
                        out.append(" queue=").append(c.getInt(3));
                        out.append(" flags=").append(c.getInt(4));
                        out.append("\n");
                    }
                    if (count == 0) out.append("  (no cards in database)\n");
                    c.close();
                } catch (Exception e) {
                    out.append("ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                } finally {
                    if (c != null) c.close();
                    if (db != null) db.close();
                }
            }
            final String result = out.toString();
            log("DB Debug:\n" + result);
            mUiHandler.post(() -> mDbResult.setText(result));
        }).start();
    }
}
