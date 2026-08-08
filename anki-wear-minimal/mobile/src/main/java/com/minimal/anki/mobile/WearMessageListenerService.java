package com.minimal.anki.mobile;

import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.minimal.anki.shared.CommonIdentifiers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


public class WearMessageListenerService extends WearableListenerService {

    private static final String TAG = "WearMsgListener";
    private static final String AUTHORITY = "com.ichi2.anki.flashcards";
    private static final Uri SCHEDULE_URI = Uri.parse("content://" + AUTHORITY + "/schedule");
    private static final Uri NOTES_URI = Uri.parse("content://" + AUTHORITY + "/notes");
    private static final Uri DECKS_URI = Uri.parse("content://" + AUTHORITY + "/decks");
    private static final Uri SELECTED_DECK_URI = Uri.parse("content://" + AUTHORITY + "/selected_deck");

    private static final String PERM_ANKI_DB = "com.ichi2.anki.permission.READ_WRITE_DATABASE";

    private static final List<String> sServiceLogs = new ArrayList<>();
    private static String sStateSnapshot = "(service not started)";
    private static final List<String> sFlushLog = new ArrayList<>();

    public static List<String> getServiceLogs() {
        synchronized (sServiceLogs) {
            return new ArrayList<>(sServiceLogs);
        }
    }

    private static void serviceLog(String msg) {
        String ts = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        synchronized (sServiceLogs) {
            sServiceLogs.add(0, ts + " " + msg);
            if (sServiceLogs.size() > 200) {
                sServiceLogs.remove(sServiceLogs.size() - 1);
            }
        }
    }

    private GoogleApiClient mGoogleApiClient;
    private long mCurrentDeckId = -1;
    private final ExecutorService mSingleThread = Executors.newSingleThreadExecutor();
    private final Set<String> mWatchCache = new HashSet<>();
    private final List<JSONObject> mPendingAnswers = new ArrayList<>();
    private static final AtomicBoolean sFlushTimerRunning = new AtomicBoolean(false);

    private static String cardKey(long noteId, int cardOrd) {
        return noteId + ":" + cardOrd;
    }

    public static String getStateSnapshot() {
        return sStateSnapshot;
    }

    private void updateSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("Deck: ").append(mCurrentDeckId).append("\n");
        sb.append("Cache (").append(mWatchCache.size()).append("): ");
        if (mWatchCache.isEmpty()) {
            sb.append("(empty)");
        } else {
            for (String key : mWatchCache) {
                sb.append(key).append(" ");
            }
        }
        sb.append("\n");
        sb.append("Recent flushes:\n");
        synchronized (sFlushLog) {
            if (sFlushLog.isEmpty()) {
                sb.append("  (none yet)\n");
            } else {
                for (String line : sFlushLog) {
                    sb.append("  ").append(line).append("\n");
                }
            }
        }
        sb.append("Top of deck:\n");
        try {
            Cursor top = getContentResolver().query(
                    SCHEDULE_URI,
                    new String[]{"note_id", "ord"},
                    "limit=1, deckID=?",
                    new String[]{String.valueOf(mCurrentDeckId)},
                    null
            );
            if (top != null && top.moveToFirst()) {
                sb.append("  note_id=").append(top.getLong(0))
                        .append(" ord=").append(top.getInt(1)).append("\n");
                top.close();
            } else {
                sb.append("  (none — deck may be empty)\n");
                if (top != null) top.close();
            }
        } catch (Exception e) {
            sb.append("  (query error: ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append(")\n");
        }
        sStateSnapshot = sb.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, WearMessageListenerService.class));
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        Log.d(TAG, "GoogleApiClient connected");
                        serviceLog("GoogleApiClient connected");
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.w(TAG, "Connection suspended: " + cause);
                        serviceLog("Connection suspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(result -> {
                    Log.e(TAG, "Connection failed: " + result.getErrorCode() + ", retrying in 3s");
                    serviceLog("Connection failed: " + result.getErrorCode() + ", retrying in 3s");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> mGoogleApiClient.connect(), 3000);
                })
                .build();
        mGoogleApiClient.connect();
        serviceLog("Service created, GoogleApiClient connecting");
        startFlushRetryTimer();
    }

    @Override
    public void onDestroy() {
        serviceLog("Service destroying, NOT disconnecting GoogleApiClient");
        super.onDestroy();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        String data = new String(messageEvent.getData());
        Log.d(TAG, "onMessageReceived: " + path + " data: " + data);
        serviceLog("Received: " + path);
        dispatchMessage(path, data);
    }

    private void dispatchMessage(String path, String data) {
        if (CommonIdentifiers.W2P_REQUEST_DECKS.equals(path)) {
            handleDecksRequest();
        } else if (CommonIdentifiers.W2P_CHOOSE_COLLECTION.equals(path)) {
            handleDeckSelect(data);
        } else if (CommonIdentifiers.W2P_RESPOND_CARD_EASE.equals(path)) {
            handleCardAnswer(data);
        } else if (CommonIdentifiers.W2P_REQUEST_CARD.equals(path)) {
            handleCardRequest(data);
        } else if (CommonIdentifiers.W2P_REQUEST_LAST_DECK.equals(path)) {
            handleLastDeckRequest();
        }
    }

    private boolean hasAnkiPerm() {
        if (checkSelfPermission(PERM_ANKI_DB) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing " + PERM_ANKI_DB + " — open the phone app to grant it");
            serviceLog("MISSING PERM: " + PERM_ANKI_DB);
            return false;
        }
        return true;
    }

    private void handleDecksRequest() {
        if (!hasAnkiPerm()) return;
        try {
            Cursor cursor = getContentResolver().query(DECKS_URI, null, null, null, null);
            if (cursor == null) {
                Log.w(TAG, "Decks cursor is null");
                serviceLog("Decks cursor is null");
                return;
            }
            JSONArray decks = new JSONArray();
            int colDeckId = cursor.getColumnIndexOrThrow("deck_id");
            int colDeckName = cursor.getColumnIndexOrThrow("deck_name");
            int colDeckCounts = cursor.getColumnIndexOrThrow("deck_count");
            while (cursor.moveToNext()) {
                JSONObject deck = new JSONObject();
                long deckId = cursor.getLong(colDeckId);
                deck.put(CommonIdentifiers.FIELD_DECK_ID, deckId);
                deck.put(CommonIdentifiers.FIELD_DECK_NAME, cursor.getString(colDeckName));
                JSONArray counts = new JSONArray(cursor.getString(colDeckCounts));
                deck.put(CommonIdentifiers.FIELD_DECK_LEARN, counts.optInt(0, 0));
                deck.put(CommonIdentifiers.FIELD_DECK_REVIEW, counts.optInt(1, 0));
                deck.put(CommonIdentifiers.FIELD_DECK_NEW, counts.optInt(2, 0));

                Cursor top = null;
                try {
                    top = getContentResolver().query(
                            SCHEDULE_URI,
                            new String[]{"note_id", "ord"},
                            "limit=1, deckID=?",
                            new String[]{String.valueOf(deckId)},
                            null
                    );
                    if (top != null && top.moveToFirst()) {
                        long topNoteId = top.getLong(0);
                        int topOrd = top.getInt(1);
                        deck.put(CommonIdentifiers.FIELD_NOTE_ID, topNoteId);
                        deck.put(CommonIdentifiers.FIELD_CARD_ORD, topOrd);

                        Uri cardUri = Uri.withAppendedPath(
                                Uri.withAppendedPath(NOTES_URI, Long.toString(topNoteId)), "cards");
                        cardUri = Uri.withAppendedPath(cardUri, Integer.toString(topOrd));
                        Cursor cardCursor = null;
                        try {
                            cardCursor = getContentResolver().query(cardUri, null, null, null, null);
                            if (cardCursor != null && cardCursor.moveToFirst()) {
                                int colT = cardCursor.getColumnIndex("type");
                                int colQ = cardCursor.getColumnIndex("queue");
                                int colF = cardCursor.getColumnIndex("flags");
                                if (colT >= 0) deck.put(CommonIdentifiers.FIELD_CARD_TYPE, cardCursor.getInt(colT));
                                if (colQ >= 0) deck.put(CommonIdentifiers.FIELD_CARD_QUEUE, cardCursor.getInt(colQ));
                                if (colF >= 0) deck.put(CommonIdentifiers.FIELD_CARD_FLAGS, cardCursor.getInt(colF));
                            }
                        } finally {
                            if (cardCursor != null) cardCursor.close();
                        }

                        JSONObject dbDetails = getCardDetails(topNoteId, topOrd);
                        if (dbDetails != null) {
                            for (java.util.Iterator<String> it = dbDetails.keys(); it.hasNext();) {
                                String k = it.next();
                                deck.put(k, dbDetails.get(k));
                            }
                        } else {
                            Log.w(TAG, "handleDecksRequest: getCardDetails returned null for " + topNoteId + "/" + topOrd + " deck=" + deckId);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (top != null) top.close();
                }

                decks.put(deck);
            }
            cursor.close();
            Log.d(TAG, "Sending " + decks.length() + " decks");
            serviceLog("Sending " + decks.length() + " decks via " + CommonIdentifiers.P2W_COLLECTION_LIST);
            fireMessage(CommonIdentifiers.P2W_COLLECTION_LIST, decks.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error fetching decks: " + e.getMessage(), e);
            serviceLog("Error fetching decks: " + e.getMessage());
        }
    }

    private void handleDeckSelect(String data) {
        if (!hasAnkiPerm()) return;
        mSingleThread.execute(() -> {
            try {
                JSONObject json = new JSONObject(data);
                long newDeckId = json.getLong(CommonIdentifiers.FIELD_DECK_ID);
                if (newDeckId <= 0) {
                    serviceLog("Invalid deck ID: " + newDeckId + " — ignoring");
                    return;
                }
                serviceLog("Switching deck from " + mCurrentDeckId + " to " + newDeckId);
                ContentValues deckSel = new ContentValues();
                deckSel.put("deck_id", newDeckId);
                getContentResolver().update(SELECTED_DECK_URI, deckSel, null, null);
                serviceLog("Updated selected deck in AnkiDroid to " + newDeckId);
                mCurrentDeckId = newDeckId;
                storeLastDeck(newDeckId);
                mWatchCache.clear();
                updateSnapshot();
                int count = json.optInt(CommonIdentifiers.FIELD_COUNT, 3);
                serviceLog("Deck switched to " + mCurrentDeckId + ", fetching " + count + " cards");
                fetchAndSendCards(count);
            } catch (Exception e) {
                Log.e(TAG, "Error selecting deck: " + e.getMessage(), e);
                serviceLog("Error selecting deck: " + e.getMessage());
            }
        });
    }

    private void storeLastDeck(long deckId) {
        try {
            Uri deckUri = Uri.withAppendedPath(DECKS_URI, Long.toString(deckId));
            Cursor deckCursor = getContentResolver().query(deckUri, new String[]{"deck_name"}, null, null, null);
            String deckName = "";
            if (deckCursor != null) {
                if (deckCursor.moveToFirst()) {
                    deckName = deckCursor.getString(deckCursor.getColumnIndexOrThrow("deck_name"));
                }
                deckCursor.close();
            }
            getSharedPreferences("anki_minimal_settings", MODE_PRIVATE)
                    .edit()
                    .putLong(CommonIdentifiers.CONFIG_LAST_DECK_ID, deckId)
                    .putString(CommonIdentifiers.CONFIG_LAST_DECK_NAME, deckName)
                    .apply();
            serviceLog("Stored last deck " + deckId + " (" + deckName + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error storing last deck: " + e.getMessage(), e);
        }
    }

    private void handleLastDeckRequest() {
        try {
            SharedPreferences prefs = getSharedPreferences("anki_minimal_settings", MODE_PRIVATE);
            long deckId = prefs.getLong(CommonIdentifiers.CONFIG_LAST_DECK_ID, -1);
            String deckName = prefs.getString(CommonIdentifiers.CONFIG_LAST_DECK_NAME, "");
            JSONObject resp = new JSONObject();
            if (deckId > 0) {
                resp.put(CommonIdentifiers.FIELD_DECK_ID, deckId);
                resp.put(CommonIdentifiers.FIELD_DECK_NAME, deckName);
            }
            fireMessage(CommonIdentifiers.P2W_LAST_DECK, resp.toString());
            serviceLog("Sent last deck: " + (deckId > 0 ? deckId + " (" + deckName + ")" : "(none)"));
        } catch (Exception e) {
            Log.e(TAG, "Error sending last deck: " + e.getMessage(), e);
            serviceLog("Error sending last deck: " + e.getMessage());
        }
    }

    private void handleCardAnswer(String data) {
        if (!hasAnkiPerm()) return;
        mSingleThread.execute(() -> {
            try {
                JSONObject json = new JSONObject(data);
                long noteId = json.getLong(CommonIdentifiers.FIELD_NOTE_ID);
                int cardOrd = json.getInt(CommonIdentifiers.FIELD_CARD_ORD);
                int ease = json.getInt(CommonIdentifiers.FIELD_EASE);

                synchronized (mPendingAnswers) {
                    mPendingAnswers.add(json);
                }

                serviceLog("Queued answer note_id=" + noteId + " ord=" + cardOrd + " ease=" + ease + " (pending=" + mPendingAnswers.size() + ")");
                flushAnswers();
            } catch (Exception e) {
                Log.e(TAG, "Error answering card", e);
                serviceLog("Error answering card: " + e.getMessage());
            }
        });
    }

    private void handleCardRequest(String data) {
        if (!hasAnkiPerm()) return;
        mSingleThread.execute(() -> {
            try {
                JSONObject json = new JSONObject(data);
                if (json.has(CommonIdentifiers.FIELD_DECK_ID)) {
                    long newDeckId = json.getLong(CommonIdentifiers.FIELD_DECK_ID);
                    if (newDeckId <= 0) {
                        serviceLog("Card request had invalid deck ID: " + newDeckId + " — ignoring");
                    } else {
                        if (mCurrentDeckId != newDeckId) {
                            serviceLog("Card request changed deck from " + mCurrentDeckId + " to " + newDeckId);
                            mCurrentDeckId = newDeckId;
                        }
                    }
                }
                int count = json.optInt(CommonIdentifiers.FIELD_COUNT, 1);
                if (mCurrentDeckId <= 0) {
                    serviceLog("Card request skipped — no valid deck selected (id=" + mCurrentDeckId + ")");
                    return;
                }
                fetchAndSendCards(count);
            } catch (Exception e) {
                Log.e(TAG, "Error requesting cards", e);
                serviceLog("Error requesting cards: " + e.getMessage());
            }
        });
    }

    private void fetchAndSendCards(int count) {
        try {
            if (mCurrentDeckId <= 0) {
                serviceLog("fetchAndSendCards skipped — deck=" + mCurrentDeckId);
                return;
            }

            serviceLog("Querying schedule for deck " + mCurrentDeckId + " count=" + count);

            List<JSONObject> cards = new ArrayList<>();

            Cursor deckVerify = null;
            try {
                Uri deckUri = Uri.withAppendedPath(DECKS_URI, Long.toString(mCurrentDeckId));
                deckVerify = getContentResolver().query(deckUri, new String[]{"deck_name"}, null, null, null);
                if (deckVerify == null || !deckVerify.moveToFirst()) {
                    serviceLog("Deck " + mCurrentDeckId + " NOT FOUND — clearing cache, sending NO_MORE_CARDS");
                    mWatchCache.clear();
                    updateSnapshot();
                    fireMessage(CommonIdentifiers.P2W_NO_MORE_CARDS, "{}");
                    if (deckVerify != null) deckVerify.close();
                    return;
                }
            } catch (Exception e) {
                serviceLog("Deck verify threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + " — proceeding anyway");
            } finally {
                if (deckVerify != null) deckVerify.close();
            }

            Cursor reviewCursor = getContentResolver().query(
                    SCHEDULE_URI,
                    new String[]{"note_id", "ord"},
                    "limit=?, deckID=?",
                    new String[]{String.valueOf(count), String.valueOf(mCurrentDeckId)},
                    null
            );

            if (reviewCursor == null) {
                Log.w(TAG, "Schedule cursor is null");
                serviceLog("Schedule cursor is null");
                return;
            }

            while (reviewCursor.moveToNext()) {
                long noteId = reviewCursor.getLong(
                        reviewCursor.getColumnIndexOrThrow("note_id"));
                int cardOrd = reviewCursor.getInt(
                        reviewCursor.getColumnIndexOrThrow("ord"));

                String key = cardKey(noteId, cardOrd);
                if (mWatchCache.contains(key)) {
                    serviceLog("Skipping card " + noteId + "/" + cardOrd + " (already in watch cache)");
                    continue;
                }

                Uri cardUri = Uri.withAppendedPath(
                        Uri.withAppendedPath(NOTES_URI, Long.toString(noteId)), "cards");
                cardUri = Uri.withAppendedPath(cardUri, Integer.toString(cardOrd));

                Cursor cardCursor = getContentResolver().query(cardUri, null, null, null, null);
                if (cardCursor != null && cardCursor.moveToFirst()) {
                    int colQ = cardCursor.getColumnIndex("question");
                    if (colQ < 0) colQ = cardCursor.getColumnIndex("question_simple");
                    int colA = cardCursor.getColumnIndex("answer");
                    if (colA < 0) colA = cardCursor.getColumnIndex("answer_pure");

                    String question = colQ >= 0 ? cardCursor.getString(colQ) : "";
                    String answer = colA >= 0 ? cardCursor.getString(colA) : "";

                    JSONObject card = new JSONObject();
                    card.put(CommonIdentifiers.FIELD_NOTE_ID, noteId);
                    card.put(CommonIdentifiers.FIELD_CARD_ORD, cardOrd);
                    card.put(CommonIdentifiers.FIELD_QUESTION, question);
                    card.put(CommonIdentifiers.FIELD_ANSWER, answer);

                    int colType = cardCursor.getColumnIndex("type");
                    int colQueue = cardCursor.getColumnIndex("queue");
                    int colFlags = cardCursor.getColumnIndex("flags");
                    if (colType >= 0) card.put(CommonIdentifiers.FIELD_CARD_TYPE, cardCursor.getInt(colType));
                    if (colQueue >= 0) card.put(CommonIdentifiers.FIELD_CARD_QUEUE, cardCursor.getInt(colQueue));
                    if (colFlags >= 0) card.put(CommonIdentifiers.FIELD_CARD_FLAGS, cardCursor.getInt(colFlags));

                    JSONObject dbDetails = getCardDetails(noteId, cardOrd);
                    if (dbDetails != null) {
                        for (java.util.Iterator<String> it = dbDetails.keys(); it.hasNext();) {
                            String k = it.next();
                            card.put(k, dbDetails.get(k));
                        }
                    }

                    cards.add(card);
                    serviceLog("Fetched card " + noteId + "/" + cardOrd);
                }
                if (cardCursor != null) cardCursor.close();
            }
            reviewCursor.close();

            if (cards.isEmpty()) {
                updateSnapshot();
                serviceLog("NO MORE CARDS for deck " + mCurrentDeckId
                        + " (cache=" + mWatchCache.size() + ")");
                fireMessage(CommonIdentifiers.P2W_NO_MORE_CARDS, "{}");
                return;
            }

            JSONArray cardsArray = new JSONArray();
            for (JSONObject card : cards) {
                card.put(CommonIdentifiers.FIELD_DECK_ID, mCurrentDeckId);
                card.put("remaining", cards.size() - cardsArray.length() - 1);
                mWatchCache.add(cardKey(card.optLong(CommonIdentifiers.FIELD_NOTE_ID, -1),
                        card.optInt(CommonIdentifiers.FIELD_CARD_ORD, -1)));
                cardsArray.put(card);
            }
            fireMessage(CommonIdentifiers.P2W_RESPOND_CARD,
                    new JSONObject().put("cards", cardsArray).toString());
            updateSnapshot();
            serviceLog("Sent " + cards.size() + " cards for deck " + mCurrentDeckId);
        } catch (Exception e) {
            Log.e(TAG, "Error fetching cards", e);
            serviceLog("Error fetching cards: " + e.getMessage());
        }
    }

    private void startFlushRetryTimer() {
        if (sFlushTimerRunning.compareAndSet(false, true)) {
            serviceLog("Starting flush retry timer (5s)");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                sFlushTimerRunning.set(false);
                mSingleThread.execute(() -> flushAnswers());
            }, 5000);
        } else {
            serviceLog("Flush retry timer already running");
        }
    }

    private void flushAnswers() {
        if (!hasAnkiPerm()) return;
        synchronized (mPendingAnswers) {
            if (mPendingAnswers.isEmpty()) {
                serviceLog("flushAnswers: nothing pending");
                return;
            }
            serviceLog("flushAnswers: " + mPendingAnswers.size() + " pending");
            List<JSONObject> remaining = new ArrayList<>();
            int successes = 0;
            int failures = 0;
            int dropped = 0;
            for (JSONObject ans : mPendingAnswers) {
                try {
                    long noteId = ans.getLong(CommonIdentifiers.FIELD_NOTE_ID);
                    int cardOrd = ans.getInt(CommonIdentifiers.FIELD_CARD_ORD);
                    int ease = ans.getInt(CommonIdentifiers.FIELD_EASE);

                    if (!isMatchingTopCard(noteId, cardOrd)) {
                        dropped++;
                        serviceLog("dropped " + noteId + "/" + cardOrd + " — not current top card, re-syncing watch");
                        continue;
                    }

                    boolean bury = ease == 1
                            && getSharedPreferences("anki_minimal_settings", MODE_PRIVATE)
                                    .getBoolean(CommonIdentifiers.CONFIG_BURY_ON_FAIL, false);
                    ContentValues values = new ContentValues();
                    values.put("note_id", noteId);
                    values.put("ord", cardOrd);
                    if (bury) {
                        values.put("buried", 1);
                    } else {
                        values.put("answer_ease", ease);
                        values.put("time_taken", 1000);
                    }
                    int rows = getContentResolver().update(SCHEDULE_URI, values, null, null);
                    String ts = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
                    synchronized (sFlushLog) {
                        String verb = bury ? "bury" : "ease=" + ease;
                        sFlushLog.add(0, ts + " flush: " + noteId + "/" + cardOrd + " rows=" + rows + " " + verb);
                        if (sFlushLog.size() > 20) sFlushLog.remove(sFlushLog.size() - 1);
                    }
                    if (rows > 0) {
                        successes++;
                        mWatchCache.remove(cardKey(noteId, cardOrd));
                        serviceLog("flush succeeded for " + noteId + "/" + cardOrd + " rows=" + rows);
                        fireMessage(CommonIdentifiers.P2W_ANSWER_ACK,
                                "{\"noteId\":" + noteId + ",\"cardOrd\":" + cardOrd + "}");
                    } else {
                        failures++;
                        remaining.add(ans);
                        serviceLog("flush FAILED rows=" + rows + " for " + noteId + "/" + cardOrd);
                    }
                } catch (Exception e) {
                    failures++;
                    remaining.add(ans);
                    Log.e(TAG, "flush error: " + e.getMessage(), e);
                    serviceLog("flush error: " + e.getMessage());
                }
            }
            mPendingAnswers.clear();
            mPendingAnswers.addAll(remaining);
            if (!mPendingAnswers.isEmpty()) {
                serviceLog("flush: " + successes + " ok, " + failures + " fail, " + dropped
                        + " dropped — " + mPendingAnswers.size() + " remain, retrying");
                startFlushRetryTimer();
            } else {
                serviceLog("flush: " + successes + " ok, " + failures + " fail, " + dropped
                        + " dropped — queue empty");
            }
            if (successes > 0 || dropped > 0) {
                sendTopCardChanged();
            }
        }
        updateSnapshot();
    }

    private boolean isMatchingTopCard(long noteId, int cardOrd) {
        if (mCurrentDeckId <= 0) return true;
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    SCHEDULE_URI,
                    new String[]{"note_id", "ord"},
                    "limit=1, deckID=?",
                    new String[]{String.valueOf(mCurrentDeckId)},
                    null
            );
            if (c != null && c.moveToFirst()) {
                long topNoteId = c.getLong(c.getColumnIndexOrThrow("note_id"));
                int topOrd = c.getInt(c.getColumnIndexOrThrow("ord"));
                return topNoteId == noteId && topOrd == cardOrd;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "isMatchingTopCard error: " + e.getMessage());
            return true;
        } finally {
            if (c != null) c.close();
        }
    }

    private String getCollectionDbPath() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Log.w(TAG, "getCollectionDbPath: MANAGE_EXTERNAL_STORAGE not granted");
            serviceLog("getCollectionDbPath: MANAGE_EXTERNAL_STORAGE not granted");
            return null;
        }
        String[] paths = {
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/AnkiDroid/collection.anki2",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2",
            "/storage/emulated/0/AnkiDroid/collection.anki2",
            "/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2"
        };
        for (String p : paths) {
            if (new java.io.File(p).exists()) {
                return p;
            }
        }
        return null;
    }

    private JSONObject getCardDetails(long noteId, int cardOrd) {
        String dbPath = getCollectionDbPath();
        if (dbPath == null) {
            Log.w(TAG, "getCardDetails: collection.anki2 not found at any path");
            return null;
        }
        serviceLog("getCardDetails: using DB path " + dbPath);
        SQLiteDatabase db = null;
        Cursor c = null;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                c = db.rawQuery("SELECT type, queue, flags, due, ivl, factor, lapses, left, odid FROM cards WHERE nid=? AND ord=?",
                        new String[]{String.valueOf(noteId), String.valueOf(cardOrd)});
                if (c != null && c.moveToFirst()) {
                    JSONObject details = new JSONObject();
                    details.put(CommonIdentifiers.FIELD_CARD_TYPE, c.getInt(0));
                    details.put(CommonIdentifiers.FIELD_CARD_QUEUE, c.getInt(1));
                    details.put(CommonIdentifiers.FIELD_CARD_FLAGS, c.getInt(2));
                    details.put(CommonIdentifiers.FIELD_CARD_DUE, c.getInt(3));
                    details.put(CommonIdentifiers.FIELD_CARD_INTERVAL, c.getInt(4));
                    details.put(CommonIdentifiers.FIELD_CARD_FACTOR, c.getInt(5));
                    details.put(CommonIdentifiers.FIELD_CARD_LAPSES, c.getInt(6));
                    details.put(CommonIdentifiers.FIELD_CARD_LEFT, c.getInt(7));
                    details.put("odid", c.getLong(8));
                    return details;
                }

            } catch (Exception e) {
                Log.w(TAG, "getCardDetails attempt " + attempts + "/3 error for " + noteId + "/" + cardOrd + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (attempts < 3) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            } finally {
                if (c != null) { c.close(); c = null; }
                if (db != null) { db.close(); db = null; }
            }
        }
        Log.w(TAG, "getCardDetails: all 3 attempts failed for " + noteId + "/" + cardOrd);
        return null;
    }

    private void sendTopCardChanged() {
        if (mCurrentDeckId <= 0) return;
        try {
            Cursor top = getContentResolver().query(
                    SCHEDULE_URI,
                    new String[]{"note_id", "ord"},
                    "limit=1, deckID=?",
                    new String[]{String.valueOf(mCurrentDeckId)},
                    null
            );
            if (top == null || !top.moveToFirst()) {
                if (top != null) top.close();
                serviceLog("sendTopCardChanged: no top card (empty deck)");
                return;
            }
            long noteId = top.getLong(top.getColumnIndexOrThrow("note_id"));
            int cardOrd = top.getInt(top.getColumnIndexOrThrow("ord"));
            top.close();

            Uri cardUri = Uri.withAppendedPath(
                    Uri.withAppendedPath(NOTES_URI, Long.toString(noteId)), "cards");
            cardUri = Uri.withAppendedPath(cardUri, Integer.toString(cardOrd));

            Cursor cardCursor = getContentResolver().query(cardUri, null, null, null, null);
            if (cardCursor == null || !cardCursor.moveToFirst()) {
                if (cardCursor != null) cardCursor.close();
                serviceLog("sendTopCardChanged: no card data for " + noteId + "/" + cardOrd);
                return;
            }

            int colQ = cardCursor.getColumnIndex("question");
            if (colQ < 0) colQ = cardCursor.getColumnIndex("question_simple");
            int colA = cardCursor.getColumnIndex("answer");
            if (colA < 0) colA = cardCursor.getColumnIndex("answer_pure");

            String question = colQ >= 0 ? cardCursor.getString(colQ) : "";
            String answer = colA >= 0 ? cardCursor.getString(colA) : "";

            int colType = cardCursor.getColumnIndex("type");
            int colQueue = cardCursor.getColumnIndex("queue");
            int colFlags = cardCursor.getColumnIndex("flags");
            int cardType = colType >= 0 ? cardCursor.getInt(colType) : -1;
            int cardQueue = colQueue >= 0 ? cardCursor.getInt(colQueue) : -1;
            int cardFlags = colFlags >= 0 ? cardCursor.getInt(colFlags) : 0;

            cardCursor.close();

            JSONObject card = new JSONObject();
            card.put(CommonIdentifiers.FIELD_NOTE_ID, noteId);
            card.put(CommonIdentifiers.FIELD_CARD_ORD, cardOrd);
            card.put(CommonIdentifiers.FIELD_QUESTION, question);
            card.put(CommonIdentifiers.FIELD_ANSWER, answer);
            card.put(CommonIdentifiers.FIELD_DECK_ID, mCurrentDeckId);
            if (colType >= 0) card.put(CommonIdentifiers.FIELD_CARD_TYPE, cardType);
            if (colQueue >= 0) card.put(CommonIdentifiers.FIELD_CARD_QUEUE, cardQueue);
            if (colFlags >= 0) card.put(CommonIdentifiers.FIELD_CARD_FLAGS, cardFlags);

            JSONObject dbDetails = getCardDetails(noteId, cardOrd);
            if (dbDetails != null) {
                for (java.util.Iterator<String> it = dbDetails.keys(); it.hasNext();) {
                    String k = it.next();
                    card.put(k, dbDetails.get(k));
                }
            }

            fireMessage(CommonIdentifiers.P2W_TOP_CARD_CHANGED, card.toString());
            serviceLog("Sent top card changed: " + noteId + "/" + cardOrd);
        } catch (Exception e) {
            Log.e(TAG, "Error sending top card", e);
            serviceLog("Error sending top card: " + e.getMessage());
        }
    }

    private void fireMessage(final String path, final String data) {
        serviceLog("fireMessage: " + path + " (connected=" + mGoogleApiClient.isConnected() + ")");
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
                    serviceLog("Connection suspended (fireMessage): " + cause);
                }
            });
        }
    }

    private void sendMessageAsync(final String path, final String data) {
        new Thread(() -> {
            try {
                NodeApi.GetConnectedNodesResult nodes =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                                .await(15, java.util.concurrent.TimeUnit.SECONDS);
                if (nodes == null) {
                    serviceLog("getConnectedNodes TIMED OUT for " + path);
                    return;
                }
                Log.d(TAG, nodes.getNodes().size() + " connected nodes for " + path);
                serviceLog("Sending " + path + " to " + nodes.getNodes().size() + " nodes");
                for (Node node : nodes.getNodes()) {
                    MessageApi.SendMessageResult result = Wearable.MessageApi.sendMessage(
                            mGoogleApiClient, node.getId(), path, data.getBytes())
                            .await(15, java.util.concurrent.TimeUnit.SECONDS);
                    Log.d(TAG, "Sent " + path + " to " + node.getDisplayName() + ": "
                            + (result == null ? "TIMEOUT"
                               : result.getStatus().isSuccess() ? "OK" : result.getStatus()));
                    serviceLog("Send " + path + " to " + node.getDisplayName() + ": "
                            + (result == null ? "TIMEOUT"
                               : result.getStatus().isSuccess() ? "OK" : "FAIL"));
                }
                if (nodes.getNodes().isEmpty()) {
                    serviceLog("WARNING: 0 connected nodes for " + path + " - watch may not receive");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending " + path + ": " + e.getMessage(), e);
                serviceLog("Error sending " + path + ": " + e.getMessage());
            }
        }).start();
    }
}
