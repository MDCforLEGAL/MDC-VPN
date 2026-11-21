package com.mdc.vpn.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Windows arayuzu: durum halkasi, tek dugme ile baglan/kes, profil secimi,
 * protokol secimi ve canli guncel giden/gelen trafik.
 */
public final class MainWindow {

    private static final String LINKS = "https://mdcforlegal.github.io";
    private static final String REPO = "https://github.com/MDCforLEGAL/MDC-VPN";
    private static final String RELEASES = REPO + "/releases/latest";

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
    private final JComboBox<String> zapretPreset = new JComboBox<>();
    private final Theme.Button zapretButton = new Theme.Button("Başlat", false);
    private final JLabel zapretStatus = Theme.label("Kapalı", Theme.regular(11f), Theme.MUTED);
    private final ZapretManager zapret = new ZapretManager();
    private ZapretManager.Engine zapretEngine = ZapretManager.savedEngine();
    private boolean zapretOnlyListed = ZapretManager.savedOnlyListed();
    private boolean fillingPresets;
    private List<ZapretManager.Preset> zapretPresets = List.of();
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
        // Kenarliksiz pencere: baslik cubugu mobil surumdeki gibi uygulamanin
        // kendi koyu yuzeyinin parcasi olur.
        frame.setUndecorated(true);
        frame.setBackground(translucencySupported() ? new Color(0, 0, 0, 0) : Theme.BG);
        frame.setSize(520, 880);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        Theme.WindowPanel root = new Theme.WindowPanel();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildContent(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        frame.setContentPane(root);

        SwingUtilities.invokeLater(this::maybeAutoConnect);
    }

    private static boolean translucencySupported() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Exception e) {
            return false;
        }
    }

    private void shutdown() {
        ticker.stop();
        if (tunnel != null) tunnel.stop();
        zapret.stop();
        frame.dispose();
        System.exit(0);
    }

    private void maybeAutoConnect() {
        if (!Profiles.autoConnect()) return;
        if (profile == null || !profile.isReady()) return;
        // Yonetici izni yoksa UAC istemiyle uygulamayi kapatmayalim.
        if (!AppPaths.isElevated()) return;
        if (state == Tunnel.State.DISCONNECTED) onConnectClicked();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 14, 2));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.X_AXIS));
        JComponent mark = brandMark();
        brand.add(mark);
        brand.add(Box.createHorizontalStrut(10));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Theme.label("MDC VPN", Theme.bold(16f), Theme.TEXT));
        titles.add(Theme.label("Windows x64", Theme.regular(10.5f), Theme.MUTED));
        brand.add(titles);

        JPanel windowButtons = new JPanel();
        windowButtons.setOpaque(false);
        windowButtons.setLayout(new BoxLayout(windowButtons, BoxLayout.X_AXIS));

        Theme.IconButton settings = new Theme.IconButton("⚙", false);
        settings.setFont(new java.awt.Font("Segoe UI Symbol", java.awt.Font.PLAIN, 16));
        settings.setToolTipText("Ayarlar");
        settings.addActionListener(e -> openSettings());
        windowButtons.add(settings);
        windowButtons.add(Box.createHorizontalStrut(2));

        Theme.IconButton minimize = new Theme.IconButton("—", false);
        minimize.setToolTipText("Küçült");
        minimize.addActionListener(e -> frame.setState(JFrame.ICONIFIED));
        windowButtons.add(minimize);

        Theme.IconButton close = new Theme.IconButton("✕", true);
        close.setToolTipText("Kapat");
        close.addActionListener(e -> shutdown());
        windowButtons.add(close);

        header.add(brand, BorderLayout.WEST);
        header.add(windowButtons, BorderLayout.EAST);

        enableDrag(frame, header, brand, titles, mark);
        return header;
    }

    /** Yuvarlatilmis "M" logosu. */
    private static JComponent brandMark() {
        JComponent mark = new JComponent() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.ACCENT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 11, 11);
                g.setColor(Theme.ON_ACCENT);
                g.setFont(Theme.bold(15f));
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth("M")) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString("M", x, y);
                g.dispose();
            }
        };
        mark.setOpaque(false);
        mark.setPreferredSize(new Dimension(32, 32));
        mark.setMinimumSize(new Dimension(32, 32));
        mark.setMaximumSize(new Dimension(32, 32));
        return mark;
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
        content.add(buildZapretCard(), gbc);
        content.add(buildProfileCard(), gbc);

        connectButton.setPreferredSize(new Dimension(0, 50));
        connectButton.addActionListener(e -> onConnectClicked());
        content.add(connectButton, gbc);
        content.add(buildStats(), gbc);

        gbc.weighty = 1;
        content.add(Box.createVerticalGlue(), gbc);
        return content;
    }

    private JComponent buildStatusCard() {
        Theme.Card card = new Theme.Card(20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(14, 18, 16, 18));

        ring.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(ring);
        card.add(Box.createVerticalStrut(10));

        stateTitle.setFont(Theme.bold(21f));
        stateTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        stateSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        stateSubtitle.setHorizontalAlignment(JLabel.CENTER);
        card.add(stateTitle);
        card.add(Box.createVerticalStrut(3));
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

    private JComponent buildZapretCard() {
        Theme.Card card = new Theme.Card(18);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(11, 14, 12, 14));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(Theme.label("DPI ATLATMA (ZAPRET)", Theme.medium(10f), Theme.MUTED), BorderLayout.WEST);
        titleRow.add(zapretStatus, BorderLayout.EAST);
        card.add(titleRow);
        card.add(Box.createVerticalStrut(7));

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setOpaque(false);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        controls.setPreferredSize(new Dimension(0, 34));

        zapretPreset.setFont(Theme.regular(12f));
        zapretPreset.setBackground(Theme.CARD_HOVER);
        zapretPreset.setForeground(Theme.TEXT);
        zapretPreset.setPreferredSize(new Dimension(0, 34));
        zapretPreset.addActionListener(e -> {
            if (fillingPresets) return;
            int index = zapretPreset.getSelectedIndex();
            if (index >= 0 && index < zapretPresets.size()) {
                ZapretManager.saveSelection(zapretEngine, zapretPresets.get(index).id(), zapretOnlyListed);
            }
        });

        zapretButton.setPreferredSize(new Dimension(116, 34));
        zapretButton.setMaximumSize(new Dimension(116, 34));
        zapretButton.setFont(Theme.medium(12f));
        zapretButton.addActionListener(e -> toggleZapret());

        controls.add(zapretPreset, BorderLayout.CENTER);
        controls.add(zapretButton, BorderLayout.EAST);
        card.add(controls);

        card.add(Box.createVerticalStrut(7));
        Theme.Button autoFind = new Theme.Button("Stratejiyi otomatik bul", false);
        autoFind.setFont(Theme.regular(12f));
        autoFind.setPreferredSize(new Dimension(0, 32));
        autoFind.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        autoFind.addActionListener(e -> autoFindStrategy(autoFind));
        card.add(autoFind);

        fillZapretPresets();
        renderZapret();
        return card;
    }

    private void selectZapretEngine(ZapretManager.Engine engine) {
        if (zapretEngine == engine) return;
        if (zapret.isRunning()) {
            zapret.stop();
            appendLog("Zapret durduruldu (motor değişti).");
        }
        zapretEngine = engine;
        fillZapretPresets();
        renderZapret();
        ZapretManager.saveSelection(zapretEngine, selectedZapretPresetId(), zapretOnlyListed);
    }

    private void fillZapretPresets() {
        fillingPresets = true;
        zapretPresets = ZapretManager.presets(zapretEngine);
        zapretPreset.removeAllItems();
        String saved = ZapretManager.savedPresetId();
        int selected = 0;
        for (int i = 0; i < zapretPresets.size(); i++) {
            zapretPreset.addItem(zapretPresets.get(i).name());
            if (zapretPresets.get(i).id().equals(saved)) selected = i;
        }
        if (!zapretPresets.isEmpty()) zapretPreset.setSelectedIndex(selected);
        fillingPresets = false;
    }

    private void toggleZapret() {
        if (zapret.isRunning()) {
            zapret.stop();
            appendLog("DPI atlatma durduruldu.");
            renderZapret();
            return;
        }

        int index = zapretPreset.getSelectedIndex();
        if (index < 0 || index >= zapretPresets.size()) return;
        ZapretManager.Preset preset = zapretPresets.get(index);

        if (!AppPaths.isElevated()) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    "Zapret, WinDivert sürücüsünü yüklemek için yönetici izni gerektirir.\n\n"
                            + "Uygulama yönetici olarak yeniden başlatılsın mı?",
                    "Yönetici izni gerekli", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                try {
                    AppPaths.relaunchElevated();
                    zapret.stop();
                    frame.dispose();
                    System.exit(0);
                } catch (IOException ex) {
                    showError("Yönetici olarak başlatılamadı: " + ex.getMessage());
                }
            }
            return;
        }

        try {
            zapret.start(preset, zapretOnlyListed);
            appendLog("DPI atlatma etkin: " + preset.name() + " (" + preset.engine().label + ")");
        } catch (IOException ex) {
            appendLog("HATA: " + ex.getMessage());
            showError(ex.getMessage());
        }
        renderZapret();
    }

    /**
     * Butun stratejileri arka planda dener ve engeli gercekten asani secer.
     */
    private void autoFindStrategy(Theme.Button button) {
        if (!ensureElevated("Stratejileri denemek için yönetici izni gerekiyor.")) return;
        button.setEnabled(false);
        zapretButton.setEnabled(false);

        Thread worker = new Thread(() -> {
            DpiStrategyFinder finder = new DpiStrategyFinder(zapret, message ->
                    SwingUtilities.invokeLater(() -> {
                        zapretStatus.setText(message);
                        zapretStatus.setForeground(Theme.ACCENT);
                    }));
            DpiStrategyFinder.Result result = finder.find(zapretOnlyListed);

            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                if (result.preset() != null) {
                    zapretEngine = result.preset().engine();
                    ZapretManager.saveSelection(zapretEngine, result.preset().id(), zapretOnlyListed);
                    fillZapretPresets();
                    appendLog("Otomatik bulundu: " + result.detail());
                } else {
                    appendLog("Otomatik bulucu: " + result.detail());
                    JOptionPane.showMessageDialog(frame, result.detail(), "DPI stratejisi",
                            result.bypassNeeded()
                                    ? JOptionPane.WARNING_MESSAGE
                                    : JOptionPane.INFORMATION_MESSAGE);
                }
                renderZapret();
            });
        }, "dpi-autodetect");
        worker.setDaemon(true);
        worker.start();
    }

    /** Yonetici izni yoksa UAC ile yeniden baslatmayi onerir. */
    private boolean ensureElevated(String message) {
        if (AppPaths.isElevated()) return true;
        int answer = JOptionPane.showConfirmDialog(frame,
                message + "\n\nUygulama yönetici olarak yeniden başlatılsın mı?",
                "Yönetici izni gerekli", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return false;
        try {
            AppPaths.relaunchElevated();
            zapret.stop();
            frame.dispose();
            System.exit(0);
        } catch (IOException ex) {
            showError("Yönetici olarak başlatılamadı: " + ex.getMessage());
        }
        return false;
    }

    private void renderZapret() {
        boolean running = zapret.isRunning();
        zapretButton.setText(running ? "Durdur" : "Başlat");
        if (running && zapret.activePreset() != null) {
            zapretStatus.setText("Etkin • " + zapret.activePreset().name());
            zapretStatus.setForeground(Theme.ACCENT);
        } else if (!zapret.lastError().isBlank()) {
            zapretStatus.setText("Hata");
            zapretStatus.setForeground(Theme.DANGER);
        } else {
            zapretStatus.setText(ZapretManager.isBundled() ? "Kapalı" : "Paket yok");
            zapretStatus.setForeground(Theme.MUTED);
        }
        zapretButton.setEnabled(ZapretManager.isBundled());
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
        footer.add(Theme.label("OpenVPN • WireGuard • Zapret • MDC VPN", Theme.regular(10f), Theme.MUTED),
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
        openVpnToggle.setSelected(next == Profiles.Engine.OPENVPN);
        wireGuardToggle.setSelected(next == Profiles.Engine.WIREGUARD);
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
        dialog.setUndecorated(true);
        dialog.setBackground(translucencySupported() ? new Color(0, 0, 0, 0) : Theme.BG);
        dialog.setSize(520, 700);
        dialog.setLocationRelativeTo(frame);

        Theme.WindowPanel root = new Theme.WindowPanel();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(2, 4, 12, 0));
        header.add(Theme.label("Ayarlar", Theme.bold(19f), Theme.TEXT), BorderLayout.WEST);
        Theme.IconButton close = new Theme.IconButton("✕", true);
        close.setToolTipText("Kapat");
        close.addActionListener(e -> dialog.dispose());
        header.add(close, BorderLayout.EAST);
        enableDrag(dialog, header);
        root.add(header, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(0, 2, 14, 4));

        // ------------------------------------------------------------ baglanti
        Theme.Switch autoConnect = new Theme.Switch();
        autoConnect.setSelected(Profiles.autoConnect());
        autoConnect.addActionListener(e -> Profiles.setAutoConnect(autoConnect.isSelected()));

        Theme.Switch legacy = new Theme.Switch();
        legacy.setSelected(Profiles.legacy());
        legacy.addActionListener(e -> {
            Profiles.setLegacy(legacy.isSelected());
            profile = Profiles.load(engine);
            renderProfile();
        });

        content.add(section("BAĞLANTI",
                settingRow("Açılışta otomatik bağlan",
                        hint("Uygulama yönetici olarak açıldığında tünel kendiliğinden kurulur"),
                        autoConnect, null),
                divider(),
                settingRow("Eski sunucu uyumluluğu",
                        hint("BF-CBC, ns-cert-type, tls-remote, LZO ve TLS 1.0 direktiflerini uyarlar"),
                        legacy, null),
                divider(),
                settingRow("Profili sıfırla",
                        hint("İçe aktarılan yapılandırmayı siler, varsayılana döner"),
                        null, () -> resetProfileFromSettings(dialog))));

        // ------------------------------------------------------------ protokol
        Theme.PillToggle openVpn = new Theme.PillToggle("OpenVPN");
        Theme.PillToggle wireGuard = new Theme.PillToggle("WireGuard");
        ButtonGroup protocolGroup = new ButtonGroup();
        protocolGroup.add(openVpn);
        protocolGroup.add(wireGuard);
        openVpn.setSelected(engine == Profiles.Engine.OPENVPN);
        wireGuard.setSelected(engine == Profiles.Engine.WIREGUARD);
        openVpn.addActionListener(e -> {
            if (openVpn.isSelected()) selectEngine(Profiles.Engine.OPENVPN);
        });
        wireGuard.addActionListener(e -> {
            if (wireGuard.isSelected()) selectEngine(Profiles.Engine.WIREGUARD);
        });
        JPanel protocolRow = new JPanel(new GridLayout(1, 2, 8, 0));
        protocolRow.setOpaque(false);
        protocolRow.setPreferredSize(new Dimension(0, 38));
        protocolRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        protocolRow.add(openVpn);
        protocolRow.add(wireGuard);

        content.add(section("PROTOKOL",
                padded(protocolRow),
                divider(),
                hintBlock("OpenVPN için .ovpn, WireGuard için .conf dosyası içe aktarın. "
                        + "Protokol değiştirmek için önce bağlantıyı kesin.")));

        // --------------------------------------------------------- dpi atlatma
        Theme.PillToggle zapret1 = new Theme.PillToggle("Zapret 1");
        Theme.PillToggle zapret2 = new Theme.PillToggle("Zapret 2");
        ButtonGroup zapretGroup = new ButtonGroup();
        zapretGroup.add(zapret1);
        zapretGroup.add(zapret2);
        zapret1.setSelected(zapretEngine == ZapretManager.Engine.ZAPRET1);
        zapret2.setSelected(zapretEngine == ZapretManager.Engine.ZAPRET2);

        JLabel strategyValue = Theme.label(currentZapretPresetName(), Theme.regular(11f), Theme.SECONDARY);
        JLabel dpiStatus = Theme.label(
                (zapret.isRunning() ? "Etkin" : "Kapalı") + " • " + ZapretManager.directory(),
                Theme.regular(11f), zapret.isRunning() ? Theme.ACCENT : Theme.MUTED);

        zapret1.addActionListener(e -> {
            if (!zapret1.isSelected()) return;
            selectZapretEngine(ZapretManager.Engine.ZAPRET1);
            strategyValue.setText(currentZapretPresetName());
        });
        zapret2.addActionListener(e -> {
            if (!zapret2.isSelected()) return;
            selectZapretEngine(ZapretManager.Engine.ZAPRET2);
            strategyValue.setText(currentZapretPresetName());
        });

        JPanel zapretRow = new JPanel(new GridLayout(1, 2, 8, 0));
        zapretRow.setOpaque(false);
        zapretRow.setPreferredSize(new Dimension(0, 38));
        zapretRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        zapretRow.add(zapret1);
        zapretRow.add(zapret2);

        Theme.Switch onlyListed = new Theme.Switch();
        onlyListed.setSelected(zapretOnlyListed);
        onlyListed.addActionListener(e -> {
            zapretOnlyListed = onlyListed.isSelected();
            ZapretManager.saveSelection(zapretEngine, selectedZapretPresetId(), zapretOnlyListed);
        });

        Theme.Switch serviceSwitch = new Theme.Switch();
        serviceSwitch.setSelected(ZapretService.isInstalled());
        serviceSwitch.addActionListener(e -> {
            boolean want = serviceSwitch.isSelected();
            if (want && !AppPaths.isElevated()) {
                serviceSwitch.setSelected(false);
                ensureElevated("Servisi kurmak için yönetici izni gerekiyor.");
                return;
            }
            try {
                if (want) {
                    ZapretManager.Preset preset = currentZapretPreset();
                    if (preset == null) throw new IOException("Önce bir strateji seçin.");
                    ZapretService.install(preset, zapretOnlyListed);
                    appendLog("DPI servisi kuruldu: " + preset.name()
                            + " (açılışta otomatik çalışır)");
                } else {
                    ZapretService.uninstall();
                    appendLog("DPI servisi kaldırıldı.");
                }
            } catch (Exception ex) {
                serviceSwitch.setSelected(!want);
                showError("Servis işlemi başarısız: " + ex.getMessage());
            }
        });

        content.add(section("DPI ATLATMA (ZAPRET)",
                padded(zapretRow),
                divider(),
                settingRow("Strateji", strategyValue, null, () -> {
                    chooseZapretPreset(dialog);
                    strategyValue.setText(currentZapretPresetName());
                }),
                divider(),
                settingRow("Yalnızca listedeki siteler",
                        hint("Kapalıyken tüm trafik işlenir; açıkken hostlist.txt kullanılır"),
                        onlyListed, null),
                divider(),
                settingRow("Servis olarak yükle",
                        hint("Seçilen stratejiyi Windows açılışında SYSTEM yetkisiyle başlatır; "
                                + "oturum açmak gerekmez"),
                        serviceSwitch, null),
                divider(),
                hintBlock("Zapret, WinDivert sürücüsü üzerinden TCP/UDP paketlerini yeniden düzenler. "
                        + "Başlatmak için yönetici izni gerekir; strateji ve motor seçimi ana ekrandaki "
                        + "DPI kartından da değiştirilebilir. " + dpiStatus.getText())));

        // ------------------------------------------------------------ guncelleme
        content.add(section("GÜNCELLEMELER",
                settingRow("Sürüm", hint("MDC VPN " + version() + " • Windows x64"), null, null),
                divider(),
                settingRow("Uygulamayı güncelle",
                        hint("Yeni sürümü indirir, kurar ve uygulamayı yeniden başlatır"),
                        null, this::checkForUpdates),
                divider(),
                settingRow("Sürüm notları", hint("GitHub'daki sürüm sayfasını açar"),
                        null, () -> browse(RELEASES)),
                divider(),
                settingRow("Diğer içeriklerim", hint(LINKS), null, () -> browse(LINKS))));

        // ------------------------------------------------------------- tani
        logArea.setEditable(false);
        logArea.setFont(Theme.mono(11f));
        logArea.setBackground(Theme.BG);
        logArea.setForeground(Theme.SECONDARY);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        logArea.setRows(5);
        logArea.setLineWrap(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(Theme.STROKE));
        logScroll.setPreferredSize(new Dimension(0, 110));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        logScroll.getViewport().setBackground(Theme.BG);

        Theme.Button clearLog = new Theme.Button("Günlüğü temizle", false);
        clearLog.setFont(Theme.regular(11f));
        clearLog.setPreferredSize(new Dimension(150, 32));
        clearLog.addActionListener(e -> logArea.setText(""));

        content.add(section("TANILAMA", padded(logScroll), divider(), padded(clearLog)));

        // --------------------------------------------------------------- hakkinda
        content.add(section("HAKKINDA",
                settingRow("Lisans", hint("Apache-2.0 • OpenVPN 3 + WireGuard + zapret"), null, null),
                divider(),
                settingRow("Kaynak kod", hint("github.com/MDCforLEGAL/MDC-VPN"),
                        null, () -> browse(REPO)),
                divider(),
                settingRow("OpenVPN", hint("openvpn.exe uygulama klasöründeki openvpn dizininden çalışır"),
                        null, null)));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 4, 0, 4));
        footer.add(Theme.label("MDC VPN • Windows x64", Theme.regular(10f), Theme.MUTED), BorderLayout.WEST);
        footer.add(Theme.label("v" + version(), Theme.regular(10f), Theme.MUTED), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setVisible(true);
        fillZapretPresets();
        renderZapret();
    }

    /** GitHub'daki en son surumu denetler ve varsa indirmeyi baslatir. */
    private void checkForUpdates() {
        appendLog("Güncellemeler denetleniyor…");
        Thread worker = new Thread(() -> {
            try {
                Updater.Release release = Updater.check();
                SwingUtilities.invokeLater(() -> {
                    if (release == null) {
                        appendLog("Uygulama güncel: v" + version());
                        JOptionPane.showMessageDialog(frame, "Uygulama güncel: v" + version(),
                                "Güncellemeler", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    appendLog("Yeni sürüm bulundu: v" + release.version());
                    int answer = JOptionPane.showConfirmDialog(frame,
                            "Sürüm " + release.version() + " yayınlandı.\n\n"
                                    + "Şimdi indirilip kurulsun mu? Kurulumdan sonra uygulama yeniden başlar.",
                            "Güncelleme bulundu", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) downloadUpdate(release);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Güncelleme denetlenemedi: " + ex.getMessage()));
            }
        }, "update-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** Indirme ilerlemesini gosterir, sonra kurulumu baslatir. */
    private void downloadUpdate(Updater.Release release) {
        JDialog progress = new JDialog(frame, "Güncelleme", true);
        progress.setUndecorated(true);
        progress.setBackground(translucencySupported() ? new Color(0, 0, 0, 0) : Theme.BG);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        Theme.WindowPanel panel = new Theme.WindowPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

        JLabel status = Theme.label("Güncelleme indiriliyor… %0", Theme.medium(13f), Theme.TEXT);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel detail = Theme.label("v" + release.version() + " • MDC-VPN-Windows-x64.zip",
                Theme.regular(11f), Theme.SECONDARY);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(status);
        panel.add(Box.createVerticalStrut(6));
        panel.add(detail);

        progress.setContentPane(panel);
        progress.setSize(360, 104);
        progress.setLocationRelativeTo(frame);

        Thread worker = new Thread(() -> {
            try {
                Path zip = Updater.download(release, percent -> SwingUtilities.invokeLater(() ->
                        status.setText("Güncelleme indiriliyor… %" + percent)));
                SwingUtilities.invokeLater(() -> status.setText("Kurulum hazırlanıyor…"));
                Updater.apply(zip);
                SwingUtilities.invokeLater(() -> {
                    progress.dispose();
                    appendLog("Güncelleme indirildi; uygulama yeniden başlatılıyor.");
                    JOptionPane.showMessageDialog(frame,
                            "Güncelleme uygulanacak. Uygulama şimdi kapanıp yeniden başlayacak.",
                            "Güncelleme", JOptionPane.INFORMATION_MESSAGE);
                    restartForUpdate();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    progress.dispose();
                    showError("Güncelleme kurulamadı: " + ex.getMessage());
                });
            }
        }, "update-download");
        worker.setDaemon(true);
        worker.start();
        progress.setVisible(true);
    }

    private void restartForUpdate() {
        ticker.stop();
        zapret.stop();
        frame.dispose();
        System.exit(0);
    }

    private void resetProfileFromSettings(JDialog dialog) {
        int answer = JOptionPane.showConfirmDialog(dialog,
                "İçe aktarılan profil silinsin mi?", "Profili sıfırla",
                JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        if (state != Tunnel.State.DISCONNECTED) stopTunnel();
        Profiles.reset();
        engine = Profiles.Engine.OPENVPN;
        Profiles.setEngine(engine);
        profile = Profiles.load(engine);
        openVpnToggle.setSelected(true);
        wireGuardToggle.setSelected(false);
        renderProfile();
        appendLog("Profil sıfırlandı.");
        dialog.dispose();
    }

    private void chooseZapretPreset(Component parent) {
        if (zapretPresets.isEmpty()) fillZapretPresets();
        String[] names = new String[zapretPresets.size()];
        for (int i = 0; i < zapretPresets.size(); i++) names[i] = zapretPresets.get(i).name();
        int current = Math.max(0, zapretPreset.getSelectedIndex());
        Object choice = JOptionPane.showInputDialog(parent, "DPI stratejisi seçin", "Zapret",
                JOptionPane.PLAIN_MESSAGE, null, names, names[current]);
        if (choice == null) return;
        int index = -1;
        for (int i = 0; i < names.length; i++) if (names[i].equals(choice.toString())) index = i;
        if (index < 0) return;
        zapretPreset.setSelectedIndex(index);
        ZapretManager.saveSelection(zapretEngine, zapretPresets.get(index).id(), zapretOnlyListed);
    }

    private String currentZapretPresetName() {
        Object item = zapretPreset.getSelectedItem();
        return item == null ? "—" : item.toString();
    }

    private String selectedZapretPresetId() {
        int index = zapretPreset.getSelectedIndex();
        if (index < 0 || index >= zapretPresets.size()) return ZapretManager.savedPresetId();
        return zapretPresets.get(index).id();
    }

    private ZapretManager.Preset currentZapretPreset() {
        int index = zapretPreset.getSelectedIndex();
        if (index < 0 || index >= zapretPresets.size()) return null;
        return zapretPresets.get(index);
    }

    // ------------------------------------------------------- ayar bilesenleri

    private JComponent section(String title, JComponent... children) {
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel heading = Theme.label(title, Theme.medium(10f), Theme.MUTED);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 4, 7, 0));
        wrap.add(heading);

        Theme.Card card = new Theme.Card(18);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        for (JComponent child : children) {
            child.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(child);
        }
        wrap.add(card);
        wrap.add(Box.createVerticalStrut(12));

        Dimension pref = wrap.getPreferredSize();
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return wrap;
    }

    private JComponent settingRow(String title, JLabel description, JComponent control, Runnable action) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Theme.label(title, Theme.medium(13f), Theme.TEXT));
        text.add(Box.createVerticalStrut(3));
        text.add(description);
        row.add(text, BorderLayout.CENTER);

        if (control != null) {
            row.add(control, BorderLayout.EAST);
        } else if (action != null) {
            row.add(Theme.label("›", Theme.regular(19f), Theme.MUTED), BorderLayout.EAST);
            row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            installClick(row, action);
        }
        Dimension pref = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return row;
    }

    private JLabel hint(String text) {
        JLabel label = Theme.label(
                "<html><div style='width:330px'>" + text + "</div></html>",
                Theme.regular(11f), Theme.SECONDARY);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JComponent hintBlock(String text) {
        JLabel label = hint(text);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(9, 16, 11, 16));
        wrap.add(label, BorderLayout.CENTER);
        Dimension pref = wrap.getPreferredSize();
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return wrap;
    }

    private JComponent padded(JComponent child) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16));
        wrap.add(child, BorderLayout.CENTER);
        Dimension pref = wrap.getPreferredSize();
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return wrap;
    }

    private JComponent divider() {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        JPanel line = new JPanel();
        line.setBackground(Theme.STROKE);
        line.setPreferredSize(new Dimension(0, 1));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        holder.add(line, BorderLayout.CENTER);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return holder;
    }

    /** Satirin her yerine (yazilar dahil) tiklanabilirlik kazandirir. */
    private static void installClick(Component component, Runnable action) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                component.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            }
        };
        component.addMouseListener(adapter);
        component.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) installClick(child, action);
        }
    }

    private static void enableDrag(Window window, Component... components) {
        MouseAdapter adapter = new MouseAdapter() {
            private Point origin;

            @Override
            public void mousePressed(MouseEvent e) {
                origin = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (origin == null) return;
                Point location = window.getLocation();
                window.setLocation(location.x + e.getX() - origin.x, location.y + e.getY() - origin.y);
            }
        };
        for (Component component : components) {
            component.addMouseListener(adapter);
            component.addMouseMotionListener(adapter);
        }
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
        if (!zapret.isRunning() && zapretStatus.getText().startsWith("Etkin")) {
            renderZapret();
            appendLog("Zapret süreci kapandı.");
        }
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
