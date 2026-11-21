package com.mdc.vpn.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows uygulamasinin profil deposu.
 *
 * Android surumundeki [ProfileStore] ve [OpenVpnCompat] kurallarinin masaustu
 * karsiligidir: eski sunucu direktifleri cevrilir, profil eksikse baglanmadan
 * once net bir neden bildirilir.
 */
public final class Profiles {

    public enum Engine { OPENVPN, WIREGUARD }

    public enum Issue { MISSING_CONFIG, TEMPLATE, NO_CA, NO_CLIENT_CERT }

    public record Profile(
            String name,
            String config,
            String host,
            int port,
            String proto,
            Engine engine,
            Issue issue,
            List<String> notes,
            boolean needsCredentials
    ) {
        public boolean isReady() {
            return issue == null && !config.isBlank();
        }

        public String endpoint() {
            return port > 0 ? host + ":" + port : host;
        }
    }

    private static final int DEFAULT_PORT = 1194;
    private static final int DEFAULT_WIREGUARD_PORT = 51820;
    private static final Pattern CERT = Pattern.compile("-----BEGIN CERTIFICATE-----");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern AUTH_USER_PASS = Pattern.compile("(?im)^\\s*auth-user-pass\\b.*$");
    private static final Pattern LEGACY_CIPHERS = Pattern.compile(
            "^(bf-cbc|cast5-cbc|des-cbc|des-ede3-cbc|rc2-cbc|rc2-40-cbc|rc2-64-cbc|idea-cbc|seed-cbc|none)$");

    private Profiles() {
    }

    // ------------------------------------------------------------------ yukleme

    public static Profile load() {
        Engine engine = engine();
        return engine == Engine.OPENVPN ? loadOpenVpn() : loadWireGuard();
    }

    public static Profile load(Engine engine) {
        return engine == Engine.OPENVPN ? loadOpenVpn() : loadWireGuard();
    }

    private static Profile loadOpenVpn() {
        Path file = openVpnFile();
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.isBlank() && isValidOpenVpn(text)) {
                    return parseOpenVpn(text, "Özel profil (OpenVPN)");
                }
            } catch (IOException ignored) {
                // Asagidaki "profil yok" durumuna duser.
            }
        }
        return new Profile("OpenVPN profili", "", "yapılandırma yok", 0, "udp",
                Engine.OPENVPN, Issue.MISSING_CONFIG, List.of(), false);
    }

    private static Profile loadWireGuard() {
        Path file = wireGuardFile();
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.isBlank() && isValidWireGuard(text)) {
                    return parseWireGuard(text, "Özel profil (WireGuard)");
                }
            } catch (IOException ignored) {
                // Asagidaki "profil yok" durumuna duser.
            }
        }
        return new Profile("WireGuard profili", "", "yapılandırma yok", 0, "wireguard",
                Engine.WIREGUARD, Issue.MISSING_CONFIG, List.of(), false);
    }

    /** Dosya seciciden gelen profili dogrular ve uygulama klasorune kopyalar. */
    public static Profile importFrom(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        if (text.isBlank()) throw new IOException("Dosya boş.");
        String name = source.getFileName().toString();

        if (looksLikeWireGuard(text)) {
            if (!isValidWireGuard(text)) throw new IOException("Geçerli bir WireGuard yapılandırması değil.");
            Files.createDirectories(AppPaths.profilesDir());
            Files.writeString(wireGuardFile(), text, StandardCharsets.UTF_8);
            setEngine(Engine.WIREGUARD);
            return parseWireGuard(text, name);
        }

        if (!isValidOpenVpn(text)) {
            throw new IOException("Geçerli bir OpenVPN yapılandırması değil (remote/client satırları eksik).");
        }
        Files.createDirectories(AppPaths.profilesDir());
        Files.writeString(openVpnFile(), text, StandardCharsets.UTF_8);
        setEngine(Engine.OPENVPN);
        return parseOpenVpn(text, name);
    }

    public static void reset() {
        try {
            Files.deleteIfExists(openVpnFile());
            Files.deleteIfExists(wireGuardFile());
        } catch (IOException ignored) {
            // Dosya silinemezse kullanici yine de yeni profil ice aktarabilir.
        }
        setEngine(Engine.OPENVPN);
    }

    // ----------------------------------------------------------------- OpenVPN

    private static Profile parseOpenVpn(String rawConfig, String name) {
        boolean legacy = legacy();
        Result compat = legacy ? applyLegacy(rawConfig) : new Result(rawConfig, List.of());
        String config = compat.config();

        boolean needsCredentials = AUTH_USER_PASS.matcher(config).find()
                && !AUTH_USER_PASS.matcher(config).results()
                .anyMatch(r -> r.group().trim().split("\\s+").length > 1);

        Issue issue = diagnose(config, needsCredentials);
        Triple remote = parseRemote(config);
        return new Profile(
                name,
                config,
                remote != null ? remote.host() : "bilinmeyen sunucu",
                remote != null ? remote.port() : DEFAULT_PORT,
                remote != null ? remote.proto() : "udp",
                Engine.OPENVPN,
                issue,
                compat.notes(),
                needsCredentials
        );
    }

    private static Issue diagnose(String config, boolean needsCredentials) {
        if (config.isBlank() || !isValidOpenVpn(config)) return Issue.MISSING_CONFIG;
        String lower = config.toLowerCase(Locale.ROOT);
        if (lower.contains("ornek-sunucu") || lower.contains("sunucunuzun ca sertifikas")) {
            return Issue.TEMPLATE;
        }
        String ca = block(config, "ca");
        if (ca == null || !CERT.matcher(ca).find()) return Issue.NO_CA;

        String cert = block(config, "cert");
        String key = block(config, "key");
        boolean hasCert = cert != null && CERT.matcher(cert).find();
        boolean hasKey = key != null && PRIVATE_KEY.matcher(key).find();
        if (!hasCert && !hasKey && !needsCredentials) return Issue.NO_CLIENT_CERT;
        return null;
    }

    public static String block(String config, String tag) {
        int start = config.indexOf("<" + tag + ">");
        if (start < 0) return null;
        int end = config.indexOf("</" + tag + ">", start);
        if (end < 0) return null;
        return config.substring(start + tag.length() + 2, end);
    }

    public static boolean isValidOpenVpn(String config) {
        boolean remote = false;
        boolean client = false;
        for (String raw : config.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("remote ")) remote = true;
            if (line.equals("client") || line.startsWith("pull") || line.startsWith("dev ")) client = true;
        }
        return remote && client;
    }

    public record Triple(String host, int port, String proto) { }

    public static Triple parseRemote(String config) {
        for (String raw : config.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("remote ")) continue;
            String[] tokens = line.split("\\s+");
            if (tokens.length < 2) continue;
            if (tokens[1].equals("random") || tokens[1].equals("remote_host")) continue;
            int port = DEFAULT_PORT;
            try {
                port = Integer.parseInt(tokens[2]);
            } catch (Exception ignored) {
                // Port yazilmamissa varsayilan kullanilir.
            }
            String proto = tokens.length > 3 && tokens[3].contains("tcp") ? "tcp-client" : "udp";
            return new Triple(tokens[1], port, proto);
        }
        return null;
    }

    /**
     * Kullanici adi/parola isteyen profillerde `auth-user-pass` satirini
     * kimlik dosyasina yonlendirir. Windows OpenVPN bu dosyayi okuyabilir.
     */
    public static String withCredentials(String config, Path credentialsFile) {
        String path = credentialsFile.toAbsolutePath().toString();
        Matcher matcher = AUTH_USER_PASS.matcher(config);
        if (!matcher.find()) return config;
        return matcher.replaceFirst(Matcher.quoteReplacement(
                "auth-user-pass \"" + path.replace("\\", "/") + "\""));
    }

    /** Sonsuz yeniden deneme yerine anlamli bir son ve hata uretir. */
    public static String withConnectionLimits(String config) {
        StringBuilder extra = new StringBuilder();
        String lower = config.toLowerCase(Locale.ROOT);
        if (!lower.contains("connect-retry-max")) extra.append("connect-retry-max 4\n");
        if (!lower.contains("resolv-retry")) extra.append("resolv-retry 30\n");
        if (!lower.contains("\nverb ") && !lower.startsWith("verb ")) extra.append("verb 3\n");
        return extra.isEmpty() ? config : config.trim() + "\n" + extra;
    }

    // --------------------------------------------------------------- WireGuard

    private static Profile parseWireGuard(String config, String name) {
        String host = "bilinmeyen sunucu";
        int port = DEFAULT_WIREGUARD_PORT;
        for (String raw : config.split("\\R")) {
            String line = raw.trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("endpoint")) continue;
            String value = line.substring(line.indexOf('=') + 1).trim();
            int separator = value.lastIndexOf(':');
            if (separator > 0) {
                host = value.substring(0, separator).replace("[", "").replace("]", "");
                try {
                    port = Integer.parseInt(value.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    // Varsayilan port kullanilir.
                }
            }
        }
        return new Profile(name, config, host, port, "wireguard", Engine.WIREGUARD,
                config.isBlank() ? Issue.MISSING_CONFIG : null, List.of(), false);
    }

    public static boolean isValidWireGuard(String config) {
        String normalised = config.replace("\r\n", "\n");
        return normalised.contains("[Interface]")
                && normalised.toLowerCase(Locale.ROOT).contains("privatekey")
                && normalised.contains("[Peer]")
                && normalised.toLowerCase(Locale.ROOT).contains("publickey")
                && normalised.toLowerCase(Locale.ROOT).contains("endpoint");
    }

    public static boolean looksLikeWireGuard(String config) {
        String normalised = config.replace("\r\n", "\n");
        return normalised.contains("[Interface]")
                || (normalised.toLowerCase(Locale.ROOT).contains("privatekey")
                && normalised.contains("[Peer]"));
    }

    // ---------------------------------------------------------------- ayarlar

    public static Engine engine() {
        String value = prefs().getProperty("engine", Engine.OPENVPN.name());
        return Engine.WIREGUARD.name().equals(value) ? Engine.WIREGUARD : Engine.OPENVPN;
    }

    public static void setEngine(Engine engine) {
        Properties properties = prefs();
        properties.setProperty("engine", engine.name());
        savePrefs(properties);
    }

    public static boolean legacy() {
        return Boolean.parseBoolean(prefs().getProperty("legacy", "true"));
    }

    /** Uygulama acilirken tuneli kendiliginden kur. */
    public static boolean autoConnect() {
        return Boolean.parseBoolean(prefs().getProperty("auto_connect", "false"));
    }

    public static void setAutoConnect(boolean enabled) {
        Properties properties = prefs();
        properties.setProperty("auto_connect", Boolean.toString(enabled));
        savePrefs(properties);
    }

    public static void setLegacy(boolean enabled) {
        Properties properties = prefs();
        properties.setProperty("legacy", Boolean.toString(enabled));
        savePrefs(properties);
    }

    private static Properties prefs() {
        Properties properties = new Properties();
        Path file = AppPaths.settingsFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Varsayilan degerler kullanilir.
            }
        }
        return properties;
    }

    private static void savePrefs(Properties properties) {
        try (OutputStream out = Files.newOutputStream(AppPaths.settingsFile())) {
            properties.store(out, "MDC VPN settings");
        } catch (IOException ignored) {
            // Ayar yazilamazsa uygulama varsayilanlarla devam eder.
        }
    }

    private static Path openVpnFile() {
        return AppPaths.profilesDir().resolve("custom.ovpn");
    }

    private static Path wireGuardFile() {
        return AppPaths.profilesDir().resolve("custom.conf");
    }

    // ------------------------------------------------------- eski sunucu destegi

    public record Result(String config, List<String> notes) { }

    /**
     * Android surumundeki OpenVpnCompat ile ayni kurallar: OVPN3/OpenVPN 2.x
     * tarafindan kaldirilan direktifler modern karsiliklarina cevrilir.
     */
    public static Result applyLegacy(String config) {
        if (config == null || config.isBlank()) return new Result(config, List.of());

        List<String> notes = new ArrayList<>();
        List<String> output = new ArrayList<>();
        boolean legacyTls = false;
        boolean hasCertProfile = false;
        boolean hasCompressionRule = false;

        String normalised = config.replace("\r\n", "\n").replace("\uFEFF", "");
        for (String rawLine : normalised.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                output.add(line);
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            switch (tokens[0].toLowerCase(Locale.ROOT)) {
                case "cipher" -> {
                    String value = tokens.length > 1 ? tokens[1].toLowerCase(Locale.ROOT) : "";
                    if (LEGACY_CIPHERS.matcher(value).matches()) {
                        output.add("cipher AES-256-CBC");
                        notes.add("cipher " + tokens[1] + " → AES-256-CBC");
                    } else {
                        output.add(line);
                    }
                }
                case "ns-cert-type" -> {
                    String value = tokens.length > 1 ? tokens[1].toLowerCase(Locale.ROOT) : "";
                    if (value.equals("server") || value.equals("client")) {
                        output.add("remote-cert-tls " + value);
                        notes.add("ns-cert-type " + value + " → remote-cert-tls " + value);
                    } else {
                        notes.add("ns-cert-type kaldırıldı");
                    }
                }
                case "tls-remote" -> {
                    if (tokens.length < 2) {
                        notes.add("tls-remote kaldırıldı");
                    } else {
                        output.add("verify-x509-name " + tokens[1] + " name");
                        notes.add("tls-remote " + tokens[1] + " → verify-x509-name");
                    }
                }
                case "comp-lzo", "compress" -> {
                    if (!hasCompressionRule) {
                        output.add("allow-compression no");
                        hasCompressionRule = true;
                    }
                    notes.add(tokens[0] + " kaldırıldı → allow-compression no");
                }
                case "allow-compression" -> {
                    hasCompressionRule = true;
                    output.add(line);
                }
                case "tls-cert-profile" -> {
                    hasCertProfile = true;
                    output.add(line);
                }
                case "tls-version-min" -> {
                    if (tokens.length > 1 && (tokens[1].equals("1.0") || tokens[1].equals("1.1"))) {
                        legacyTls = true;
                    }
                    output.add(line);
                }
                default -> output.add(line);
            }
        }

        if (legacyTls && !hasCertProfile) {
            output.add("tls-cert-profile legacy");
            notes.add("tls-version-min ≤ 1.1 → tls-cert-profile legacy eklendi");
        }

        if (notes.isEmpty()) return new Result(config, List.of());
        return new Result(String.join("\n", output).stripTrailing() + "\n", notes);
    }
}
