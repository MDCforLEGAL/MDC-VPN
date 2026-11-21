package com.mdc.vpn.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * WireGuard for Windows tunelini yonetir.
 *
 * WireGuard servis tabanli calistigi icin kurulum/kaldirma yonetici izni
 * gerektirir. Surucu ve servis WireGuard uygulamasinin kendisi tarafindan
 * saglanir; bu sinif yalnizca tuneli kurar ve durumunu izler.
 */
public final class WireGuardTunnel implements Tunnel {

    private static final String TUNNEL_NAME = "mdcvpn";

    private Listener listener;
    private volatile boolean running;
    private volatile State state = State.DISCONNECTED;
    private volatile String lastError = "";
    private Path configFile;
    private Thread poller;

    @Override
    public void start(Profiles.Profile profile, String username, String password, Listener listener)
            throws Exception {
        stop();
        this.listener = listener;
        lastError = "";

        Path exe = AppPaths.wireGuardExe();
        if (!Files.isRegularFile(exe)) {
            throw new IOException("WireGuard for Windows bulunamadı. "
                    + "https://www.wireguard.com/install/ adresinden kurup tekrar deneyin.");
        }
        if (profile.config().isBlank()) {
            throw new IOException("WireGuard yapılandırması boş. Bir .conf dosyası içe aktarın.");
        }

        configFile = AppPaths.runDir().resolve("mdcvpn.conf");
        Files.writeString(configFile, profile.config(), StandardCharsets.UTF_8);

        notifyState(State.CONNECTING, "WireGuard tüneli kuruluyor…");
        Process install;
        try {
            install = AppPaths.runHidden(List.of(
                    exe.toString(), "/installtunnelservice", configFile.toString()));
        } catch (IOException hiddenFailure) {
            install = new ProcessBuilder(exe.toString(), "/installtunnelservice", configFile.toString())
                    .redirectErrorStream(true).start();
        }
        String output = new String(install.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = install.waitFor();
        if (code != 0) {
            lastError = output.isBlank() ? "WireGuard tüneli kurulamadı (kod " + code + ")." : output;
            throw new IOException(lastError);
        }

        running = true;
        startPoller();
    }

    private void startPoller() {
        poller = new Thread(() -> {
            boolean announced = false;
            while (running) {
                boolean up = serviceRunning();
                if (up && !announced) {
                    announced = true;
                    notifyState(State.CONNECTED, "WireGuard tüneli etkin");
                } else if (!up && announced && running) {
                    announced = false;
                    lastError = "WireGuard tüneli kapandı.";
                    notifyState(State.DISCONNECTED, lastError);
                    running = false;
                    return;
                }
                sleep(1500);
            }
        }, "wireguard-status");
        poller.setDaemon(true);
        poller.start();
    }

    private boolean serviceRunning() {
        try {
            Process process = new ProcessBuilder("sc", "query", "WireGuardTunnel$" + TUNNEL_NAME)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output.toUpperCase(Locale.ROOT).contains("RUNNING");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void stop() {
        running = false;
        Path exe = AppPaths.wireGuardExe();
        if (Files.isRegularFile(exe)) {
            try {
                Process remove = AppPaths.runHidden(List.of(
                        exe.toString(), "/uninstalltunnelservice", TUNNEL_NAME));
                remove.getInputStream().readAllBytes();
                remove.waitFor();
            } catch (Exception ignored) {
                // Tünel zaten kapalı olabilir.
            }
        }
        state = State.DISCONNECTED;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long bytesIn() {
        return 0L;
    }

    @Override
    public long bytesOut() {
        return 0L;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    private void notifyState(State next, String detail) {
        if (state == next) return;
        state = next;
        Listener current = listener;
        if (current != null) current.onState(next, detail);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
