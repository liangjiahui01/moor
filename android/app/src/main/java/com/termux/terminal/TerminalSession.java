package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;

/**
 * Modified from Termux's TerminalSession: no local process; bytes come from a pluggable {@link Transport} (SSH here).
 * The transport thread calls {@link #feed} to queue output and the main thread hands it to the emulator; keyboard
 * input goes through a queue to a writer thread. On disconnect the transport is cleared while the session and
 * screen stay, and a new transport is plugged in after reconnecting.
 */
public final class TerminalSession extends TerminalOutput {

    /** Implemented by the connection: receives size changes and bytes to send. */
    public interface Transport {
        void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

        void write(byte[] data, int offset, int count) throws java.io.IOException;
    }

    private static final int MSG_NEW_INPUT = 1;

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(16 * 1024);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;
    private final Integer mTranscriptRows;
    private volatile Transport mTransport;
    private volatile boolean mFinished;

    private int mColumns, mRows, mCellWidth, mCellHeight;

    final Handler mMainThreadHandler = new MainThreadHandler();

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        Thread writer = new Thread("TermSessionOutputWriter") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int n = mTerminalToProcessIOQueue.read(buffer, true);
                    if (n == -1) return;
                    Transport t = mTransport;
                    if (t == null) continue; // input while disconnected is dropped
                    try {
                        t.write(buffer, 0, n);
                    } catch (Exception e) {
                        // the connection reports the disconnect itself
                    }
                }
            }
        };
        writer.setDaemon(true);
        writer.start();
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Called once TerminalView knows the grid: creates the emulator the first time, then resizes and tells the remote. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mColumns = columns;
        mRows = rows;
        mCellWidth = cellWidthPixels;
        mCellHeight = cellHeightPixels;
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        Transport t = mTransport;
        if (t != null) t.resize(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    public int getColumns() { return mColumns; }

    public int getRows() { return mRows; }

    public int getCellWidth() { return mCellWidth; }

    public int getCellHeight() { return mCellHeight; }

    public boolean hasSize() { return mColumns > 0 && mRows > 0; }

    public void setTransport(Transport transport) { mTransport = transport; }

    public boolean hasTransport() { return mTransport != null; }

    /** Transport thread: remote output. Blocks when the queue is full (back pressure). */
    public void feed(byte[] data, int offset, int count) {
        if (mFinished) return;
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Main thread: before reconnecting, clears the screen and modes left by the last connection (alt screen, mouse reporting, ...). */
    public void resetForReconnect() {
        if (mEmulator == null) return;
        byte[] reset = "\033c\033[2J\033[3J\033[H".getBytes(StandardCharsets.US_ASCII);
        mEmulator.append(reset, reset.length);
        notifyScreenUpdate();
    }

    /** Main thread: prints a local notice line without sending it to the remote. */
    public void printLocal(String text) {
        if (mEmulator == null) return;
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(b, b.length);
        notifyScreenUpdate();
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Bytes sent before the next user input (e.g. to make tmux leave scroll mode); mouse events and terminal replies don't trigger it. */
    public volatile byte[] pendingPrefix;
    /** The main thread is writing non-user input (mouse events, terminal replies). */
    public boolean internalWrite;

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mFinished) return;
        byte[] prefix = pendingPrefix;
        if (prefix != null && !internalWrite) {
            pendingPrefix = null;
            mTerminalToProcessIOQueue.write(prefix, 0, prefix.length);
        }
        mTerminalToProcessIOQueue.write(data, offset, count);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        int pos = 0;
        if (prependEscape) mUtf8InputBuffer[pos++] = 27;
        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[pos++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[pos++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[pos++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[pos++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[pos++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, pos);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Ends the session for good (when the app quits). */
    public void finish() {
        mFinished = true;
        mTransport = null;
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
    }

    public boolean isRunning() {
        return !mFinished;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {
        final byte[] mReceiveBuffer = new byte[16 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator == null) return; // stays queued until the size is known
            int bytesRead;
            boolean any = false;
            while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                internalWrite = true;
                try {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                } finally {
                    internalWrite = false;
                }
                any = true;
            }
            if (any) notifyScreenUpdate();
        }
    }
}
