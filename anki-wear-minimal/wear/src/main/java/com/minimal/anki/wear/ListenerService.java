package com.minimal.anki.wear;

import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class ListenerService extends WearableListenerService {

    private static final String TAG = "WearListenerService";
    public static final String ACTION_MESSAGE = "com.minimal.anki.MESSAGE";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_DATA = "data";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d(TAG, "received: " + messageEvent.getPath());
        Intent intent = new Intent(ACTION_MESSAGE);
        intent.putExtra(EXTRA_PATH, messageEvent.getPath());
        intent.putExtra(EXTRA_DATA, new String(messageEvent.getData()));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
