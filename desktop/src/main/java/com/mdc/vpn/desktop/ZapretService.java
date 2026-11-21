package com.mdc.vpn.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Zapret'i Windows acilisinda, oturum acmadan calisan bir gorev olarak kurar.
 *
 * winws bir Windows servisi degildir (SCM ile iletisim kuramaz), bu yuzden
 * ayni isi goren ve SYSTEM yetkisiyle onyuklemede baslayan bir Gorev
 * Zamanlayici kaydi olusturulur. Kayit kaldirilana kadar her acilista
 * kendiliginden calisir.
 */
public final class ZapretService {

    public static final String TASK = "MDC VPN DPI";

    private ZapretService() {
    }

    /** Kayitli gorev var mi? */
    public static boolean isInstalled() {
        try {
            return run(List.of("schtasks.exe", "/Query", "/TN", TASK)).exit == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Zapret su anda servis/gorev uzerinden calisiyor mu? */
    public static boolean isRunning() {
        return ProcessHandle.allProcesses().anyMatch(process -> {
            String command = process.info().command().orElse("");
            String name = command.toLowerCase(Locale.ROOT);
            if (!(name.endsWith("winws.exe") || name.endsWith("winws2.exe"))) return false;
            return command.contains("zapret") || command.contains("Zapret");
        });
    }

    /** Secilen stratejiyi onyukleme gorevi olarak kurar ve hemen baslatir. */
    public static void install(ZapretManager.Preset preset, boolean onlyListed) throws IOException {
        if (!AppPaths.isWindows()) {
            throw new IOException("Servis yalnızca Windows'ta kurulabilir.");
        }
        Path xml = AppPaths.dataDir().resolve("dpi-service.xml");
        Files.writeString(xml, taskXml(preset, onlyListed), StandardCharsets.UTF_16);

        Command create = run(List.of("schtasks.exe", "/Create",
                "/TN", TASK, "/XML", xml.toString(), "/F"));
        if (create.exit != 0) {
            throw new IOException("Görev oluşturulamadı (yönetici izni gerekir): " + create.output);
        }
        run(List.of("schtasks.exe", "/Run", "/TN", TASK));
    }

    /** Onyukleme gorevini durdurur ve siler. */
    public static void uninstall() throws IOException {
        run(List.of("schtasks.exe", "/End", "/TN", TASK));
        Command delete = run(List.of("schtasks.exe", "/Delete", "/TN", TASK, "/F"));
        if (delete.exit != 0) {
            throw new IOException("Görev kaldırılamadı: " + delete.output);
        }
    }

    /** Onyuklemede SYSTEM olarak calisan gorev tanimi. */
    private static String taskXml(ZapretManager.Preset preset, boolean onlyListed) throws IOException {
        Path exe = ZapretManager.executable(preset.engine());
        Path dir = ZapretManager.directory();
        List<String> args = ZapretManager.arguments(preset, onlyListed);

        StringBuilder arguments = new StringBuilder();
        for (String arg : args) {
            if (arguments.length() > 0) arguments.append(' ');
            arguments.append(quote(arg));
        }

        return """
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <RegistrationInfo>
                    <Description>MDC VPN DPI atlatma (Zapret)</Description>
                  </RegistrationInfo>
                  <Triggers>
                    <BootTrigger>
                      <Enabled>true</Enabled>
                    </BootTrigger>
                  </Triggers>
                  <Principals>
                    <Principal id="Author">
                      <UserId>S-1-5-18</UserId>
                      <RunLevel>HighestAvailable</RunLevel>
                    </Principal>
                  </Principals>
                  <Settings>
                    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <AllowHardTerminate>true</AllowHardTerminate>
                    <StartWhenAvailable>true</StartWhenAvailable>
                    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                    <Enabled>true</Enabled>
                  </Settings>
                  <Actions Context="Author">
                    <Exec>
                      <Command>%s</Command>
                      <Arguments>%s</Arguments>
                      <WorkingDirectory>%s</WorkingDirectory>
                    </Exec>
                  </Actions>
                </Task>
                """.formatted(xml(exe.toString()), xml(arguments.toString()), xml(dir.toString()));
    }

    private static String quote(String value) {
        String escaped = value.replace("\"", "\\\"");
        return value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0
                ? "\"" + escaped + "\""
                : escaped;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Command(int exit, String output) {
    }

    private static Command run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Command(-1, "zaman aşımı");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Command(-1, "kesildi");
        }
        return new Command(process.exitValue(), output);
    }
}
