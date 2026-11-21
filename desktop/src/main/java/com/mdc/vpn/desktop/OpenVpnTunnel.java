package com.mdc.vpn.desktop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windows OpenVPN istemcisi.
 *
 * openvpn.exe gizli pencerede baslatilir; durum ve trafik bilgisi yerel
 * yonetim arayuzunden (management) okunur. Cekirdek cokerse veya sunucu
 * baglantiyi reddederse kullaniciya gercek neden gosterilir.
 */
public final class OpenVpnTunnel implements Tunnel {

    private static final long STOP_GRACE_MS = 4_000L;

    private Listener listener;
    private ProcessHandle handle;
    private Socket socket;
    private BufferedWriter management;
    private Thread managementThread;
    private Thread logThread;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile State state = State.DISCONNECTED;
    private volatile long bytesIn;
    private volatile long bytesOut;
    private volatile String lastError = "";
    private Path logFile;
    private Path credentialsFile;

    @Override
    public void start(Profiles.Profile profile, String username, String password, Listener listener)
            throws Exception {
        stop();
        this.listener = listener;
        stopping = false;
        running = false;
        state = State.DISCONNECTED;
        bytesIn = 0;
        bytesOut = 0;
        lastError = "";

        Path exe = AppPaths.openVpnExe();
        if (!Files.isRegularFile(exe)) {
            throw new IOException("OpenVPN bulunamadı. Beklenen konum: " + exe
                    + "\n\nMDC VPN kurulum paketi openvpn.exe dosyasını içerir; "
                    + "portatif sürümü kullanıyorsanız openvpn klasörünün uygulamanın yanında olduğundan emin olun.");
        }
        Path wintun = exe.getParent() == null ? null : exe.getParent().resolve("wintun.dll");
        if (wintun == null || !Files.isRegularFile(wintun)) {
            throw new IOException("Wintun sürücüsü (wintun.dll) OpenVPN klasöründe bulunamadı. "
                    + "Tünel için openvpn.exe ile aynı klasörde wintun.dll bulunmalıdır.");
        }

        String config = Profiles.withConnectionLimits(profile.config());
        if (profile.needsCredentials()) {
            if (username == null || username.isBlank()) {
                throw new IOException("Bu profil kullanıcı adı ve parola istiyor.");
            }
            credentialsFile = AppPaths.runDir().resolve("credentials.txt");
            Files.writeString(credentialsFile, username + "\n" + (password == null ? "" : password) + "\n",
                    StandardCharsets.UTF_8);
            config = Profiles.withCredentials(config, credentialsFile);
        }

        Path configFile = AppPaths.runDir().resolve("session.ovpn");
        Files.writeString(configFile, config, StandardCharsets.UTF_8);

        logFile = AppPaths.logsDir().resolve("openvpn.log");
        Files.deleteIfExists(logFile);

        int managementPort = freePort();
        List<String> args = new ArrayList<>(List.of(
                "--config", configFile.toString(),
                "--windows-driver", "wintun",
                "--log", logFile.toString(),
                "--management", "127.0.0.1", Integer.toString(managementPort)
        ));

        notifyState(State.CONNECTING, "Sunucuya bağlanılıyor…");
        try {
            handle = AppPaths.launchHidden(exe, args, exe.getParent());
        } catch (IOException hiddenFailure) {
            // PowerShell kullanilamiyorsa dogrudan calistirilir; bu durumda
            // OpenVPN'in konsol penceresi gorunur ama tunel yine kurulur.
            List<String> command = new ArrayList<>();
            command.add(exe.toString());
            command.addAll(args);
            Process direct = new ProcessBuilder(command).redirectErrorStream(true).start();
            handle = direct.toHandle();
            log("Gizli başlatma kullanılamadı, doğrudan çalıştırıldı: " + hiddenFailure.getMessage());
        }
        running = true;
        handle.onExit().thenAccept(process -> {
            running = false;
            if (!stopping) {
                if (lastError.isBlank()) {
                    String reason = readLastLogLine();
                    lastError = reason.isBlank()
                            ? "OpenVPN beklenmedik şekilde kapandı."
                            : "OpenVPN kapandı: " + reason;
                }
                notifyState(State.DISCONNECTED, lastError);
            }
        });

        startLogTailer();
        startManagement(managementPort);
    }

    private int freePort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setReuseAddress(true);
            return server.getLocalPort();
        }
    }

    private void startManagement(int port) {
        managementThread = new Thread(() -> {
            Socket local = null;
            for (int attempt = 0; attempt < 80 && running; attempt++) {
                try {
                    local = new Socket();
                    local.connect(new InetSocketAddress("127.0.0.1", port), 400);
                    break;
                } catch (IOException e) {
                    local = null;
                    sleep(250);
                }
            }
            if (local == null) {
                log("Yönetim arayüzüne bağlanılamadı; durum günlük dosyasından izleniyor.");
                return;
            }
            socket = local;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(local.getInputStream(), StandardCharsets.UTF_8))) {
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(local.getOutputStream(), StandardCharsets.UTF_8));
                management = out;
                out.write("state on\nbytecount 1\nlog on\n");
                out.flush();

                String line;
                while ((line = in.readLine()) != null) {
                    handleManagementLine(line);
                }
            } catch (IOException ignored) {
                // Baglanti kapanmasi normaldir; surec cikisi ayrica izlenir.
            } finally {
                management = null;
            }
        }, "openvpn-management");
        managementThread.setDaemon(true);
        managementThread.start();
    }

    private void handleManagementLine(String line) {
        if (line.startsWith(">STATE:")) {
            String[] parts = line.substring(7).split(",");
            if (parts.length >= 2) applyCoreState(parts[1].trim(), parts.length >= 3 ? parts[2].trim() : "");
        } else if (line.startsWith(">BYTECOUNT:")) {
            String[] parts = line.substring(11).split(",");
            if (parts.length >= 2) {
                bytesIn = parseLong(parts[0]);
                bytesOut = parseLong(parts[1]);
            }
        } else if (line.startsWith(">FATAL:")) {
            lastError = line.substring(7).trim();
            log("HATA: " + lastError);
            notifyState(State.DISCONNECTED, lastError);
            running = false;
        } else if (line.startsWith(">PASSWORD:Verification Failed")) {
            lastError = "Sunucu kullanıcı adı/parola doğrulamasını reddetti.";
            log("HATA: " + lastError);
        } else if (line.startsWith(">LOG:")) {
            String[] parts = line.split(",", 3);
            if (parts.length == 3) {
                String message = parts[2].trim();
                if (!message.isEmpty()) {
                    log(message);
                    inspectLogMessage(message);
                }
            }
        }
    }

    private void applyCoreState(String coreState, String detail) {
        switch (coreState.toUpperCase(Locale.ROOT)) {
            case "CONNECTED" -> notifyState(State.CONNECTED, "Tünel kuruldu");
            case "DISCONNECTED", "EXITING" -> {
                if (stopping) {
                    notifyState(State.DISCONNECTED, "Bağlantı kesildi");
                } else {
                    if (lastError.isBlank()) {
                        lastError = detail.isBlank()
                                ? "Sunucu bağlantıyı kapattı."
                                : "Sunucu bağlantıyı kapattı: " + detail;
                    }
                    notifyState(State.DISCONNECTED, lastError);
                }
            }
            case "RECONNECTING", "RESOLVE", "TCP_CONNECT", "WAIT" ->
                    notifyState(State.CONNECTING, "Yeniden bağlanılıyor… (" + coreState + ")");
            case "AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH_PENDING" ->
                    notifyState(State.CONNECTING, "Tünel yapılandırılıyor… (" + coreState + ")");
            default -> notifyState(State.CONNECTING, "Bağlanılıyor… (" + coreState + ")");
        }
    }

    private void startLogTailer() {
        logThread = new Thread(() -> {
            try {
                // Dosyanin olusmasini bekle.
                for (int i = 0; i < 60 && !Files.exists(logFile); i++) sleep(100);
                BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8);
                String line;
                while (running || reader.ready()) {
                    line = reader.readLine();
                    if (line == null) {
                        if (!running) break;
                        sleep(250);
                        continue;
                    }
                    log(line);
                    inspectLogMessage(line);
                }
            } catch (Exception ignored) {
                // Günlük dosyası okunamazsa yönetim arayüzü yeterlidir.
            }
        }, "openvpn-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void inspectLogMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("initialization sequence completed")) {
            notifyState(State.CONNECTED, "Tünel kuruldu");
        } else if (lower.contains("auth_failed") || lower.contains("authentication failed")) {
            lastError = "Kimlik doğrulama başarısız. Kullanıcı adı/parola veya sertifikaları kontrol edin.";
        } else if (lower.startsWith("error:") || lower.contains("options error")) {
            lastError = message;
        }
    }

    private String readLastLogLine() {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && i > lines.size() - 12; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) return line;
            }
        } catch (Exception ignored) {
            // Günlük yoksa genel mesaj kullanılır.
        }
        return "";
    }

    @Override
    public void stop() {
        stopping = true;
        try {
            if (management != null) {
                management.write("signal SIGTERM\n");
                management.flush();
            }
        } catch (IOException ignored) {
            // Yönetim bağlantısı yoksa süreç doğrudan kapatılır.
        }

        ProcessHandle current = handle;
        if (current != null && current.isAlive()) {
            long deadline = System.currentTimeMillis() + STOP_GRACE_MS;
            while (current.isAlive() && System.currentTimeMillis() < deadline) {
                sleep(120);
            }
            if (current.isAlive()) {
                current.destroy();
                sleep(400);
            }
            if (current.isAlive()) {
                try {
                    Process killer = new ProcessBuilder("taskkill", "/PID",
                            Long.toString(current.pid()), "/T", "/F")
                            .redirectErrorStream(true).start();
                    killer.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    current.destroyForcibly();
                }
            }
        }
        handle = null;
        closeQuietly();
        running = false;
        state = State.DISCONNECTED;
        try {
            if (credentialsFile != null) Files.deleteIfExists(credentialsFile);
        } catch (IOException ignored) {
            // Kimlik dosyası silinemezse bir sonraki bağlantıda üzerine yazılır.
        }
    }

    private void closeQuietly() {
        try {
            if (management != null) management.close();
        } catch (IOException ignored) {
            // Yoksayilir.
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Yoksayilir.
        }
        management = null;
        socket = null;
    }

    @Override
    public boolean isRunning() {
        ProcessHandle current = handle;
        return running && current != null && current.isAlive();
    }

    @Override
    public long bytesIn() {
        return bytesIn;
    }

    @Override
    public long bytesOut() {
        return bytesOut;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    private void notifyState(State next, String detail) {
        if (state == next && next != State.CONNECTING) return;
        state = next;
        Listener current = listener;
        if (current != null) current.onState(next, detail);
    }

    private void log(String message) {
        Listener current = listener;
        if (current != null) current.onLog(message);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
