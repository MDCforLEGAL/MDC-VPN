package com.mdc.vpn.desktop;

/** Tum tunel cekirdeklerinin ortak arayuzu. */
public interface Tunnel {

    enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    interface Listener {
        void onState(State state, String detail);

        void onLog(String line);
    }

    void start(Profiles.Profile profile, String username, String password, Listener listener) throws Exception;

    void stop();

    boolean isRunning();

    long bytesIn();

    long bytesOut();

    String lastError();
}
