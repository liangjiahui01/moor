package com.jcraft.jsch;

/**
 * Sets how much a channel lets the machine send ahead of what the app has read (JSch keeps this setter
 * package-private). A bulk read (a file preview) gets a small window: on a slow path, a 1 MB window fills the
 * network's queues for many seconds, the direct connection's keepalives wait behind it and the link drops.
 */
public final class ChannelWindow {
    private ChannelWindow() {}

    /** Before the channel connects. */
    public static void limit(Channel c, int bytes) {
        c.setLocalWindowSizeMax(bytes);
        c.setLocalWindowSize(bytes);
    }
}
