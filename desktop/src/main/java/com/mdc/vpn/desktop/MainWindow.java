package com.mdc.vpn.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Windows arayuzu: durum halkasi, tek dugme ile baglan/kes, profil secimi,
 * protokol secimi ve canli guncel giden/gelen trafik.
 */
public final class MainWindow {

    private static final String LINKS = "https://mdcforlegal.github.io";

    private final JFrame frame = new JFrame("MDC VPN");
    private final Theme.StatusRing ring = new Theme.StatusRing();
    private final JLabel stateTitle = Theme.label("Bağlı değil", Theme.bold(23f), Theme.TEXT);
    private final JLabel stateSubtitle = Theme.label("Güvenli tünel kapalı", Theme.regular(12f), Theme.SECONDARY);
    private final JLabel profileName = Theme.label("—", Theme.medium(15f), Theme.TEXT);
    private final JLabel profileEndpoint = Theme.label("", Theme.regular(12f), Theme.SECONDARY);
    private final JLabel issueLabel = Theme.label("", Theme.regular(11f), Theme.DANGER);
    private final JLabel durationValue = Theme.label("00:00:00", Theme.medium(17f), Theme.TEXT);
    private final JLabel downloadValue = Theme.label("0 B/s", Theme.medium(17f), Theme.TEXT);
    private final JLabel uploadValue = Theme.label("0 B/s", Theme.medium(17f), Theme.TEXT);
    private final Theme.PillToggle openVpnToggle = new Theme.PillToggle("OpenVPN");
    private final Theme.PillToggle wireGuardToggle = new Theme.PillToggle("WireGuard");
    private final Theme.Button connectButton = new Theme.Button("Bağlan", true);
    private final JTextArea logArea = new JTextArea();

    private Profiles.Engine engine = Profiles.engine();
    private Profiles.Profile profile;
    private Tunnel tunnel;
    private Tunnel.State state = Tunnel.State.DISCONNECTED;
    private String stateDetail = "";
    private boolean errorState;
    private long connectedAt;
    private long lastRx;
    private long lastTx;
    private String cachedUser = "";
    private String cachedPassword = "";
    private final Timer ticker;

    public MainWindow() {
        profile = Profiles.load(engine);
        buildUi();
        renderProfile();
        renderState();
        ticker = new Timer(1000, e -> tick());
        ticker.start();
    }

    public void show() {
        frame.setVisible(true);
    }

    // -------------------------------------------------------------------- ui

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(490, 880);
        frame.setMinimumSize(new Dimension(460, 760));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (tunnel != null) tunnel.stop();
                frame.dispose();
                System.exit(0);
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 14, 20));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildContent(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        frame.setContentPane(root);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.X_AXIS));
        JLabel mark = new JLabel("M");
        mark.setFont(Theme.bold(15f));
        mark.setForeground(Theme.ON_ACCENT);
        mark.setHorizontalAlignment(JLabel.CENTER);
        mark.setOpaque(true);
        mark.setBackground(Theme.ACCENT);
        mark.setPreferredSize(new Dimension(34, 34));
        mark.setMaximumSize(new Dimension(34, 34));
        mark.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        brand.add(mark);
        brand.add(Box.createHorizontalStrut(10));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Theme.label("MDC VPN", Theme.bold(17f), Theme.TEXT));
        titles.add(Theme.label("Windows x64", Theme.regular(11f), Theme.MUTED));
        brand.add(titles);

        Theme.Button settings = new Theme.Button("Ayarlar", false);
        settings.setFont(Theme.regular(12f));
        settings.addActionListener(e -> openSettings());

        header.add(brand, BorderLayout.WEST);
        header.add(settings, BorderLayout.EAST);
        return header;
    }

    private JComponent buildContent() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 12, 0);

        content.add(buildStatusCard(), gbc);
        content.add(buildProtocolCard(), gbc);
        content.add(buildProfileCard(), gbc);

        connectButton.setPreferredSize(new Dimension(0, 52));
        connectButton.addActionListener(e -> onConnectClicked());
        content.add(connectButton, gbc);

        content.add(buildStats(), gbc);
        content.add(buildLogCard(), gbc);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildStatusCard() {
        Theme.Card card = new Theme.Card(20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        ring.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(ring);
        card.add(Box.createVerticalStrut(12));

        stateTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        stateSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        stateSubtitle.setHorizontalAlignment(JLabel.CENTER);
        card.add(stateTitle);
        card.add(Box.createVerticalStrut(4));
        card.add(stateSubtitle);
        return card;
    }

    private JComponent buildProtocolCard() {
        Theme.Card card = new Theme.Card(18);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        card.add(Theme.label("PROTOKOL", Theme.medium(10f), Theme.MUTED));
        card.add(Box.createVerticalStrut(8));

        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        row.setPreferredSize(new Dimension(0, 42));
        ButtonGroup group = new ButtonGroup();
        group.add(openVpnToggle);
        group.add(wireGuardToggle);
        openVpnToggle.setSelected(engine == Profiles.Engine.OPENVPN);
        wireGuardToggle.setSelected(engine == Profiles.Engine.WIREGUARD);
        openVpnToggle.addActionListener(e -> selectEngine(Profiles.Engine.OPENVPN));
        wireGuardToggle.addActionListener(e -> selectEngine(Profiles.Engine.WIREGUARD));
        row.add(openVpnToggle);
        row.add(wireGuardToggle);
        card.add(row);
        return card;
    }

    private JComponent buildProfileCard() {
        Theme.Card card = new Theme.Card(18);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        card.setLayout(new BorderLayout(10, 0));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Theme.label("PROFİL", Theme.medium(10f), Theme.MUTED));
        text.add(Box.createVerticalStrut(5));
        text.add(profileName);
        text.add(Box.createVerticalStrut(2));
        text.add(profileEndpoint);
        text.add(Box.createVerticalStrut(3));
        text.add(issueLabel);

        Theme.Button choose = new Theme.Button("Dosya Seç", false);
        choose.setFont(Theme.regular(12f));
        choose.addActionListener(e -> chooseProfile());

        card.add(text, BorderLayout.CENTER);
        card.add(choose, BorderLayout.EAST);
        return card;
    }

    private JComponent buildStats() {
        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.add(statCard("SÜRE", durationValue));
        row.add(statCard("İNDİRME", downloadValue));
        row.add(statCard("YÜKLEME", uploadValue));
        return row;
    }

    private JComponent statCard(String title, JLabel value) {
        Theme.Card card = new Theme.Card(16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
        card.add(Theme.label(title, Theme.medium(9.5f), Theme.MUTED));
        card.add(Box.createVerticalStrut(5));
        card.add(value);
        return card;
    }

    private JComponent buildLogCard() {
        Theme.Card card = new Theme.Card(18);
        card.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        card.setLayout(new BorderLayout(0, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Theme.label("GÜNLÜK", Theme.medium(10f), Theme.MUTED), BorderLayout.WEST);

        Theme.Button clear = new Theme.Button("Temizle", false);
        clear.setFont(Theme.regular(10f));
        clear.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        clear.addActionListener(e -> logArea.setText(""));
        header.add(clear, BorderLayout.EAST);

        logArea.setEditable(false);
        logArea.setFont(Theme.mono(11f));
        logArea.setBackground(Theme.BG);
        logArea.setForeground(Theme.SECONDARY);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        logArea.setRows(6);
        logArea.setLineWrap(false);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.STROKE));
        scroll.setPreferredSize(new Dimension(0, 120));
        scroll.getViewport().setBackground(Theme.BG);
        scroll.setBackground(Theme.BG);

        card.add(header, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(12, 2, 0, 2));
        footer.add(Theme.label("OpenVPN • WireGuard • MDC VPN", Theme.regular(10.5f), Theme.MUTED),
                BorderLayout.WEST);
        footer.add(Theme.label("v" + version(), Theme.regular(10.5f), Theme.MUTED), BorderLayout.EAST);
        return footer;
    }

    // --------------------------------------------------------------- actions

    private void onConnectClicked() {
        if (state == Tunnel.State.CONNECTED || state == Tunnel.State.CONNECTING) {
            stopTunnel();
            return;
        }
        if (state == Tunnel.State.DISCONNECTING) return;

        if (profile == null) profile = Profiles.load(engine);
        if (!profile.isReady()) {
            showProfileIssue(profile);
            return;
        }
        if (!AppPaths.isElevated()) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    "VPN adaptörü oluşturmak ve tüneli açmak için yönetici izni gerekiyor.\n\n"
                            + "Uygulama yönetici olarak yeniden başlatılsın mı?",
                    "Yönetici izni gerekli", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                try {
                    AppPaths.relaunchElevated();
                    frame.dispose();
                    System.exit(0);
                } catch (IOException ex) {
                    showError("Yönetici olarak başlatılamadı: " + ex.getMessage());
                }
            }
            return;
        }

        String username = null;
        String password = null;
        if (profile.needsCredentials()) {
            JTextField userField = new JTextField(cachedUser);
            JPasswordField passField = new JPasswordField(cachedPassword);
            JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
            panel.setOpaque(false);
            panel.add(Theme.label("Kullanıcı adı", Theme.regular(12f), Theme.SECONDARY));
            panel.add(userField);
            panel.add(Theme.label("Parola", Theme.regular(12f), Theme.SECONDARY));
            panel.add(passField);
            int result = JOptionPane.showConfirmDialog(frame, panel, "Sunucu kimliği",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;
            username = userField.getText().trim();
            password = new String(passField.getPassword());
            cachedUser = username;
            cachedPassword = password;
        }

        final String finalUser = username;
        final String finalPassword = password;
        final Profiles.Profile current = profile;
        final Tunnel next = engine == Profiles.Engine.OPENVPN ? new OpenVpnTunnel() : new WireGuardTunnel();
        tunnel = next;
        setState(Tunnel.State.CONNECTING, "Bağlanılıyor…");
        appendLog("Profil: " + current.name() + " • " + current.endpoint()
                + " • " + current.engine().name());

        Thread worker = new Thread(() -> {
            try {
                next.start(current, finalUser, finalPassword, tunnelListener);
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                appendLog("HATA: " + message);
                SwingUtilities.invokeLater(() -> {
                    if (tunnel == next) tunnel = null;
                    setState(Tunnel.State.DISCONNECTED, message);
                });
            }
        }, "tunnel-start");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopTunnel() {
        Tunnel current = tunnel;
        if (current == null) {
            setState(Tunnel.State.DISCONNECTED, "");
            return;
        }
        setState(Tunnel.State.DISCONNECTING, "Kapatılıyor…");
        Thread worker = new Thread(() -> {
            current.stop();
            SwingUtilities.invokeLater(() -> {
                if (tunnel == current) tunnel = null;
                setState(Tunnel.State.DISCONNECTED, "Bağlantı kesildi");
            });
        }, "tunnel-stop");
        worker.setDaemon(true);
        worker.start();
    }

    private void chooseProfile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("VPN profili seç");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "VPN profilleri (*.ovpn, *.conf, *.wg)", "ovpn", "conf", "wg"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        try {
            if (state != Tunnel.State.DISCONNECTED) stopTunnel();
            Profiles.Profile imported = Profiles.importFrom(source);
            engine = imported.engine();
            profile = imported;
            openVpnToggle.setSelected(engine == Profiles.Engine.OPENVPN);
            wireGuardToggle.setSelected(engine == Profiles.Engine.WIREGUARD);
            renderProfile();
            if (!imported.notes().isEmpty()) {
                StringBuilder notes = new StringBuilder("Eski sunucu uyumluluğu uygulandı:\n\n");
                for (String note : imported.notes()) notes.append("• ").append(note).append('\n');
                JOptionPane.showMessageDialog(frame, notes.toString(),
                        "Profil hazırlandı", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            showError("Profil yüklenemedi: " + ex.getMessage());
        }
    }

    private void selectEngine(Profiles.Engine next) {
        if (state != Tunnel.State.DISCONNECTED) {
            openVpnToggle.setSelected(engine == Profiles.Engine.OPENVPN);
            wireGuardToggle.setSelected(engine == Profiles.Engine.WIREGUARD);
            showError("Protokol değiştirmek için önce bağlantıyı kesin.");
            return;
        }
        engine = next;
        Profiles.setEngine(next);
        profile = Profiles.load(next);
        renderProfile();
    }

    private void showProfileIssue(Profiles.Profile current) {
        String message;
        if (current.issue() == null) {
            message = "Bu profil bağlanmaya hazır değil.";
        } else {
            message = switch (current.issue()) {
                case MISSING_CONFIG ->
                        "Henüz bir yapılandırma yüklenmemiş.\n\nOpenVPN için .ovpn, WireGuard için .conf dosyası içe aktarın.";
                case TEMPLATE ->
                        "Gömülü varsayılan profil yalnızca bir şablondur.\n\nSunucunuzun kendi .ovpn dosyasını içe aktarın.";
                case NO_CA ->
                        "Profilde sunucu sertifikasını doğrulayacak CA sertifikası yok.\n\n"
                                + "<ca> bloğunu profilin içine gömün; harici dosya referansları okunamaz.";
                case NO_CLIENT_CERT ->
                        "İstemci sertifikası veya özel anahtarı eksik.\n\n<cert> ve <key> bloklarını profilin içine gömün.";
            };
        }
        int answer = JOptionPane.showConfirmDialog(frame, message, "Profil bağlanmaya hazır değil",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) chooseProfile();
    }

    private void showError(String message) {
        appendLog("HATA: " + message);
        JOptionPane.showMessageDialog(frame, message, "MDC VPN", JOptionPane.ERROR_MESSAGE);
    }

    private void openSettings() {
        JDialog dialog = new JDialog(frame, "Ayarlar", true);
        dialog.setSize(430, 360);
        dialog.setLocationRelativeTo(frame);

        JPanel content = new JPanel();
        content.setBackground(Theme.BG);
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.add(Theme.label("BAĞLANTI", Theme.medium(10f), Theme.MUTED));
        content.add(Box.createVerticalStrut(10));

        JCheckBox legacy = new JCheckBox("Eski sunucu uyumluluğu", Profiles.legacy());
        legacy.setOpaque(false);
        legacy.setForeground(Theme.TEXT);
        legacy.setFont(Theme.regular(13f));
        legacy.setFocusPainted(false);
        legacy.addActionListener(e -> {
            Profiles.setLegacy(legacy.isSelected());
            profile = Profiles.load(engine);
            renderProfile();
        });
        content.add(legacy);
        content.add(Box.createVerticalStrut(6));
        JLabel legacyHint = Theme.label(
                "<html>BF-CBC, ns-cert-type, tls-remote, LZO ve TLS 1.0 gibi eski<br>"
                        + "direktifleri modern karşılıklarına çevirir.</html>",
                Theme.regular(11f), Theme.MUTED);
        content.add(legacyHint);
        content.add(Box.createVerticalStrut(18));

        Theme.Button reset = new Theme.Button("Profili sıfırla", false);
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        reset.addActionListener(e -> {
            int answer = JOptionPane.showConfirmDialog(dialog,
                    "İçe aktarılan profil silinsin mi?", "Profili sıfırla",
                    JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) {
                Profiles.reset();
                engine = Profiles.Engine.OPENVPN;
                profile = Profiles.load(engine);
                openVpnToggle.setSelected(true);
                renderProfile();
                dialog.dispose();
            }
        });
        content.add(reset);
        content.add(Box.createVerticalStrut(8));

        Theme.Button links = new Theme.Button("Diğer içeriklerim", false);
        links.setAlignmentX(Component.LEFT_ALIGNMENT);
        links.addActionListener(e -> browse(LINKS));
        content.add(links);
        content.add(Box.createVerticalStrut(20));

        content.add(Theme.label("OpenVPN", Theme.medium(10f), Theme.MUTED));
        JLabel openVpnPath = Theme.label(AppPaths.openVpnExe().toString(), Theme.regular(10.5f), Theme.SECONDARY);
        content.add(openVpnPath);
        content.add(Box.createVerticalStrut(8));
        content.add(Theme.label("Sürüm", Theme.medium(10f), Theme.MUTED));
        content.add(Theme.label("MDC VPN " + version() + " • Windows x64", Theme.regular(10.5f), Theme.SECONDARY));

        dialog.setContentPane(new JScrollPane(content));
        dialog.setVisible(true);
    }

    private void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // Asagida hata mesaji gosterilir.
        }
        showError("Bağlantı açılamadı: " + url);
    }

    // --------------------------------------------------------------- render

    private void renderProfile() {
        if (profile == null) return;
        profileName.setText(profile.name());
        if (profile.engine() == Profiles.Engine.WIREGUARD) {
            profileEndpoint.setText(profile.config().isBlank()
                    ? "WireGuard • yapılandırma yok"
                    : "WireGuard • " + profile.endpoint());
        } else {
            profileEndpoint.setText(profile.endpoint());
        }

        String issue = "";
        Color color = Theme.DANGER;
        if (profile.issue() != null) {
            issue = switch (profile.issue()) {
                case MISSING_CONFIG -> "Yapılandırma yok — bir dosya seçin";
                case TEMPLATE -> "Şablon profil — kendi .ovpn dosyanızı seçin";
                case NO_CA -> "CA sertifikası eksik";
                case NO_CLIENT_CERT -> "İstemci sertifikası eksik";
            };
        } else if (profile.needsCredentials()) {
            issue = "Kullanıcı adı ve parola istenecek";
            color = Theme.SECONDARY;
        }
        issueLabel.setText(issue);
        issueLabel.setForeground(color);
        issueLabel.setVisible(!issue.isEmpty());
    }

    private void renderState() {
        ring.setState(state);
        String title;
        String subtitle;
        switch (state) {
            case DISCONNECTED -> {
                title = "Bağlı değil";
                subtitle = stateDetail.isBlank() ? "Güvenli tünel kapalı" : stateDetail;
            }
            case CONNECTING -> {
                title = "Bağlanıyor";
                subtitle = stateDetail.isBlank() ? "Sunucu ile el sıkışılıyor" : stateDetail;
            }
            case CONNECTED -> {
                title = "Güvende";
                subtitle = stateDetail.isBlank() ? "Trafik şifreli tünelden akıyor" : stateDetail;
            }
            case DISCONNECTING -> {
                title = "Kesiliyor";
                subtitle = stateDetail.isBlank() ? "Tünel kapatılıyor" : stateDetail;
            }
            default -> {
                title = "Bağlı değil";
                subtitle = "Güvenli tünel kapalı";
            }
        }
        stateTitle.setText(title);
        stateSubtitle.setText(subtitle);
        stateSubtitle.setForeground(errorState ? Theme.DANGER : Theme.SECONDARY);

        connectButton.setText(switch (state) {
            case CONNECTED, CONNECTING -> "Bağlantıyı Kes";
            case DISCONNECTING -> "Kesiliyor…";
            case DISCONNECTED -> "Bağlan";
        });
        connectButton.setEnabled(state != Tunnel.State.DISCONNECTING);
    }

    private void setState(Tunnel.State next, String detail) {
        state = next;
        stateDetail = detail == null ? "" : detail;
        errorState = next == Tunnel.State.DISCONNECTED
                && !stateDetail.isBlank()
                && !stateDetail.equals("Bağlantı kesildi");
        if (next == Tunnel.State.CONNECTED) {
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis();
            lastRx = 0;
            lastTx = 0;
        }
        if (next == Tunnel.State.DISCONNECTED || next == Tunnel.State.CONNECTING) {
            if (next == Tunnel.State.DISCONNECTED) {
                connectedAt = 0L;
                durationValue.setText("00:00:00");
                downloadValue.setText("0 B/s");
                uploadValue.setText("0 B/s");
            }
        }
        renderState();
    }

    private void tick() {
        if (state != Tunnel.State.CONNECTED || tunnel == null) return;
        long rx = tunnel.bytesIn();
        long tx = tunnel.bytesOut();
        long rxDelta = lastRx > 0 ? Math.max(0, rx - lastRx) : 0;
        long txDelta = lastTx > 0 ? Math.max(0, tx - lastTx) : 0;
        lastRx = rx;
        lastTx = tx;

        long seconds = connectedAt > 0 ? (System.currentTimeMillis() - connectedAt) / 1000 : 0;
        durationValue.setText(formatDuration(seconds));
        if (engine == Profiles.Engine.WIREGUARD) {
            downloadValue.setText("—");
            uploadValue.setText("—");
        } else {
            downloadValue.setText(formatSpeed(rxDelta));
            uploadValue.setText(formatSpeed(txDelta));
        }

        if (!tunnel.isRunning()) {
            String message = tunnel.lastError();
            tunnel = null;
            setState(Tunnel.State.DISCONNECTED, message.isBlank() ? "Tünel kapandı." : message);
        }
    }

    private final Tunnel.Listener tunnelListener = new Tunnel.Listener() {
        @Override
        public void onState(Tunnel.State next, String detail) {
            SwingUtilities.invokeLater(() -> setState(next, detail));
        }

        @Override
        public void onLog(String line) {
            SwingUtilities.invokeLater(() -> appendLog(line));
        }
    };

    private void appendLog(String line) {
        if (line == null || line.isBlank()) return;
        logArea.append(line + "\n");
        if (logArea.getLineCount() > 500) {
            try {
                logArea.replaceRange("", 0, logArea.getLineStartOffset(logArea.getLineCount() - 500));
            } catch (Exception ignored) {
                // Günlük kırpılamazsa büyümeye devam eder.
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ------------------------------------------------------------- utilities

    private static String formatDuration(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB/s", kb);
        return String.format(Locale.ROOT, "%.1f MB/s", kb / 1024.0);
    }

    static String version() {
        String value = MainWindow.class.getPackage().getImplementationVersion();
        return value == null || value.isBlank() ? "1.0.0" : value;
    }

}
