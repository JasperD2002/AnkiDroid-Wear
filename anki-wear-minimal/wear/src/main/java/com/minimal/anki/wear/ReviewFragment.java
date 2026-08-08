package com.minimal.anki.wear;

import android.app.Fragment;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;

import java.util.regex.Pattern;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import static com.minimal.anki.shared.CommonIdentifiers.FIELD_ANSWER;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_CARD_FLAGS;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_CARD_ORD;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_CARD_QUEUE;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_CARD_TYPE;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_EASE;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_NOTE_ID;
import static com.minimal.anki.shared.CommonIdentifiers.FIELD_QUESTION;

public class ReviewFragment extends Fragment {

    private static final String TAG = "ReviewFragment";

    private View mLoadingCardLayout;
    private TextView mQuestionText;
    private TextView mAnswerText;
    private TextView mCardTypeText;
    private TextView mCardFlagsText;
    private Button mShowAnswerBtn;
    private LinearLayout mEaseLayout;
    private ScrollView mScrollView;
    private View mStatusBar;
    private View mFlagBar;
    private Button mBackButton;
    private Button mAgainButton;
    private Button mGoodButton;
    private TextView mWaitingText;

    private long mNoteId;
    private int mCardOrd;
    private String mQuestion;
    private String mAnswer;
    private boolean mAnswerShown = false;
    private boolean mAnswerPendingAck = false;
    private boolean mShownCard = false;
    private long mPendingAckNoteId = -1;
    private int mPendingAckCardOrd = -1;
    private final android.os.Handler mPendingAckTimeout = new android.os.Handler();

    private Callbacks mCallbacks;

    public interface Callbacks {
        void onAnswerCard(long noteId, int cardOrd, int ease);
        void onRequestCards();
        int getQueueSize();
        void onReviewFinished();
        void onBackToDecks();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (getActivity() != null) {
            getActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        View view = inflater.inflate(R.layout.frag_review, container, false);
        mLoadingCardLayout = view.findViewById(R.id.layout_loading_card);
        mScrollView = view.findViewById(R.id.scroll_view);
        mScrollView.requestFocus();
        mQuestionText = view.findViewById(R.id.tv_question);
        mAnswerText = view.findViewById(R.id.tv_answer);
        mCardTypeText = view.findViewById(R.id.tv_card_type);
        mCardFlagsText = view.findViewById(R.id.tv_card_flags);
        mStatusBar = view.findViewById(R.id.view_status_bar);
        mFlagBar = view.findViewById(R.id.view_flag_bar);
        mShowAnswerBtn = view.findViewById(R.id.btn_show_answer);
        mEaseLayout = view.findViewById(R.id.layout_ease);
        mBackButton = view.findViewById(R.id.btn_back_decks);
        mAgainButton = view.findViewById(R.id.btn_again);
        mGoodButton = view.findViewById(R.id.btn_good);
        mWaitingText = view.findViewById(R.id.tv_waiting);

        mShowAnswerBtn.setOnClickListener(v -> showAnswer());
        view.findViewById(R.id.btn_back_decks).setOnClickListener(v -> {
            if (mCallbacks != null) mCallbacks.onBackToDecks();
        });

        view.findViewById(R.id.btn_again).setOnClickListener(v -> answer(1));
        view.findViewById(R.id.btn_good).setOnClickListener(v -> answer(3));

        if (getActivity() != null) {
            android.content.SharedPreferences prefs =
                    getActivity().getSharedPreferences("anki_settings", 0);
            int fsQ = prefs.getInt("font_size_q", 15);
            int fsA = prefs.getInt("font_size_a", 15);
            applyFontSizes(fsQ, fsA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mQuestionText.setTextLocale(java.util.Locale.JAPANESE);
            mAnswerText.setTextLocale(java.util.Locale.JAPANESE);
        }

        return view;
    }

    @Override
    public void onAttach(android.app.Activity activity) {
        super.onAttach(activity);
        if (activity instanceof Callbacks) {
            mCallbacks = (Callbacks) activity;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = null;
        if (getActivity() != null) {
            getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    public void setCard(JSONObject card) {
        try {
            mNoteId = card.getLong(FIELD_NOTE_ID);
            mCardOrd = card.getInt(FIELD_CARD_ORD);
            mQuestion = card.optString(FIELD_QUESTION, "");
            mAnswer = card.optString(FIELD_ANSWER, "");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing card JSON", e);
        }
        mShownCard = true;
        if (mLoadingCardLayout != null) {
            mLoadingCardLayout.setVisibility(View.GONE);
        }
        updateStatusDisplay(card);
        displayQuestion();
    }

    private void updateStatusDisplay(JSONObject card) {
        if (mCardTypeText != null && card.has(FIELD_CARD_TYPE)) {
            int type = card.optInt(FIELD_CARD_TYPE, -1);
            int queue = card.optInt(FIELD_CARD_QUEUE, -1);
            String label = cardTypeLabel(type, queue);
            mCardTypeText.setText(label);
            mCardTypeText.setVisibility(View.VISIBLE);
        } else if (mCardTypeText != null) {
            mCardTypeText.setVisibility(View.GONE);
        }
        if (mCardFlagsText != null && card.has(FIELD_CARD_FLAGS)) {
            int flags = card.optInt(FIELD_CARD_FLAGS, 0);
            mCardFlagsText.setText("flag " + flags);
            mCardFlagsText.setVisibility(View.VISIBLE);
        } else if (mCardFlagsText != null) {
            mCardFlagsText.setVisibility(View.GONE);
        }
        updateStatusBar(card);
        updateFlagBar(card);
    }

    private void updateFlagBar(JSONObject card) {
        if (mFlagBar == null) return;
        if (!card.has(FIELD_CARD_FLAGS)) {
            mFlagBar.setVisibility(View.GONE);
            return;
        }
        int flags = card.optInt(FIELD_CARD_FLAGS, 0);
        if (flags == 0) {
            mFlagBar.setVisibility(View.GONE);
            return;
        }
        int color;
        switch (flags) {
            case 1: color = 0xFFF44336; break; // red
            case 2: color = 0xFFFF9800; break; // orange
            case 3: color = 0xFF4CAF50; break; // green
            case 4: color = 0xFF2196F3; break; // blue
            case 5: color = 0xFFE91E63; break; // pink
            case 6: color = 0xFF009688; break; // turquoise
            case 7: color = 0xFF9C27B0; break; // purple
            default: color = 0xFFFFFFFF; break;
        }
        mFlagBar.setBackgroundColor(Color.argb(64, Color.red(color), Color.green(color), Color.blue(color)));
        mFlagBar.setVisibility(View.VISIBLE);
    }

    private void updateStatusBar(JSONObject card) {
        if (mStatusBar == null) return;
        if (!card.has(FIELD_CARD_TYPE)) {
            mStatusBar.setVisibility(View.GONE);
            return;
        }
        int type = card.optInt(FIELD_CARD_TYPE, -1);
        int queue = card.optInt(FIELD_CARD_QUEUE, -1);
        int color;
        if (queue == -1 || queue == -2 || queue == -3) {
            color = 0xFF9E9E9E; // gray for suspended/buried
        } else {
            switch (type) {
                case 0: color = 0xFF2196F3; break; // blue for new
                case 1: color = 0xFFFFC107; break; // yellow for learn
                case 2: color = 0xFF4CAF50; break; // green for review
                case 3: color = 0xFFF44336; break; // red for relearning
                default: color = 0xFF9E9E9E; break; // gray
            }
        }
        mStatusBar.setBackgroundColor(Color.argb(64, Color.red(color), Color.green(color), Color.blue(color)));
        mStatusBar.setVisibility(View.VISIBLE);
    }

    private static String cardTypeLabel(int type, int queue) {
        if (queue == -1) return "suspended";
        if (queue == -2) return "buried";
        if (queue == -3) return "buried";
        switch (type) {
            case 0: return "new";
            case 1: return "learn";
            case 2: return "review";
            case 3: return "relearn";
            default: return "card";
        }
    }

    private void displayQuestion() {
        mAnswerShown = false;
        mQuestionText.setText(Html.fromHtml(mQuestion));
        mQuestionText.setVisibility(View.VISIBLE);
        mAnswerText.setVisibility(View.GONE);
        mShowAnswerBtn.setVisibility(View.VISIBLE);
        mEaseLayout.setVisibility(View.GONE);
        mScrollView.scrollTo(0, 0);
    }

    private void showAnswer() {
        mAnswerShown = true;
        String cleanAnswer = mAnswer.replaceFirst("^\\s*" + Pattern.quote(mQuestion), "");
        cleanAnswer = cleanAnswer
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\bExpression\\b", "")
                .trim();
        mQuestionText.setVisibility(View.VISIBLE);
        mAnswerText.setText(Html.fromHtml(cleanAnswer));
        mAnswerText.setVisibility(View.VISIBLE);
        mShowAnswerBtn.setVisibility(View.GONE);
        mEaseLayout.setVisibility(View.VISIBLE);
        mScrollView.post(() -> {
            mScrollView.requestFocus();
            mScrollView.scrollTo(0, mAnswerText.getTop());
        });
    }

    public void onAnswerAck(long noteId, int cardOrd) {
        if (mAnswerPendingAck && noteId == mPendingAckNoteId && cardOrd == mPendingAckCardOrd) {
            unlockAnswerButtons();
        }
    }

    private void lockAnswerButtons() {
        mAnswerPendingAck = true;
        mPendingAckNoteId = mNoteId;
        mPendingAckCardOrd = mCardOrd;
        mAgainButton.setEnabled(false);
        mGoodButton.setEnabled(false);
        if (mWaitingText != null) {
            mWaitingText.setVisibility(View.VISIBLE);
        }
        mPendingAckTimeout.removeCallbacksAndMessages(null);
        mPendingAckTimeout.postDelayed(() -> {
            if (mAnswerPendingAck) {
                Log.w(TAG, "Answer ack timeout — auto-unlocking buttons");
                unlockAnswerButtons();
            }
        }, 3000);
    }

    private void unlockAnswerButtons() {
        mAnswerPendingAck = false;
        mPendingAckNoteId = -1;
        mPendingAckCardOrd = -1;
        mAgainButton.setEnabled(true);
        mGoodButton.setEnabled(true);
        if (mWaitingText != null) {
            mWaitingText.setVisibility(View.GONE);
        }
        mPendingAckTimeout.removeCallbacksAndMessages(null);
    }

    private void answer(int ease) {
        try {
            if (mCallbacks != null) {
                mCallbacks.onAnswerCard(mNoteId, mCardOrd, ease);
            }
            lockAnswerButtons();
            if (mCallbacks != null && mCallbacks.getQueueSize() > 0) {
                JSONObject nextCard = ((MainActivity) getActivity()).dequeueCard();
                if (nextCard != null) {
                    setCard(nextCard);
                }
                if (mCallbacks.getQueueSize() <= 1) {
                    mCallbacks.onRequestCards();
                }
            } else {
                if (mCallbacks != null) {
                    mCallbacks.onRequestCards();
                }
                showEmptyState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error answering card", e);
            showEmptyState();
        }
    }

    private void showEmptyState() {
        if (mLoadingCardLayout != null) {
            mLoadingCardLayout.setVisibility(View.VISIBLE);
        }
        mQuestionText.setText("");
        mAnswerText.setVisibility(View.GONE);
        mShowAnswerBtn.setVisibility(View.GONE);
        mEaseLayout.setVisibility(View.GONE);
        if (mCardTypeText != null) mCardTypeText.setVisibility(View.GONE);
        if (mCardFlagsText != null) mCardFlagsText.setVisibility(View.GONE);
        if (mStatusBar != null) mStatusBar.setVisibility(View.GONE);
        if (mFlagBar != null) mFlagBar.setVisibility(View.GONE);
    }

    public void onCardReceived() {
        if (getActivity() != null &&
                mLoadingCardLayout != null &&
                mLoadingCardLayout.getVisibility() == View.VISIBLE) {
            JSONObject card = ((MainActivity) getActivity()).dequeueCard();
            if (card != null) {
                setCard(card);
            }
        }
    }

    public boolean isCurrentCard(long noteId, int cardOrd) {
        return mNoteId == noteId && mCardOrd == cardOrd;
    }

    public void applyFontSizes(int questionSize, int answerSize) {
        if (mQuestionText != null) mQuestionText.setTextSize(questionSize);
        if (mAnswerText != null) mAnswerText.setTextSize(answerSize);
    }

    public void onNoMoreCards() {
        if (mLoadingCardLayout != null) {
            mLoadingCardLayout.setVisibility(View.GONE);
        }
        mQuestionText.setText("No more cards");
        mAnswerText.setVisibility(View.GONE);
        mShowAnswerBtn.setVisibility(View.GONE);
        mEaseLayout.setVisibility(View.GONE);
        if (mCallbacks != null && mShownCard) {
            mCallbacks.onReviewFinished();
        }
    }
}
