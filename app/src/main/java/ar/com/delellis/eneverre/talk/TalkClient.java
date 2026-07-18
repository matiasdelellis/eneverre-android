package ar.com.delellis.eneverre.talk;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Push-to-talk client: streams the microphone to the camera's two-way-audio
 * backchannel over a WebSocket (see {@code doc/TALK.md} in the eneverre-api repo).
 * This socket is send-only — the camera's own audio is heard through the normal
 * live stream.
 *
 * <p>Two codecs, negotiated in the handshake:
 * <ul>
 *   <li><b>PCM/G.711</b> (default) — captures mono S16LE PCM and sends the bytes
 *       verbatim; the server resamples to 8 kHz and encodes G.711 for the camera.
 *       Every backchannel camera supports this.
 *   <li><b>AAC</b> ({@code aac=true}) — encodes AAC-LC on-device with
 *       {@link MediaCodec} and sends one raw access unit per message; the server
 *       forwards them untranscoded for 16 kHz wideband audio. Only use this when
 *       the camera advertises AAC (see {@code Camera.supportsTalkAac()}), otherwise
 *       the server closes the socket with an RTSP error.
 * </ul>
 *
 * <p>The session and the actual transmission are decoupled: {@link #start()} opens
 * the socket and reports {@link Listener#onReady()} once the backchannel is live,
 * but the microphone is not captured until {@link #setTransmitting(boolean)} is
 * toggled by the push-to-talk button. This lets the UI initialize the link first
 * and only hold the mic while the user is actually speaking. On the AAC path the
 * encoder still comes up at "ready" and streams <em>silence</em> until then, to
 * warm the camera's backchannel so the first word is not clipped (the G.711 path
 * relies on the server for that).
 *
 * <p>Authentication uses the same Bearer access token as the REST API, set on the
 * upgrade request's {@code Authorization} header. One active session per camera:
 * a second concurrent client is rejected with HTTP 409.
 */
public class TalkClient {

    /** UI callbacks. Invoked on OkHttp/background threads — marshal to the UI thread. */
    public interface Listener {
        /** The camera backchannel is live; push-to-talk can now be enabled. */
        void onReady();
        /**
         * The session ended (user close or failure). {@code reason} is a close
         * reason or {@code "HTTP <code>"} (401 auth, 404 unsupported, 409 busy).
         */
        void onEnd(@Nullable String reason);
    }

    private static final int SAMPLE_RATE = 16000;   // low rate → less uplink; see doc "Bandwidth"
    private static final int AAC_BIT_RATE = 32000;  // plenty for 16 kHz mono voice
    /** AAC-LC frames are 1024 samples; used to size and pace the warm-up silence. */
    private static final int AAC_FRAME_SAMPLES = 1024;
    private static final long AAC_FRAME_MS = 1000L * AAC_FRAME_SAMPLES / SAMPLE_RATE; // ~64 ms

    private final String talkUrl;              // ws(s)://<host>/api/camera/<id>/talk
    private final String authorizationHeader;  // "Bearer <accessToken>"
    // When true, encode AAC-LC on-device and negotiate codec=aac; otherwise send
    // raw PCM and let the server transcode to G.711. Fixed for the session.
    private final boolean aac;
    private final Listener listener;

    private final OkHttpClient client = new OkHttpClient.Builder()
            // Also detect a dead server from the client side (server pings every 25 s).
            .pingInterval(25, TimeUnit.SECONDS)
            .build();

    private WebSocket ws;
    private volatile boolean transmitting;
    /** AAC path only: true while the encoder + warm-up-silence session thread runs. */
    private volatile boolean aacSessionRunning;
    /** Linear gain applied to captured samples before sending; 1.0 = unity. */
    private volatile float micGain = 1.0f;

    public TalkClient(String talkUrl, String authorizationHeader, boolean aac, Listener listener) {
        this.talkUrl = talkUrl;
        this.authorizationHeader = authorizationHeader;
        this.aac = aac;
        this.listener = listener;
    }

    /** Opens the socket and handshakes; {@link Listener#onReady()} follows when live. */
    public void start() {
        Request request = new Request.Builder()
                .url(talkUrl)
                // Android sets the Authorization header directly — no token in the URL/logs.
                .header("Authorization", authorizationHeader)
                .build();

        ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // Handshake with the capture rate (and codec on the AAC path);
                // capture itself waits for the PTT button.
                if (aac) {
                    webSocket.send("{\"sampleRate\": " + SAMPLE_RATE + ", \"codec\": \"aac\"}");
                } else {
                    webSocket.send("{\"sampleRate\": " + SAMPLE_RATE + "}");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // Server signals the camera backchannel is live.
                if (text.contains("\"ready\"")) {
                    // The AAC path is passthrough: the server sends nothing to the
                    // camera until we send the first access unit, so the camera's
                    // decoder/speaker never warms up on its own and it drops the
                    // start of the first word. Bring the encoder up now and stream
                    // silence until the user actually talks (see doc "AAC warm-up").
                    // The G.711 path needs none of this — the server streams its own
                    // silence to the camera from the moment the session opens.
                    if (aac) startAacSession();
                    listener.onReady();
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                stop();
                listener.onEnd(reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                stop();
                listener.onEnd(reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                // response.code() is 401 / 404 / 409 for auth / capability / busy.
                stop();
                listener.onEnd(response != null ? "HTTP " + response.code() : t.getMessage());
            }
        });
    }

    /** Sets the mic gain (0 = silence, 1 = unity, >1 = amplify). */
    public void setMicGain(float gain) {
        micGain = gain < 0f ? 0f : gain;
    }

    /**
     * Starts/stops capturing and sending the mic. Called by the push-to-talk
     * button: {@code true} on press, {@code false} on release. Idempotent.
     */
    public synchronized void setTransmitting(boolean on) {
        if (on == transmitting) {
            return;
        }
        if (aac) {
            // The AAC session thread is already running (started on "ready"); it
            // switches between silence and the real mic by reading this flag, so
            // there is no per-press thread to start or stop here. Silence keeps
            // flowing during pauses, which keeps the camera backchannel warm.
            transmitting = on;
            return;
        }
        if (on) {
            startRecording();
        } else {
            // Just signal the capture thread. It finishes the in-flight read, sends
            // that last chunk, then stops and releases the recorder itself — so no
            // audio is dropped on release and the recorder is never touched across
            // threads. The socket stays open until the panel is closed (stop()).
            transmitting = false;
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startRecording() {
        final WebSocket webSocket = ws;
        if (webSocket == null) {
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf);
        recorder.startRecording();
        transmitting = true;

        final int bufSize = minBuf;
        new Thread(() -> {
            byte[] buf = new byte[bufSize];
            // Read-then-send, checking the flag last: when transmitting flips off
            // mid-read, the just-filled buffer is still sent before the loop exits,
            // so the tail of speech isn't clipped.
            while (transmitting) {
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    applyGain(buf, n, micGain);
                    // AudioRecord gives little-endian S16 — send the bytes as-is.
                    webSocket.send(ByteString.of(buf, 0, n));
                }
            }
            try { recorder.stop(); } catch (IllegalStateException ignored) {}
            recorder.release();
        }, "talk-mic").start();
    }

    /**
     * AAC path: brings the on-device AAC-LC encoder up as soon as the backchannel is
     * ready and runs a single long-lived thread for the whole session, sending one
     * raw access unit per WebSocket message; the server forwards them untranscoded
     * to the camera's MPEG4-GENERIC track (16 kHz wideband). The encoder format
     * (AAC-LC, mono, {@link #SAMPLE_RATE}) must match that track.
     *
     * <p>Because the server relays AAC verbatim (it does not synthesize its own
     * silence as it does on G.711), the camera only warms its decoder/speaker once
     * we start sending. So the thread feeds the encoder <em>silence</em> whenever
     * {@link #transmitting} is false — during the warm-up window after "ready" and
     * during pauses between presses — and switches to the real mic while the button
     * is held. The user's first word therefore lands on an already-live channel and
     * is not clipped. The mic is opened only while actually transmitting.
     */
    @SuppressWarnings("MissingPermission")
    private void startAacSession() {
        final WebSocket webSocket;
        synchronized (this) {
            if (aacSessionRunning || ws == null) {
                return;
            }
            aacSessionRunning = true;   // claim the session before the slow codec setup
            webSocket = ws;
        }

        final int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf);
        final MediaCodec codec;
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            aacSessionRunning = false;
            listener.onEnd("AAC encoder unavailable");
            return;
        }

        new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // Scratch buffer for real mic PCM, so gain can be applied before handing
            // samples to the encoder (the encoder input is opaque AAC).
            byte[] pcm = new byte[minBuf];
            // One AAC-LC frame of zero-filled PCM, fed while not transmitting to keep
            // the camera's backchannel warm.
            byte[] silence = new byte[AAC_FRAME_SAMPLES * 2];
            AudioRecord recorder = null;   // opened only while actually transmitting
            try {
                while (aacSessionRunning) {
                    boolean tx = transmitting;
                    if (tx && recorder == null) {
                        recorder = new AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, minBuf);
                        recorder.startRecording();
                    } else if (!tx && recorder != null) {
                        // Back to silence: release the mic so the OS indicator turns
                        // off during pauses, but keep feeding the encoder (below).
                        try { recorder.stop(); } catch (IllegalStateException ignored) {}
                        recorder.release();
                        recorder = null;
                    }

                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        int n;
                        if (recorder != null) {
                            int cap = Math.min(inBuf.remaining(), pcm.length);
                            n = recorder.read(pcm, 0, cap);   // blocks → real-time pacing
                            if (n > 0) {
                                applyGain(pcm, n, micGain);
                                inBuf.put(pcm, 0, n);
                            }
                        } else {
                            n = Math.min(silence.length, inBuf.remaining());
                            inBuf.put(silence, 0, n);
                        }
                        codec.queueInputBuffer(inIdx, 0, Math.max(n, 0), 0, 0);
                    }
                    drainEncoder(codec, info, webSocket);

                    // Real mic reads self-pace (they block until captured); silence
                    // does not, so throttle it to ~one frame of real time, otherwise
                    // the loop would flood the socket with silent AUs.
                    if (recorder == null) {
                        try {
                            Thread.sleep(AAC_FRAME_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                // Flush the encoder so any buffered tail is emitted before release.
                try {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    drainEncoder(codec, info, webSocket);
                } catch (IllegalStateException ignored) {}
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); } catch (IllegalStateException ignored) {}
                    recorder.release();
                }
                try { codec.stop(); } catch (IllegalStateException ignored) {}
                codec.release();
            }
        }, "talk-aac").start();
    }

    /** Drains ready AAC access units and sends each as one binary message. */
    private static void drainEncoder(MediaCodec codec, MediaCodec.BufferInfo info, WebSocket webSocket) {
        int outIdx = codec.dequeueOutputBuffer(info, 0);
        while (outIdx >= 0) {
            // The first output is the AudioSpecificConfig (CSD) — not audio; skip it.
            boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (info.size > 0 && !isConfig) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                byte[] au = new byte[info.size];
                outBuf.position(info.offset);
                outBuf.get(au);
                webSocket.send(ByteString.of(au));
            }
            codec.releaseOutputBuffer(outIdx, false);
            outIdx = codec.dequeueOutputBuffer(info, 0);
        }
    }

    /** Scales S16LE samples in place; a no-op at unity gain. */
    private static void applyGain(byte[] buf, int len, float gain) {
        if (gain == 1.0f) {
            return;
        }
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            int scaled = Math.round(sample * gain);
            if (scaled > 32767) scaled = 32767;
            else if (scaled < -32768) scaled = -32768;
            buf[i] = (byte) (scaled & 0xff);
            buf[i + 1] = (byte) ((scaled >> 8) & 0xff);
        }
    }

    /**
     * Ends the session (called when the talk panel is closed). Signals the capture
     * thread to stop and release the recorder, then closes the socket — OkHttp
     * flushes already-queued audio frames before the close frame. Idempotent.
     */
    public synchronized void stop() {
        transmitting = false;
        aacSessionRunning = false;
        if (ws != null) {
            ws.close(1000, "user released");
            ws = null;
        }
    }
}
