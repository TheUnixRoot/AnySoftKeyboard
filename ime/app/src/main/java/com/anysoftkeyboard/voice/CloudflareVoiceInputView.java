package com.anysoftkeyboard.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.anysoftkeyboard.prefs.DirectBootAwareSharedPreferences;
import com.menny.android.anysoftkeyboard.R;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class CloudflareVoiceInputView extends LinearLayout {

    public interface Callback {
        void onTextTranscribed(String text);
        void onFinish();
    }

    private TextView mStatusText;
    private ImageButton mMicButton;
    private Button mCancelButton;
    private Button mDoneButton;

    private MediaRecorder mMediaRecorder;
    private File mAudioFile;
    private boolean mIsRecording = false;
    private boolean mIsTranscribing = false;

    private Callback mCallback;
    private String mAccountId;
    private String mApiToken;
    private String mModel;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private long mRecordingStartTime = 0;
    private final Runnable mUpdateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsRecording) {
                long elapsed = (System.currentTimeMillis() - mRecordingStartTime) / 1000;
                mStatusText.setText("Recording... " + elapsed + "s / 30s");
                if (elapsed >= 30) {
                    stopAndTranscribe();
                } else {
                    mMainHandler.postDelayed(this, 1000);
                }
            }
        }
    };

    public CloudflareVoiceInputView(Context context) {
        super(context);
        init(context);
    }

    public CloudflareVoiceInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.cloudflare_voice_input, this, true);

        mStatusText = findViewById(R.id.cloudflare_stt_status);
        mMicButton = findViewById(R.id.cloudflare_stt_mic_button);
        mCancelButton = findViewById(R.id.cloudflare_stt_cancel);
        mDoneButton = findViewById(R.id.cloudflare_stt_done);

        mMicButton.setOnClickListener(v -> {
            if (!mIsRecording && !mIsTranscribing) {
                startRecording();
            } else if (mIsRecording) {
                stopAndTranscribe();
            }
        });

        mCancelButton.setOnClickListener(v -> {
            cancelRecording();
            if (mCallback != null) {
                mCallback.onFinish();
            }
        });

        mDoneButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onFinish();
            }
        });
    }

    public void setup(String accountId, String apiToken, String model, Callback callback) {
        mAccountId = accountId;
        mApiToken = apiToken;
        mModel = model == null || model.isEmpty() ? "@cf/openai/whisper" : model;
        mCallback = callback;

        // Auto start recording when setup is completed
        startRecording();
    }

    private void startRecording() {
        if (mAccountId == null || mAccountId.isEmpty() || mApiToken == null || mApiToken.isEmpty()) {
            mStatusText.setText("Error: Cloudflare settings not configured.");
            return;
        }

        try {
            mAudioFile = new File(getContext().getCacheDir(), "cloudflare_voice_input.m4a");
            if (mAudioFile.exists()) {
                mAudioFile.delete();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mMediaRecorder = new MediaRecorder(getContext());
            } else {
                mMediaRecorder = new MediaRecorder();
            }

            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mMediaRecorder.setAudioSamplingRate(16000);
            mMediaRecorder.setOutputFile(mAudioFile.getAbsolutePath());

            mMediaRecorder.prepare();
            mMediaRecorder.start();

            mIsRecording = true;
            mMicButton.setImageResource(R.drawable.ic_cancel);
            mRecordingStartTime = System.currentTimeMillis();
            mMainHandler.post(mUpdateTimerRunnable);
        } catch (Exception e) {
            e.printStackTrace();
            mStatusText.setText("Error starting recording: " + e.getMessage());
            cleanupRecorder();
        }
    }

    private void stopAndTranscribe() {
        if (!mIsRecording) return;
        mIsRecording = false;
        mMainHandler.removeCallbacks(mUpdateTimerRunnable);

        try {
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
            }
        } catch (RuntimeException stopException) {
            stopException.printStackTrace();
            if (mAudioFile != null && mAudioFile.exists()) {
                mAudioFile.delete();
            }
            mStatusText.setText("Too short. Try again.");
            mIsTranscribing = false;
            mMicButton.setEnabled(true);
            mCancelButton.setEnabled(true);
            mMicButton.setImageResource(R.drawable.ic_av_mic_light);
            cleanupRecorder();
            return;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cleanupRecorder();
        }

        if (mAudioFile == null || !mAudioFile.exists() || mAudioFile.length() == 0) {
            mStatusText.setText("Error: No audio recorded.");
            return;
        }

        mIsTranscribing = true;
        mStatusText.setText("Transcribing...");
        mMicButton.setEnabled(false);
        mCancelButton.setEnabled(false);

        mExecutor.execute(this::transcribeAudio);
    }

    private void transcribeAudio() {
        String resultText = null;
        String errorMessage = null;

        try {
            URL url = new URL("https://api.cloudflare.com/client/v4/accounts/" + mAccountId + "/ai/run/" + mModel);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + mApiToken);
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            // Write audio bytes to connection output stream
            try (FileInputStream fis = new FileInputStream(mAudioFile);
                 OutputStream os = conn.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, length);
                }
                String responseStr = baos.toString("UTF-8");
                JSONObject responseJson = new JSONObject(responseStr);
                boolean success = responseJson.optBoolean("success", false);
                if (success) {
                    JSONObject resultJson = responseJson.optJSONObject("result");
                    if (resultJson != null) {
                        resultText = resultJson.optString("text", "");
                    }
                } else {
                    org.json.JSONArray errorsArray = responseJson.optJSONArray("errors");
                    if (errorsArray != null && errorsArray.length() > 0) {
                        errorMessage = errorsArray.getJSONObject(0).optString("message", "Unknown error");
                    } else {
                        errorMessage = "API Error (Unknown)";
                    }
                }
            } else {
                errorMessage = "HTTP Error " + responseCode + ": " + conn.getResponseMessage();
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
            errorMessage = e.getMessage();
        }

        final String transcribedText = resultText;
        final String finalError = errorMessage;

        mMainHandler.post(() -> {
            mIsTranscribing = false;
            mMicButton.setEnabled(true);
            mCancelButton.setEnabled(true);
            mMicButton.setImageResource(R.drawable.ic_av_mic_light);

            if (transcribedText != null) {
                mStatusText.setText("Transcription successful!");
                if (mCallback != null) {
                    mCallback.onTextTranscribed(transcribedText);
                }
                mDoneButton.setVisibility(VISIBLE);
                mDoneButton.setText("Close");
                if (mCallback != null) {
                    mCallback.onFinish();
                }
            } else {
                mStatusText.setText("Failed: " + finalError);
            }
        });
    }

    private void cancelRecording() {
        mIsRecording = false;
        mMainHandler.removeCallbacks(mUpdateTimerRunnable);
        cleanupRecorder();
        if (mAudioFile != null && mAudioFile.exists()) {
            mAudioFile.delete();
        }
    }

    private void cleanupRecorder() {
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mMediaRecorder = null;
        }
    }

    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelRecording();
        mExecutor.shutdownNow();
    }
}
