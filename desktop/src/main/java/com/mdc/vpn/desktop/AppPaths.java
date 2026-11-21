package com.mdc.vpn.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Windows'ta kullanilan sabit yollar ve yonetici izni yardimcilari. */
public final class AppPaths {

    private AppPaths() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** %APPDATA%\MDC VPN */
    public static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"));
        return base.resolve("MDC VPN");
    }

    public static Path profilesDir() {
        return ensure(dataDir().resolve("profiles"));
    }

    public static Path runDir() {
        return ensure(dataDir().resolve("run"));
    }

    public static Path logsDir() {
        return ensure(dataDir().resolve("logs"));
    }

    public static Path settingsFile() {
        return dataDir().resolve("settings.properties");
    }

    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {
            // Dizin olusturulamazsa dosya islemleri anlamli bir hata verecektir.
        }
        return path;
    }

    /** jpackage uygulama klasoru (MDC-VPN.exe'nin yani). */
    public static Path installDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path parent = Path.of(appPath).toAbsolutePath().getParent();
            if (parent != null) return parent;
        }
        try {
            Path location = Path.of(AppPaths.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            return dir != null ? dir : Path.of(".").toAbsolutePath();
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath();
        }
    }

    /** Uygulamayla birlikte gelen openvpn.exe; yoksa kurulu OpenVPN aranir. */
    public static Path openVpnExe() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(installDir().resolve("openvpn").resolve("openvpn.exe"));
        candidates.add(installDir().resolve("openvpn").resolve("bin").resolve("openvpn.exe"));
        String env = System.getenv("MDC_OPENVPN_EXE");
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            candidates.add(Path.of(programFiles, "OpenVPN", "bin", "openvpn.exe"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return candidates.get(0);
    }

    /** Kurulu WireGuard for Windows. */
    public static Path wireGuardExe() {
        List<Path> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            candidates.add(Path.of(programFiles, "WireGuard", "wireguard.exe"));
        }
        String env = System.getenv("MDC_WIREGUARD_EXE");
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return candidates.isEmpty() ? Path.of("wireguard.exe") : candidates.get(0);
    }

    /** Uygulama yonetici olarak mi calisiyor? (VPN adaptoru icin gereklidir.) */
    public static boolean isElevated() {
        if (!isWindows()) return false;
        try {
            Process process = new ProcessBuilder("net", "session")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ayni uygulamayi UAC istemiyle yeniden baslatir. */
    public static void relaunchElevated() throws IOException {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            throw new IOException("Uygulama yolu bulunamadi (gelistirme modu).");
        }
        String quoted = appPath.replace("'", "''");
        new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process -FilePath '" + quoted + "' -Verb RunAs"
        ).start();
    }

    /** Komutu gizli pencerede baslatir ve surec kimligini dondurur. */
    public static ProcessHandle launchHidden(Path exe, List<String> args, Path workingDir) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("$p = Start-Process -FilePath ").append(psLiteral(exe.toString()))
                .append(" -ArgumentList @(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) script.append(',');
            script.append(psLiteral("\"" + args.get(i).replace("\"", "\\\"") + "\""));
        }
        script.append(')');
        if (workingDir != null) {
            script.append(" -WorkingDirectory ").append(psLiteral(workingDir.toString()));
        }
        script.append(" -WindowStyle Hidden -PassThru; $p.Id");

        Process launcher = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", script.toString()
        ).redirectErrorStream(true).start();

        String output = new String(launcher.getInputStream().readAllBytes()).trim();
        try {
            launcher.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long pid = 0;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("\\d+")) pid = Long.parseLong(trimmed);
        }
        if (pid <= 0) {
            throw new IOException("Süreç başlatılamadı: " + output);
        }
        final long processId = pid;
        return ProcessHandle.of(processId)
                .orElseThrow(() -> new IOException("Süreç bulunamadı: " + processId));
    }

    private static String psLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Windows'ta gizli bir komut calistirir (gerekirse konsol gizlenir). */
    public static Process runHidden(List<String> command) throws IOException {
        List<String> wrapper = new ArrayList<>();
        wrapper.add("powershell.exe");
        wrapper.add("-NoProfile");
        wrapper.add("-NonInteractive");
        wrapper.add("-WindowStyle");
        wrapper.add("Hidden");
        wrapper.add("-Command");
        wrapper.add("& " + command.stream()
                .map(part -> psLiteral("\"" + part.replace("\"", "\\\"") + "\""))
                .reduce((a, b) -> a + " " + b).orElse(""));
        return new ProcessBuilder(wrapper).redirectErrorStream(true).start();
    }
}
