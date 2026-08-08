package com.minimal.anki.wear;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.minimal.anki.shared.CommonIdentifiers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DeckSelectFragment extends Fragment implements AdapterView.OnItemClickListener {

    private static final long RETRY_UI_DELAY = 300;
    private static final long AUTO_RETRY_INTERVAL = 1000;
    private static final long MAX_RETRY_DURATION = 8000;

    private static final String ARG_LAST_DECK_ID = "last_deck_id";
    private static final String ARG_LAST_DECK_NAME = "last_deck_name";
    private static final String ARG_AUTO_SELECT = "auto_select";

    private List<Deck> mDecks = new ArrayList<>();
    private DeckAdapter mAdapter;
    private ListView mListView;
    private OnDeckSelectedListener mListener;
    private View mLoadingLayout;
    private TextView mLoadingText;
    private ProgressBar mProgressBar;
    private Handler mAutoRetryHandler;
    private boolean mRetryUIShown = false;
    private long mRetryStartTime;
    private boolean mAutoRetryActive = false;

    private long mAutoSelectLastDeckId = -1;
    private String mAutoSelectLastDeckName = "";
    private boolean mAutoSelectEnabled = false;
    private boolean mAutoSelectDone = false;
    private boolean mNoLastDeckMessageShown = false;
    private boolean mLastDeckRequestSent = false;

    public interface OnDeckSelectedListener {
        void onDeckSelected(long deckId, String deckName);
        void onRetryDecks();
        void onRequestLastDeck();
    }

    public static DeckSelectFragment newInstance(long lastDeckId, String lastDeckName, boolean autoSelectLastDeck) {
        DeckSelectFragment fragment = new DeckSelectFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_LAST_DECK_ID, lastDeckId);
        args.putString(ARG_LAST_DECK_NAME, lastDeckName == null ? "" : lastDeckName);
        args.putBoolean(ARG_AUTO_SELECT, autoSelectLastDeck);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapter = new DeckAdapter(mDecks);
        Bundle args = getArguments();
        if (args != null) {
            mAutoSelectLastDeckId = args.getLong(ARG_LAST_DECK_ID, -1);
            mAutoSelectLastDeckName = args.getString(ARG_LAST_DECK_NAME, "");
            mAutoSelectEnabled = args.getBoolean(ARG_AUTO_SELECT, false);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.frag_deck_select, container, false);
        mLoadingLayout = view.findViewById(R.id.layout_loading);
        mLoadingText = view.findViewById(R.id.tv_loading);
        mProgressBar = view.findViewById(R.id.progress_bar);

        mLoadingLayout.setOnClickListener(v -> {
            if (mRetryUIShown) {
                mLoadingText.setText("Loading decks...");
                mProgressBar.setVisibility(View.VISIBLE);
                if (mListener != null) mListener.onRetryDecks();
            }
        });

        startAutoRetry();

        mListView = view.findViewById(android.R.id.list);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(this);
        return view;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof OnDeckSelectedListener) {
            mListener = (OnDeckSelectedListener) activity;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mListener != null && position < mDecks.size()) {
            Deck deck = mDecks.get(position);
            mListener.onDeckSelected(deck.id, deck.name);
        }
    }

    private void startAutoRetry() {
        cancelAutoRetry();
        mAutoRetryHandler = new Handler(Looper.getMainLooper());
        mAutoRetryActive = true;
        mRetryUIShown = false;
        mRetryStartTime = System.currentTimeMillis();

        mAutoRetryHandler.postDelayed(() -> {
            if (mAutoRetryActive && mLoadingLayout != null
                    && mLoadingLayout.getVisibility() == View.VISIBLE) {
                mRetryUIShown = true;
                mLoadingText.setText("No response. Tap to retry.");
            }
        }, RETRY_UI_DELAY);

        scheduleNextAutoRetry();

        mAutoRetryHandler.postDelayed(() -> {
            mAutoRetryActive = false;
            if (mLoadingLayout != null
                    && mLoadingLayout.getVisibility() == View.VISIBLE) {
                if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
            }
        }, MAX_RETRY_DURATION);
    }

    private void scheduleNextAutoRetry() {
        if (!mAutoRetryActive || mAutoRetryHandler == null) return;
        long elapsed = System.currentTimeMillis() - mRetryStartTime;
        if (elapsed >= MAX_RETRY_DURATION) return;

        mAutoRetryHandler.postDelayed(() -> {
            if (!mAutoRetryActive) return;
            long now = System.currentTimeMillis();
            if (now - mRetryStartTime >= MAX_RETRY_DURATION) {
                mAutoRetryActive = false;
                if (mLoadingLayout != null
                        && mLoadingLayout.getVisibility() == View.VISIBLE) {
                    if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                }
                return;
            }
            if (mListener != null) mListener.onRetryDecks();
            scheduleNextAutoRetry();
        }, AUTO_RETRY_INTERVAL);
    }

    private void cancelAutoRetry() {
        if (mAutoRetryHandler != null) {
            mAutoRetryHandler.removeCallbacksAndMessages(null);
            mAutoRetryHandler = null;
        }
        mAutoRetryActive = false;
    }

    private void startAutoSelectPolling() {
        if (!mAutoSelectEnabled) return;
        if (mAutoSelectDone) return;
        if (mAutoSelectLastDeckId <= 0) {
            requestLastDeckFromPhone();
            return;
        }
        selectLastDeckNow();
    }

    private void selectLastDeckNow() {
        if (mAutoSelectDone) return;
        mAutoSelectDone = true;
        if (mListener != null) {
            mListener.onDeckSelected(mAutoSelectLastDeckId, mAutoSelectLastDeckName);
        }
    }

    private void requestLastDeckFromPhone() {
        if (mLastDeckRequestSent) return;
        mLastDeckRequestSent = true;
        if (mLoadingLayout != null && mLoadingText != null) {
            mLoadingText.setText("Requesting last deck...");
            if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        }
        if (mListener != null) {
            mListener.onRequestLastDeck();
        }
    }

    public void onLastDeckReceived(long deckId, String deckName) {
        if (!mAutoSelectEnabled) return;
        if (deckId > 0) {
            mAutoSelectLastDeckId = deckId;
            mAutoSelectLastDeckName = deckName == null ? "" : deckName;
            selectLastDeckNow();
        } else {
            showNoLastDeckMessage();
        }
    }

    private void showNoLastDeckMessage() {
        if (mNoLastDeckMessageShown) return;
        mNoLastDeckMessageShown = true;
        cancelAutoRetry();
        if (mLoadingLayout != null && mLoadingText != null) {
            mLoadingText.setText("No last deck saved");
            if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mLoadingLayout != null && mLoadingLayout.getVisibility() == View.VISIBLE) {
            startAutoRetry();
        }
        startAutoSelectPolling();
    }

    @Override
    public void onPause() {
        super.onPause();
        cancelAutoRetry();
    }

    public void onDecksReceived(JSONArray decks) {
        cancelAutoRetry();
        mDecks.clear();
        for (int i = 0; i < decks.length(); i++) {
            try {
                JSONObject obj = decks.getJSONObject(i);
                mDecks.add(new Deck(
                        obj.getLong(CommonIdentifiers.FIELD_DECK_ID),
                        obj.getString(CommonIdentifiers.FIELD_DECK_NAME),
                        obj.optInt(CommonIdentifiers.FIELD_DECK_NEW, 0),
                        obj.optInt(CommonIdentifiers.FIELD_DECK_LEARN, 0),
                        obj.optInt(CommonIdentifiers.FIELD_DECK_REVIEW, 0),
                        obj.has(CommonIdentifiers.FIELD_NOTE_ID) ? obj.getLong(CommonIdentifiers.FIELD_NOTE_ID) : -1,
                        obj.has(CommonIdentifiers.FIELD_CARD_ORD) ? obj.getInt(CommonIdentifiers.FIELD_CARD_ORD) : -1,
                        obj.optInt(CommonIdentifiers.FIELD_CARD_TYPE, -1),
                        obj.optInt(CommonIdentifiers.FIELD_CARD_FLAGS, 0)
                ));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        mAdapter.notifyDataSetChanged();
        if (mLoadingLayout != null) {
            mLoadingLayout.setVisibility(View.GONE);
        }
        if (mListView != null) {
            mListView.post(() -> mListView.requestFocus());
        }
    }

    private static class Deck {
        final long id;
        final String name;
        final int newCount;
        final int learnCount;
        final int reviewCount;
        final long topNoteId;
        final int topCardOrd;
        final int topCardType;
        final int topCardFlags;

        Deck(long id, String name, int newCount, int learnCount, int reviewCount, long topNoteId, int topCardOrd, int topCardType, int topCardFlags) {
            this.id = id;
            this.name = name;
            this.newCount = newCount;
            this.learnCount = learnCount;
            this.reviewCount = reviewCount;
            this.topNoteId = topNoteId;
            this.topCardOrd = topCardOrd;
            this.topCardType = topCardType;
            this.topCardFlags = topCardFlags;
        }
    }

    private static class DeckAdapter extends BaseAdapter {
        private final List<Deck> decks;

        DeckAdapter(List<Deck> decks) {
            this.decks = decks;
        }

        @Override
        public int getCount() {
            return decks.size();
        }

        @Override
        public Object getItem(int position) {
            return decks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View view, @NonNull ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.list_item_deck, parent, false);
            }
            Deck deck = decks.get(position);
            ((TextView) view.findViewById(R.id.tv_deck_name)).setText(deck.name);
            TextView tvCounts = view.findViewById(R.id.tv_deck_counts);
            String counts = deck.newCount + "N  " + deck.learnCount + "L  " + deck.reviewCount + "R";
            tvCounts.setText(counts);
            TextView tvTop = view.findViewById(R.id.tv_deck_top);
            if (deck.topNoteId > 0) {
                String topText = "top: " + deck.topNoteId + "/" + deck.topCardOrd;
                topText += " t=" + deck.topCardType + " f=" + deck.topCardFlags;
                tvTop.setText(topText);
            } else {
                tvTop.setText("(empty)");
            }
            return view;
        }
    }
}
