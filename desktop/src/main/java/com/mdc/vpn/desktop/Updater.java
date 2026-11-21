package com.mdc.vpn.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Uygulama ici guncelleme.
 *
 * GitHub'daki en son surumu denetler, Windows ZIP'ini indirir ve uygulama
 * kapandiktan sonra dosyalari degistirip yeniden baslatan bir PowerShell
 * yardimcisi birakir (calisan MDC-VPN.exe kendi uzerine yazilamaz).
 */
public final class Updater {

    public record Release(String tag, String version, String url) {
    }

    private static final String LATEST = "https://github.com/MDCforLEGAL/MDC-VPN/releases/latest";
    private static final String ASSET = "MDC-VPN-Windows-x64.zip";
    private static final String USER_AGENT = "MDC-VPN-Updater";

    private Updater() {
    }

    /**
     * Yeni surum varsa dondurur; uygulama guncelse null.
     *
     * GitHub API'si anonim isteklerde siki bir saatlik sinira tabidir; bu
     * yuzden `/releases/latest` yonlendirmesi izlenip son adresten surum
     * etiketi okunur.
     */
    public static Release check() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub yanıtı: HTTP " + response.statusCode());
        }
        Matcher matcher = Pattern.compile("/releases/tag/([^/]+)").matcher(response.uri().getPath());
        if (!matcher.find()) {
            throw new IOException("Sürüm etiketi okunamadı.");
        }
        String tag = matcher.group(1);
        String version = tag.replaceFirst("^[vV]", "");
        if (!isNewer(version, MainWindow.version())) {
            return null;
        }
        return new Release(tag, version,
                "https://github.com/MDCforLEGAL/MDC-VPN/releases/download/" + tag + "/" + ASSET);
    }

    /** Guncelleme ZIP'ini indirir ve yolunu dondurur. */
    public static Path download(Release release, IntConsumer progress)
            throws IOException, InterruptedException {
        Path dir = AppPaths.dataDir().resolve("update");
        Files.createDirectories(dir);
        Path target = dir.resolve(ASSET);
        Path partial = dir.resolve(ASSET + ".part");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.url()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("İndirme başarısız: HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int read;
            int reported = -1;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                written += read;
                if (total > 0) {
                    int percent = (int) Math.min(100, written * 100 / total);
                    if (percent != reported) {
                        reported = percent;
                        progress.accept(percent);
                    }
                }
            }
        }
        if (Files.size(partial) < 1_000_000) {
            Files.deleteIfExists(partial);
            throw new IOException("İndirilen paket beklenenden küçük.");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * ZIP'i acar ve uygulama kapandiktan sonra dosyalari degistirip yeniden
     * baslatan yardimciyi calistirir.
     */
    public static void apply(Path zip) throws IOException {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            throw new IOException("Güncelleme yalnızca kurulu Windows uygulamasında uygulanabilir.");
        }
        Path installDir = AppPaths.installDir();
        if (!Files.isWritable(installDir)) {
            throw new IOException("Uygulama klasörüne yazma izni yok. Güncellemeyi yönetici olarak çalıştırın.");
        }
        Path updateDir = AppPaths.dataDir().resolve("update");
        Path staging = updateDir.resolve("staging");
        deleteTree(staging);
        Files.createDirectories(staging);

        unzip(zip, staging);
        Path source = staging.resolve("MDC-VPN");
        if (!Files.isRegularFile(source.resolve("MDC-VPN.exe"))) {
            throw new IOException("Güncelleme paketi beklenen uygulama dosyalarını içermiyor.");
        }

        Path script = updateDir.resolve("apply-update.ps1");
        Files.writeString(script, helperScript(source, installDir, ProcessHandle.current().pid()),
                StandardCharsets.UTF_8);

        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString())
                .redirectErrorStream(true)
                .start();
    }

    /** Calisan surec kapanana kadar bekleyip dosyalari kopyalar. */
    private static String helperScript(Path source, Path target, long pid) {
        String sourcePath = source.toAbsolutePath().toString().replace("'", "''");
        String targetPath = target.toAbsolutePath().toString().replace("'", "''");
        String scriptPath = Path.of(AppPaths.dataDir().toString(), "update")
                .toAbsolutePath().toString().replace("'", "''");
        return """
                $ErrorActionPreference = 'SilentlyContinue'
                $processId = %d
                $source = '%s'
                $target = '%s'
                while (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
                    Start-Sleep -Milliseconds 700
                }
                Start-Sleep -Milliseconds 600
                robocopy $source $target /E /R:3 /W:1 /NFL /NDL /NJH /NJS | Out-Null
                Start-Process -FilePath (Join-Path $target 'MDC-VPN.exe')
                Start-Sleep -Seconds 3
                Remove-Item -LiteralPath '%s' -Recurse -Force
                Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force
                """.formatted(pid, sourcePath, targetPath, scriptPath);
    }

    /** ZIP'i hedef dizine, dizin disina tasmadan acar. */
    private static void unzip(Path zip, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Geçersiz arşiv girdisi: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    /** "1.0.12" > "1.0.9" gibi sayisal karsilastirma. */
    static boolean isNewer(String candidate, String current) {
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = number(left, i);
            int b = number(right, i);
            if (a != b) return a > b;
        }
        return false;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
