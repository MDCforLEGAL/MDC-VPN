package com.mdc.vpn.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * DPI stratejisi otomatik bulucu.
 *
 * Turkiye'de engellenen sitelere (Discord, Instagram) once atlatma olmadan
 * erisilir; erisilemiyorsa butun zapret stratejileri sirayla baslatilir ve
 * gercekten sayfa acan ilk strateji secilir.
 */
public final class DpiStrategyFinder {

    /** Bulunan (veya bulunamayan) stratejinin sonucu. */
    public record Result(ZapretManager.Preset preset, boolean bypassNeeded, String detail) {
    }

    private static final List<String> PROBE_URLS = List.of(
            "https://discord.com/",
            "https://www.instagram.com/");

    private final ZapretManager zapret;
    private final Consumer<String> progress;
    private final HttpClient client;

    public DpiStrategyFinder(ZapretManager zapret, Consumer<String> progress) {
        this.zapret = zapret;
        this.progress = progress;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(2500))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Calisan stratejiyi arar. Basarili olursa zapret o stratejiyle calismaya
     * devam eder; bulunamazsa zapret durdurulur.
     */
    public Result find(boolean onlyListed) {
        zapret.stop();

        progress.accept("DPI engeli denetleniyor…");
        if (reachable()) {
            return new Result(null, false, "DPI engeli görünmüyor; atlatma gerekmiyor.");
        }

        List<ZapretManager.Preset> candidates = orderedCandidates();
        if (candidates.isEmpty()) {
            return new Result(null, true, "Zapret ikilileri bulunamadı; otomatik bulucu çalıştırılamadı.");
        }

        for (ZapretManager.Preset preset : candidates) {
            progress.accept("Deneniyor: " + preset.name() + " (" + preset.engine().label + ")");
            try {
                zapret.start(preset, onlyListed);
            } catch (IOException ex) {
                continue;
            }
            if (reachable() || retryReachable()) {
                return new Result(preset, true, "Çalışan strateji: " + preset.name()
                        + " (" + preset.engine().label + ")");
            }
            zapret.stop();
        }
        return new Result(null, true, "Bu ağda çalışan bir strateji bulunamadı; "
                + "strateji listesinden elle deneyin.");
    }

    /** Kayitli motorun stratejileri once denenir. */
    private List<ZapretManager.Preset> orderedCandidates() {
        List<ZapretManager.Preset> result = new ArrayList<>();
        ZapretManager.Engine saved = ZapretManager.savedEngine();
        for (ZapretManager.Preset preset : ZapretManager.allPresets()) {
            if (preset.engine() == saved && java.nio.file.Files.isRegularFile(
                    ZapretManager.executable(preset.engine()))) {
                result.add(preset);
            }
        }
        for (ZapretManager.Preset preset : ZapretManager.allPresets()) {
            if (preset.engine() != saved && java.nio.file.Files.isRegularFile(
                    ZapretManager.executable(preset.engine()))) {
                result.add(preset);
            }
        }
        return result;
    }

    private boolean retryReachable() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return reachable();
    }

    /** Engelli test sitelerinden en az biri gercekten aciliyor mu? */
    private boolean reachable() {
        for (String url : PROBE_URLS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status > 0 && status < 500) return true;
            } catch (Exception ignored) {
                // Sonraki adres denenir.
            }
        }
        return false;
    }
}
