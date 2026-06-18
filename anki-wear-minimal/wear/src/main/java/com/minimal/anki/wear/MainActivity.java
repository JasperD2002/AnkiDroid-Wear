package com.minimal.anki.wear;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.minimal.anki.shared.CommonIdentifiers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements
        DeckSelectFragment.OnDeckSelectedListener,
        ReviewFragment.Callbacks {

    private static final String TAG = "MainActivity";
    private static final int PRE_FETCH_COUNT_DEFAULT = 3;

    private int getPrefetchCount() {
        return getSharedPreferences("anki_settings", MODE_PRIVATE)
                .getInt("prefetch", PRE_FETCH_COUNT_DEFAULT);
    }

    private GoogleApiClient mGoogleApiClient;
    private List<JSONObject> mCardQueue = new ArrayList<>();
    private long mCurrentDeckId = -1;
    private String mCurrentDeckName = "";

    private DeckSelectFragment mDeckFragment;
    private ReviewFragment mReviewFragment;
    private MessageApi.MessageListener mDirectListener;

    private final BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String path = intent.getStringExtra(ListenerService.EXTRA_PATH);
            String data = intent.getStringExtra(ListenerService.EXTRA_DATA);
            handleMessage(path, data);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDirectListener = messageEvent -> {
            String path = messageEvent.getPath();
            String data = new String(messageEvent.getData());
            Log.d(TAG, "Direct listener: " + path);
            runOnUiThread(() -> handleMessage(path, data));
        };

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        Log.d(TAG, "GoogleApiClient connected");
                        Wearable.MessageApi.addListener(mGoogleApiClient, mDirectListener);
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.w(TAG, "Connection suspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(result -> {
                    Log.e(TAG, "Connection failed: " + result.getErrorCode() + ", retrying in 3s");
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> mGoogleApiClient.connect(), 3000);
                })
                .build();
        mGoogleApiClient.connect();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(ListenerService.ACTION_MESSAGE));

        showDeckSelect(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestDecks();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            Wearable.MessageApi.removeListener(mGoogleApiClient, mDirectListener);
            mGoogleApiClient.disconnect();
        }
        super.onDestroy();
    }

    private void handleMessage(String path, String data) {
        Log.d(TAG, "handleMessage: " + path);

        try {
            if (CommonIdentifiers.P2W_COLLECTION_LIST.equals(path)) {
                JSONArray decks = new JSONArray(data);
                if (mDeckFragment != null && mDeckFragment.isVisible()) {
                    mDeckFragment.onDecksReceived(decks);
                }
            } else if (CommonIdentifiers.P2W_RESPOND_CARD.equals(path)) {
                JSONObject wrapper = new JSONObject(data);
                JSONArray cards = wrapper.getJSONArray("cards");
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.getJSONObject(i);
                    long noteId = card.optLong(CommonIdentifiers.FIELD_NOTE_ID, -1);
                    int cardOrd = card.optInt(CommonIdentifiers.FIELD_CARD_ORD, -1);
                    boolean isCurrent = mReviewFragment != null
                            && mReviewFragment.isCurrentCard(noteId, cardOrd);
                    boolean inQueue = false;
                    for (JSONObject q : mCardQueue) {
                        if (q.optLong(CommonIdentifiers.FIELD_NOTE_ID, -1) == noteId
                                && q.optInt(CommonIdentifiers.FIELD_CARD_ORD, -1) == cardOrd) {
                            inQueue = true;
                            break;
                        }
                    }
                    if (!isCurrent && !inQueue) {
                        mCardQueue.add(card);
                    }
                }
                if (mReviewFragment != null && mReviewFragment.isVisible()) {
                    mReviewFragment.onCardReceived();
                }
            } else if (CommonIdentifiers.P2W_NO_MORE_CARDS.equals(path)) {
                if (mCardQueue.isEmpty()) {
                    if (mReviewFragment != null && mReviewFragment.isVisible()) {
                        mReviewFragment.onNoMoreCards();
                    }
                } else {
                    Log.d(TAG, "Ignoring P2W_NO_MORE_CARDS — queue still has " + mCardQueue.size() + " cards");
                }
            } else if (CommonIdentifiers.P2W_ANSWER_ACK.equals(path)) {
                Log.d(TAG, "Answer confirmed by phone: " + data);
            } else if (CommonIdentifiers.P2W_TOP_CARD_CHANGED.equals(path)) {
                handleTopCardChanged(new JSONObject(data));
            } else if (CommonIdentifiers.P2W_CHANGE_SETTINGS.equals(path)) {
                JSONObject settings = new JSONObject(data);
                int fsQ = settings.optInt("fontSizeQuestion", 15);
                int fsA = settings.optInt("fontSizeAnswer", 15);
                int pf = settings.optInt("prefetchCount", 3);
                getSharedPreferences("anki_settings", MODE_PRIVATE)
                        .edit()
                        .putInt("font_size_q", fsQ)
                        .putInt("font_size_a", fsA)
                        .putInt("prefetch", pf)
                        .apply();
                if (mReviewFragment != null && mReviewFragment.isVisible()) {
                    mReviewFragment.applyFontSizes(fsQ, fsA);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling message", e);
        }
    }

    // --- Deck selection ---

    private void requestDecks() {
        fireMessage(CommonIdentifiers.W2P_REQUEST_DECKS, "");
    }

    @Override
    public void onDeckSelected(long deckId, String deckName) {
        mCurrentDeckId = deckId;
        mCurrentDeckName = deckName;
        mCardQueue.clear();
        showReview(true);
    }

    @Override
    public void onRetryDecks() {
        requestDecks();
    }

    // --- Card callbacks ---

    @Override
    public void onAnswerCard(long noteId, int cardOrd, int ease) {
        try {
            JSONObject answer = new JSONObject();
            answer.put(CommonIdentifiers.FIELD_NOTE_ID, noteId);
            answer.put(CommonIdentifiers.FIELD_CARD_ORD, cardOrd);
            answer.put(CommonIdentifiers.FIELD_EASE, ease);
            answer.put(CommonIdentifiers.FIELD_COUNT, getPrefetchCount());
            answer.put(CommonIdentifiers.FIELD_DECK_ID, mCurrentDeckId);
            fireMessage(CommonIdentifiers.W2P_RESPOND_CARD_EASE, answer.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending answer", e);
        }
    }

    @Override
    public void onRequestCards() {
        try {
            JSONObject request = new JSONObject();
            request.put(CommonIdentifiers.FIELD_DECK_ID, mCurrentDeckId);
            request.put(CommonIdentifiers.FIELD_COUNT, getPrefetchCount());
            fireMessage(CommonIdentifiers.W2P_REQUEST_CARD, request.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error requesting cards", e);
        }
    }

    @Override
    public int getQueueSize() {
        return mCardQueue.size();
    }

    @Override
    public void onReviewFinished() {
        showDeckSelect(true);
    }

    @Override
    public void onBackToDecks() {
        restartApp();
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }

    public JSONObject dequeueCard() {
        if (mCardQueue.isEmpty()) return null;
        return mCardQueue.remove(0);
    }

    private void handleTopCardChanged(JSONObject card) {
        try {
            long noteId = card.getLong(CommonIdentifiers.FIELD_NOTE_ID);
            int cardOrd = card.getInt(CommonIdentifiers.FIELD_CARD_ORD);

            // Check if already the current card
            if (mReviewFragment != null && mReviewFragment.isCurrentCard(noteId, cardOrd)) {
                Log.d(TAG, "Top card " + noteId + "/" + cardOrd + " is already displayed");
                return;
            }

            // Check if in queue
            int queueIndex = -1;
            for (int i = 0; i < mCardQueue.size(); i++) {
                JSONObject q = mCardQueue.get(i);
                if (q.optLong(CommonIdentifiers.FIELD_NOTE_ID, -1) == noteId
                        && q.optInt(CommonIdentifiers.FIELD_CARD_ORD, -1) == cardOrd) {
                    queueIndex = i;
                    break;
                }
            }

            if (queueIndex >= 0) {
                JSONObject queued = mCardQueue.remove(queueIndex);
                Log.d(TAG, "Top card " + noteId + "/" + cardOrd + " found in queue at " + queueIndex + ", displaying");
                if (mReviewFragment != null) {
                    mReviewFragment.setCard(queued);
                }
            } else {
                Log.d(TAG, "Top card " + noteId + "/" + cardOrd + " not in queue, requesting refresh");
                mCardQueue.clear();
                onRequestCards();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling top card changed", e);
        }
    }

    // --- Navigation ---

    private void showDeckSelect(boolean addToBackStack) {
        if (mReviewFragment != null) {
            mReviewFragment = null;
        }
        mDeckFragment = new DeckSelectFragment();
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.fragment_container, mDeckFragment, "decks");
        if (addToBackStack) ft.addToBackStack(null);
        ft.commit();
    }

    private void showReview(boolean requestCards) {
        mReviewFragment = new ReviewFragment();
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.fragment_container, mReviewFragment, "review");
        ft.addToBackStack(null);
        ft.commit();

        if (requestCards) {
            try {
                JSONObject select = new JSONObject();
                select.put(CommonIdentifiers.FIELD_DECK_ID, mCurrentDeckId);
                select.put(CommonIdentifiers.FIELD_COUNT, getPrefetchCount());
                fireMessage(CommonIdentifiers.W2P_CHOOSE_COLLECTION, select.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error selecting deck", e);
            }
        }
    }

    // --- Messaging ---

    private void fireMessage(final String path, final String data) {
        if (mGoogleApiClient.isConnected()) {
            sendMessageAsync(path, data);
        } else {
            mGoogleApiClient.registerConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                @Override
                public void onConnected(Bundle bundle) {
                    mGoogleApiClient.unregisterConnectionCallbacks(this);
                    sendMessageAsync(path, data);
                }
                @Override
                public void onConnectionSuspended(int cause) {
                    Log.w(TAG, "Connection suspended: " + cause);
                }
            });
        }
    }

    private void sendMessageAsync(final String path, final String data) {
        new Thread(() -> {
            try {
                NodeApi.GetConnectedNodesResult nodes =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                                .await(15, TimeUnit.SECONDS);
                if (nodes == null) {
                    Log.e(TAG, "getConnectedNodes timed out for " + path);
                    return;
                }
                Log.d(TAG, nodes.getNodes().size() + " connected nodes for " + path);
                for (Node node : nodes.getNodes()) {
                    MessageApi.SendMessageResult result =
                            Wearable.MessageApi.sendMessage(
                                    mGoogleApiClient, node.getId(), path, data.getBytes()
                            ).await(15, TimeUnit.SECONDS);
                    Log.d(TAG, "Sent " + path + " to " + node.getDisplayName() + ": "
                            + (result == null ? "TIMEOUT"
                               : result.getStatus().isSuccess() ? "OK" : result.getStatus()));
                }
                if (nodes.getNodes().isEmpty()) {
                    Log.w(TAG, "No connected nodes for " + path);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending " + path + ": " + e.getMessage(), e);
            }
        }).start();
    }
}
