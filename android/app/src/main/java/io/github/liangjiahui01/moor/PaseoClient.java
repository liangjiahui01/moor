package io.github.liangjiahui01.moor;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * A client of the Paseo daemon (github.com/getpaseo/paseo, Apache-2.0) on the machine, reached through an SSH
 * tunnel to its local port. Paseo runs the Claude Code conversations (Claude Agent SDK), keeps them through
 * disconnects and sends their timeline as structured events; the chat screen only renders them.
 * Wire protocol: JSON frames on /ws; a hello with the daemon's local credential, then {"type":"session",
 * "message":{...}} both ways. Requests carry a requestId that the answer's payload echoes.
 * Unknown types and fields are ignored: the daemon adds fields often, never removes them.
 */
final class PaseoClient {
    interface Events {
        void onReady();

        /** The connection ended (or never started); why, for the screen. */
        void onClosed(String why);

        /** A message that is not the answer to a request: agent_stream, agent_update, ... */
        void onMessage(JSONObject m);
    }

    interface Reply {
        void done(JSONObject payload, String error);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Events events;
    private final Map<String, Reply> pending = new HashMap<>();
    private WebSocket ws;
    private boolean ready, closed;
    private int seq;

    PaseoClient(int port, String token, Events events) {
        this.events = events;
        ws = HTTP.newWebSocket(new Request.Builder().url("ws://127.0.0.1:" + port + "/ws").build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                try {
                    JSONObject caps = new JSONObject().put("hello_rejection", true).put("selective_agent_timeline", true);
                    socket.send(new JSONObject().put("type", "hello").put("clientId", "moor-" + android.os.Build.MODEL)
                        .put("clientType", "mobile").put("protocolVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
                        .put("auth", new JSONObject().put("kind", "localCredential").put("token", token))
                        .put("capabilities", caps).toString());
                } catch (Exception e) {
                    socket.close(1000, null);
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                JSONObject m;
                try {
                    JSONObject o = new JSONObject(text);
                    m = o.has("message") ? o.getJSONObject("message") : o;
                } catch (Exception e) {
                    return;
                }
                main.post(() -> handle(m));
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                main.post(() -> end(t.getMessage() == null ? t.toString() : t.getMessage()));
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                main.post(() -> end(reason == null || reason.isEmpty() ? "closed (" + code + ")" : reason));
            }
        });
    }

    boolean ready() {
        return ready && !closed;
    }

    private void handle(JSONObject m) {
        if (closed) return;
        String type = m.optString("type");
        JSONObject p = m.optJSONObject("payload");
        if (type.equals("hello.rejected") || type.equals("hello_rejected")) {
            end(p != null ? p.optString("reason", "rejected") : "rejected");
            return;
        }
        if (type.equals("status") && p != null && "server_info".equals(p.optString("status"))) {
            if (!ready) {
                ready = true;
                events.onReady();
            }
            return;
        }
        String id = p == null ? "" : p.optString("requestId");
        Reply r = id.isEmpty() ? null : pending.remove(id);
        if (r != null) {
            String err = p.optString("error", "");
            if (err.equals("null")) err = "";
            if (type.equals("rpc_error") && err.isEmpty()) err = p.optString("message", "error");
            r.done(p, err.isEmpty() ? null : err);
            // An answer may also be news for the screen (agent_created carries the new chat)
            if (!type.endsWith("_response")) events.onMessage(m);
            return;
        }
        events.onMessage(m);
    }

    /** Sends a session message; reply (may be null) gets the payload of the answer with the same requestId. */
    void request(JSONObject msg, Reply reply) {
        // A permission answer names the request it answers in requestId; the reply echoes it back the same way
        String given = msg.optString("requestId", "");
        String id = given.isEmpty() ? "m" + (++seq) : given;
        try {
            msg.put("requestId", id);
            if (reply != null) pending.put(id, reply);
            if (ws == null || closed || !ws.send(new JSONObject().put("type", "session").put("message", msg).toString())) {
                pending.remove(id);
                if (reply != null) reply.done(null, "not connected");
                return;
            }
        } catch (Exception e) {
            pending.remove(id);
            if (reply != null) reply.done(null, e.getMessage());
            return;
        }
        if (reply != null) main.postDelayed(() -> {
            Reply r = pending.remove(id);
            if (r != null) r.done(null, "timed out");
        }, 60_000);
    }

    void close() {
        if (ws != null) ws.close(1000, null);
        end(null);
    }

    private void end(String why) {
        if (closed) return;
        closed = true;
        ready = false;
        for (Reply r : pending.values()) r.done(null, "disconnected");
        pending.clear();
        if (ws != null) ws.cancel();
        if (why != null) events.onClosed(why);
    }

    static JSONObject msg(String type) {
        try {
            return new JSONObject().put("type", type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
