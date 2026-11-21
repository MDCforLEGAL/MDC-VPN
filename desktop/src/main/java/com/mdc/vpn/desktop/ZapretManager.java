package com.mdc.vpn.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Windows zapret (winws / winws2) yonetimi.
 *
 * Zapret, WinDivert surucusu uzerinden TCP/UDP paketlerini yeniden duzenleyerek
 * DPI filtrelerini atlatir. Bu sinif hazir Turkiye stratejilerini
 * (alimali54/zapret-win-turkey) uygulamanin yanindaki `zapret` klasorunden
 * gizli bir surec olarak baslatir ve durdurur.
 */
public final class ZapretManager {

    public enum Engine {
        ZAPRET1("Zapret 1", "winws.exe"),
        ZAPRET2("Zapret 2", "winws2.exe");

        final String label;
        final String exe;

        Engine(String label, String exe) {
            this.label = label;
            this.exe = exe;
        }
    }

    /** Hazir strateji: ad + motor + winws argumanlari. */
    public record Preset(String id, String name, Engine engine, String args) {
    }

    private static final List<Preset> PRESETS = List.of(
            // Zapret 1 (klasik winws) - zapret-win-turkey hazir profilleri
            new Preset("tt", "Türk Telekom", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-ttl=4"),
            new Preset("tt-alt", "Türk Telekom (alternatif)", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-ttl=3"),
            new Preset("sol", "Superonline", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-fooling=md5sig"),
            new Preset("sol-alt", "Superonline (alternatif)", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-fooling=md5sig --dpi-desync-ttl=3"),
            new Preset("vodafone", "Vodafone", Engine.ZAPRET1,
                    "--dpi-desync=fake,multisplit --dpi-desync-fooling=badseq --dpi-desync-split-pos=1,midsld"),
            new Preset("turkcell-mobil", "Turkcell Mobil", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-ttl=1 --dpi-desync-autottl=3"),
            new Preset("vodafone-mobil", "Vodafone Mobil", Engine.ZAPRET1,
                    "--dpi-desync=multisplit --dpi-desync-split-pos=2"),
            new Preset("tt-mobil", "Türk Telekom Mobil", Engine.ZAPRET1,
                    "--dpi-desync=fake --dpi-desync-ttl=5"),

            // Zapret 2 (winws2 + Lua) - ayni repodaki strateji2 profilleri
            new Preset("z2-tt", "Türk Telekom", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multidisorder:pos=2:seqovl=1"),
            new Preset("z2-sol", "Superonline", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multidisorder:pos=2:seqovl=1"),
            new Preset("z2-vodafone", "Vodafone", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multisplit:blob=fake_default_tls:ip_ttl=5:pos=2:nodrop:repeats=1"),
            new Preset("z2-kablonet", "Türksat Kablonet", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multidisorder:pos=2:seqovl=1"),
            new Preset("z2-tt-mobil", "Türk Telekom Mobil", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=fake:blob=0x00000000:ip_ttl=5:repeats=1"),
            new Preset("z2-turkcell", "Turkcell Mobil", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multidisorder:pos=2:seqovl=1"),
            new Preset("z2-vodafone-mobil", "Vodafone Mobil", Engine.ZAPRET2,
                    "--payload=tls_client_hello --lua-desync=multidisorder:pos=2:seqovl=1")
    );

    private static final String KEY_ENGINE = "zapret.engine";
    private static final String KEY_PRESET = "zapret.preset";
    private static final String KEY_ONLY_LISTED = "zapret.only_listed";

    private ProcessHandle handle;
    private Preset active;
    private String lastError = "";

    public static List<Preset> presets(Engine engine) {
        return PRESETS.stream().filter(p -> p.engine() == engine).toList();
    }

    public static List<Preset> allPresets() {
        return PRESETS;
    }

    /** Uygulamanin yanindaki zapret klasoru. */
    public static Path directory() {
        return AppPaths.installDir().resolve("zapret");
    }

    public static Path executable(Engine engine) {
        return directory().resolve(engine.exe);
    }

    public boolean isRunning() {
        return handle != null && handle.isAlive();
    }

    public Preset activePreset() {
        return active;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * Secilen stratejiyi gizli surec olarak baslatir.
     *
     * @param onlyListed true ise yalnizca hostlist.txt icindeki alan adlarina
     *                   uygulanir; false ise tum trafik islenir.
     */
    public synchronized void start(Preset preset, boolean onlyListed) throws IOException {
        stop();
        lastError = "";

        Path exe = executable(preset.engine());
        if (!Files.isRegularFile(exe)) {
            throw new IOException("Zapret bulunamadı. Beklenen konum: " + exe
                    + "\n\nMDC VPN Windows paketi zapret klasörünü içerir; "
                    + "portatif sürümde zapret klasörünün uygulamanın yanında olduğundan emin olun.");
        }
        Path dir = directory();
        Path divert = dir.resolve("WinDivert.dll");
        if (!Files.isRegularFile(divert)) {
            throw new IOException("WinDivert.dll zapret klasöründe bulunamadı: " + divert);
        }

        List<String> args = new ArrayList<>();
        if (preset.engine() == Engine.ZAPRET1) {
            args.add("--wf-tcp=0-65535");
            args.add("--wf-udp=0-65535");
        } else {
            args.add("--wf-l3=ipv4");
            args.add("--wf-tcp-out=0-65535");
            args.add("--wf-udp-out=0-65535");
        }

        Path excludelist = dir.resolve("excludelist.txt");
        if (Files.isRegularFile(excludelist)) {
            args.add("--hostlist-exclude=" + excludelist);
        }
        args.addAll(splitArgs(preset.args()));
        if (onlyListed) {
            Path hostlist = dir.resolve("hostlist.txt");
            if (Files.isRegularFile(hostlist)) args.add("--hostlist=" + hostlist);
        }

        if (preset.engine() == Engine.ZAPRET2) {
            Path lua = dir.resolve("lua");
            args.add("--lua-init=@" + lua.resolve("zapret-lib.lua"));
            args.add("--lua-init=@" + lua.resolve("zapret-antidpi.lua"));
            args.add("--lua-init=@" + lua.resolve("zapret-auto.lua"));
        }

        handle = AppPaths.launchHidden(exe, args, dir);
        active = preset;

        // WinDivert surucusu yuklenemezse winws hemen kapanir.
        try {
            TimeUnit.MILLISECONDS.sleep(1_200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!handle.isAlive()) {
            handle = null;
            active = null;
            lastError = "Zapret başlatılamadı; süreç hemen kapandı. "
                    + "Yönetici iznini ve WinDivert sürücüsünü kontrol edin.";
            throw new IOException(lastError);
        }

        saveSelection(preset.engine(), preset.id(), onlyListed);
    }

    public synchronized void stop() {
        if (handle != null) {
            handle.descendants().forEach(ProcessHandle::destroy);
            handle.destroy();
            handle = null;
        }
        active = null;
    }

    /** Zapret'in bu makinede kullanilabilir olup olmadigi. */
    public static boolean isBundled() {
        return Files.isRegularFile(executable(Engine.ZAPRET1))
                || Files.isRegularFile(executable(Engine.ZAPRET2));
    }

    public static Engine savedEngine() {
        String value = readSetting(KEY_ENGINE, "");
        return "ZAPRET2".equals(value) ? Engine.ZAPRET2 : Engine.ZAPRET1;
    }

    public static String savedPresetId() {
        return readSetting(KEY_PRESET, "");
    }

    public static void saveSelection(Engine engine, String presetId, boolean onlyListed) {
        Properties properties = loadSettings();
        properties.setProperty(KEY_ENGINE, engine.name());
        properties.setProperty(KEY_PRESET, presetId);
        properties.setProperty(KEY_ONLY_LISTED, Boolean.toString(onlyListed));
        try (OutputStream out = Files.newOutputStream(AppPaths.settingsFile())) {
            properties.store(out, "MDC VPN ayarlari");
        } catch (IOException ignored) {
            // Ayar kaydedilemezse varsayilanlar kullanilir.
        }
    }

    public static boolean savedOnlyListed() {
        return Boolean.parseBoolean(readSetting(KEY_ONLY_LISTED, "false"));
    }

    private static String readSetting(String key, String fallback) {
        return loadSettings().getProperty(key, fallback);
    }

    private static Properties loadSettings() {
        Properties properties = new Properties();
        Path file = AppPaths.settingsFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Bozuk ayar dosyasi varsayilanlara duser.
            }
        }
        return properties;
    }

    /** Boslukla ayrilmis argumanlari tirnaklari koruyarak ayirir. */
    static List<String> splitArgs(String raw) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (char ch : raw.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(ch);
            }
        }
        if (token.length() > 0) result.add(token.toString());
        return result;
    }
}
