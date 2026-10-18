package io.github.liangjiahui01.moor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Speech to text with Volcengine's streaming speech recognition (Doubao "bigmodel" ASR): 16 kHz mono PCM goes up a
 * WebSocket in 100 ms packets, the running transcript comes back. Uses the user's own API key from Settings.
 */
final class VoiceInput {
    private static final String TAG = "VoiceInput";
    private static final String ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
    static final String MODEL_2 = "volc.seedasr.sauc.duration";
    static final String MODEL_1 = "volc.bigasr.sauc.duration";
    private static final int RATE = 16000;
    private static final int PACKET = RATE * 2 / 10; // 100 ms of 16-bit mono

    interface Listener {
        /** Running transcript (everything recognised so far). */
        void onText(String text);

        /** Microphone level 0..1, for the recording indicator. */
        void onLevel(float level);

        /** The final transcript after stop(); empty if nothing was said. */
        void onDone(String text);

        void onError(String message);
    }

    static final class Config {
        final String apiKey, appId, accessToken, model;

        Config(String apiKey, String appId, String accessToken, String model) {
            this.apiKey = apiKey;
            this.appId = appId;
            this.accessToken = accessToken;
            this.model = model;
        }

        boolean usable() {
            return !apiKey.isEmpty() || (!appId.isEmpty() && !accessToken.isEmpty());
        }

        static Config load(Context c) {
            SharedPreferences p = Prefs.raw(c);
            return new Config(p.getString("voice_api_key", "").trim(), p.getString("voice_app_id", "").trim(),
                p.getString("voice_access_token", "").trim(), p.getString("voice_model", MODEL_2));
        }
    }

    private static OkHttpClient http;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebSocket ws;
    private AudioRecord rec;
    private Thread reader;
    private volatile boolean recording, finished, cancelled;
    private volatile String text = "";
    private boolean triedFallback;

    VoiceInput(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build();
        }
        return http;
    }

    /** Starts recording and streaming. Needs the RECORD_AUDIO permission. */
    void start() {
        Config cfg = Config.load(ctx);
        open(cfg, cfg.model);
    }

    private void open(Config cfg, String model) {
        Request.Builder b = new Request.Builder().url(ENDPOINT)
            .header("X-Api-Resource-Id", model)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString());
        if (!cfg.apiKey.isEmpty()) {
            b.header("X-Api-Key", cfg.apiKey);
        } else {
            b.header("X-Api-App-Key", cfg.appId).header("X-Api-Access-Key", cfg.accessToken);
        }
        ws = http().newWebSocket(b.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (socket != ws) return;
                socket.send(ByteString.of(frame(0x10, 0x10, request())));
                startRecording(socket);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (socket != ws) return;
                handle(socket, bytes.toByteArray(), cfg, model);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                if (socket != ws) return;
                String msg = t.getMessage();
                if (response != null) {
                    try {
                        String body = response.body() != null ? response.body().string() : "";
                        msg = "HTTP " + response.code() + (body.isEmpty() ? "" : ": " + body);
                    } catch (Exception ignored) {
                    }
                }
                Log.w(TAG, "websocket failed: " + msg);
                // A key without access to model 2.0: try 1.0 once, before any audio was lost
                if (!triedFallback && response != null && model.equals(MODEL_2) && !recording) {
                    triedFallback = true;
                    open(cfg, MODEL_1);
                    return;
                }
                fail(msg);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (socket == ws) finish();
            }
        });
    }

    private byte[] request() {
        try {
            JSONObject req = new JSONObject()
                .put("model_name", "bigmodel")
                .put("enable_punc", true)
                .put("enable_ddc", true)
                .put("enable_nonstream", true)
                .put("show_utterances", true)
                .put("result_type", "full");
            JSONObject audio = new JSONObject().put("format", "pcm").put("codec", "raw")
                .put("rate", RATE).put("bits", 16).put("channel", 1);
            JSONObject root = new JSONObject()
                .put("user", new JSONObject().put("uid", uid()))
                .put("audio", audio)
                .put("request", req);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String uid() {
        SharedPreferences p = Prefs.raw(ctx);
        String id = p.getString("voice_uid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("voice_uid", id).apply();
        }
        return id;
    }

    /** Header (version 1, 4-byte header) + payload size + payload. */
    private static byte[] frame(int typeAndFlags, int serialization, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.put((byte) 0x11).put((byte) typeAndFlags).put((byte) serialization).put((byte) 0);
        b.putInt(payload.length);
        b.put(payload);
        return b.array();
    }

    @SuppressLint("MissingPermission")
    private void startRecording(WebSocket socket) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, PACKET * 4));
            rec.startRecording();
        } catch (Exception e) {
            fail(e.getMessage());
            return;
        }
        recording = true;
        reader = new Thread(() -> {
            byte[] buf = new byte[PACKET];
            while (recording) {
                int n = 0;
                while (n < PACKET && recording) {
                    int r = rec.read(buf, n, PACKET - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n <= 0) continue;
                byte[] pcm = n == PACKET ? buf.clone() : java.util.Arrays.copyOf(buf, n);
                socket.send(ByteString.of(frame(0x20, 0x00, pcm)));
                float level = level(pcm);
                main.post(() -> listener.onLevel(level));
            }
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            if (!cancelled) socket.send(ByteString.of(frame(0x22, 0x00, new byte[0]))); // last packet
        }, "voice-rec");
        reader.start();
    }

    private static float level(byte[] pcm) {
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        double rms = Math.sqrt(sum / (double) Math.max(n, 1));
        return (float) Math.min(1.0, rms / 6000.0);
    }

    private void handle(WebSocket socket, byte[] d, Config cfg, String model) {
        if (d.length < 8) return;
        int headerSize = (d[0] & 0x0f) * 4;
        int type = (d[1] >> 4) & 0x0f;
        int flags = d[1] & 0x0f;
        int compression = d[2] & 0x0f;
        int off = headerSize;
        if (type == 0x0f) {
            // An error before any audio is a real failure; afterwards it is how the server ends the session
            String msg = "";
            try {
                off += 4; // error code
                int size = ByteBuffer.wrap(d, off, 4).getInt();
                msg = new String(d, off + 4, Math.min(size, d.length - off - 4), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            if (!recording && !finished) {
                if (!triedFallback && model.equals(MODEL_2)) {
                    triedFallback = true;
                    ws = null;
                    socket.cancel();
                    open(cfg, MODEL_1);
                    return;
                }
                fail(msg.isEmpty() ? "error" : msg);
            } else {
                finish();
            }
            return;
        }
        if (flags == 0x01 || flags == 0x03) off += 4; // sequence number
        try {
            int size = ByteBuffer.wrap(d, off, 4).getInt();
            byte[] payload = java.util.Arrays.copyOfRange(d, off + 4, off + 4 + size);
            if (compression == 1) payload = gunzip(payload);
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONObject result = json.optJSONObject("result");
            String t = result != null ? result.optString("text", "") : json.optString("text", "");
            if (t.isEmpty() && result != null) {
                JSONArray us = result.optJSONArray("utterances");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; us != null && i < us.length(); i++) sb.append(us.getJSONObject(i).optString("text"));
                t = sb.toString();
            }
            if (!t.isEmpty() || flags == 0x03 || flags == 0x02) {
                text = t;
                final String shown = t;
                main.post(() -> {
                    if (!cancelled && !finished) listener.onText(shown);
                });
            }
            if (flags == 0x03 || flags == 0x02) finish(); // the last response
        } catch (Exception e) {
            Log.w(TAG, "bad response", e);
        }
    }

    private static byte[] gunzip(byte[] in) throws Exception {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = z.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** Stops recording; onDone follows with the final transcript (within a few seconds at most). */
    void stop() {
        if (!recording) {
            finish();
            return;
        }
        recording = false;
        main.postDelayed(this::finish, 4000); // in case the final response never comes
    }

    /** Stops and drops everything; no callbacks follow. */
    void cancel() {
        cancelled = true;
        finished = true;
        recording = false;
        WebSocket s = ws;
        ws = null;
        if (s != null) s.cancel();
    }

    private void finish() {
        main.post(() -> {
            if (finished) return;
            finished = true;
            recording = false;
            WebSocket s = ws;
            ws = null;
            if (s != null) s.close(1000, null);
            listener.onDone(text);
        });
    }

    private void fail(String message) {
        main.post(() -> {
            if (finished) return;
            finished = true;
            recording = false;
            WebSocket s = ws;
            ws = null;
            if (s != null) s.cancel();
            listener.onError(message == null ? "" : message);
        });
    }
}
