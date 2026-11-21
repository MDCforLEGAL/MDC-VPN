package com.mdc.vpn.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Kurumsal koyu tema: renkler, yazilar ve ortak bilesenler. */
public final class Theme {

    public static final Color BG = new Color(0x121417);
    public static final Color CARD = new Color(0x1E2229);
    public static final Color CARD_HOVER = new Color(0x262B33);
    public static final Color STROKE = new Color(0x2A2F37);
    public static final Color ACCENT = new Color(0x10B981);
    public static final Color ACCENT_DARK = new Color(0x0B8F65);
    public static final Color TEXT = new Color(0xF2F5F8);
    public static final Color SECONDARY = new Color(0x98A2B3);
    public static final Color MUTED = new Color(0x6B7280);
    public static final Color DANGER = new Color(0xEF4444);
    public static final Color ON_ACCENT = new Color(0x0B1512);

    private Theme() {
    }

    public static void install() {
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.background", CARD);
        UIManager.put("TextField.background", CARD_HOVER);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("PasswordField.background", CARD_HOVER);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("PasswordField.caretForeground", TEXT);
        UIManager.put("TextArea.background", BG);
        UIManager.put("TextArea.foreground", SECONDARY);
        UIManager.put("TextPane.background", CARD);
        UIManager.put("ScrollPane.background", CARD);
        UIManager.put("Viewport.background", CARD);
        UIManager.put("Button.background", CARD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("ToolTip.background", CARD);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("List.background", CARD);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("ComboBox.background", CARD_HOVER);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", STROKE);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ScrollBar.background", BG);
        UIManager.put("ScrollBar.thumb", STROKE);
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ScrollBarUI", DarkScrollBarUI.class.getName());
    }

    /** Koyu temaya uyan ince kaydirma cubugu. */
    public static class DarkScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = STROKE;
            trackColor = BG;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setBorder(null);
            button.setOpaque(false);
            return button;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(BG);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !((JScrollBar) component).isEnabled()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(thumbColor);
            g.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 6, 6);
            g.dispose();
        }
    }

    public static Font regular(float size) {
        return new Font("Segoe UI", Font.PLAIN, Math.round(size));
    }

    public static Font medium(float size) {
        return new Font("Segoe UI Semibold", Font.PLAIN, Math.round(size));
    }

    public static Font bold(float size) {
        return new Font("Segoe UI", Font.BOLD, Math.round(size));
    }

    public static Font mono(float size) {
        return new Font("Consolas", Font.PLAIN, Math.round(size));
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /** Yuvarlatilmis koseli kart. */
    public static class Card extends JPanel {
        private final int radius;

        public Card(int radius) {
            this.radius = radius;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(CARD);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), radius, radius));
            g.setColor(STROKE);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, radius, radius));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Duz renkli, yuvarlatilmis aksiyon dugmesi. */
    public static class Button extends JButton {
        private final boolean accent;
        private boolean hover;

        public Button(String text, boolean accent) {
            super(text);
            this.accent = accent;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(medium(14f));
            setForeground(accent ? ON_ACCENT : TEXT);
            setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            if (accent) {
                fill = hover ? ACCENT_DARK : ACCENT;
                if (!isEnabled()) fill = new Color(0x1B3A31);
            } else {
                fill = hover ? CARD_HOVER : CARD;
            }
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 12, 12));
            if (!accent) {
                g.setColor(STROKE);
                g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 12, 12));
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Protokol secimi icin segment dugmesi. */
    public static class PillToggle extends JToggleButton {
        private boolean hover;

        public PillToggle(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(medium(13f));
            setForeground(SECONDARY);
            addItemListener(e -> repaint());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected = isSelected();
            g.setColor(selected ? ACCENT : (hover ? CARD_HOVER : new Color(0, 0, 0, 0)));
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 10, 10));
            if (!selected) {
                g.setColor(STROKE);
                g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 10, 10));
            }
            g.dispose();
            setForeground(selected ? ON_ACCENT : SECONDARY);
            super.paintComponent(graphics);
        }
    }

    /** Mobil surumdeki gibi yuvarlak anahtar (switch). */
    public static class Switch extends JToggleButton {
        public Switch() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(42, 24));
            setMaximumSize(new Dimension(42, 24));
            setMinimumSize(new Dimension(42, 24));
            addItemListener(e -> repaint());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 24;
            int w = 42;
            int y = (getHeight() - h) / 2;
            g.setColor(isSelected() ? ACCENT : STROKE);
            g.fillRoundRect(0, y, w, h, h, h);
            g.setColor(isSelected() ? ON_ACCENT : SECONDARY);
            int knob = 18;
            int x = isSelected() ? w - knob - 3 : 3;
            g.fillOval(x, y + 3, knob, knob);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Baslik cubugundaki simge dugmesi (kucult / kapat / ayarlar). */
    public static class IconButton extends JButton {
        private final boolean danger;
        private boolean hover;

        public IconButton(String text, boolean danger) {
            super(text);
            this.danger = danger;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(medium(15f));
            setForeground(SECONDARY);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            setPreferredSize(new Dimension(34, 30));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    setForeground(danger ? DANGER : TEXT);
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    setForeground(SECONDARY);
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (hover) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(danger ? new Color(0x3A1D20) : CARD_HOVER);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /** Kenarliksiz pencere/dialog icin yuvarlatilmis koyu yuzey. */
    public static class WindowPanel extends JPanel {
        public WindowPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 18, 18));
            g.setColor(STROKE);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 18, 18));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Baglanti durumunu gosteren halka. */
    public static class StatusRing extends JComponent {
        private Tunnel.State state = Tunnel.State.DISCONNECTED;
        private float angle;
        private final javax.swing.Timer timer;

        public StatusRing() {
            setPreferredSize(new Dimension(148, 148));
            setMinimumSize(new Dimension(148, 148));
            setMaximumSize(new Dimension(148, 148));
            setOpaque(false);
            timer = new javax.swing.Timer(40, e -> {
                if (state == Tunnel.State.CONNECTING || state == Tunnel.State.DISCONNECTING) {
                    angle = (angle + 5f) % 360f;
                    repaint();
                }
            });
            timer.start();
        }

        public void setState(Tunnel.State state) {
            this.state = state;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            int inset = 10;
            int diameter = size - inset * 2;
            int x = (getWidth() - diameter) / 2;
            int y = (getHeight() - diameter) / 2;

            g.setColor(new Color(0x22272F));
            g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x, y, diameter, diameter, 0, 360);

            Color color = switch (state) {
                case CONNECTED -> ACCENT;
                case CONNECTING, DISCONNECTING -> ACCENT;
                case DISCONNECTED -> MUTED;
            };
            g.setColor(color);
            switch (state) {
                case CONNECTED -> g.drawArc(x, y, diameter, diameter, 90, -360);
                case CONNECTING, DISCONNECTING -> g.drawArc(x, y, diameter, diameter, Math.round(angle), 90);
                case DISCONNECTED -> g.drawArc(x, y, diameter, diameter, 90, -40);
            }

            // Ortadaki kalkan/kilit isareti.
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g.setColor(state == Tunnel.State.CONNECTED ? ACCENT : SECONDARY);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(cx - 13, cy - 6, 26, 20, 6, 6);
            g.drawArc(cx - 9, cy - 18, 18, 22, 0, 180);
            g.dispose();
        }
    }
}
