package com.mdc.vpn.desktop;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Windows uygulamasinin giris noktasi. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        Theme.install();

        if (!AppPaths.isWindows()) {
            JOptionPane.showMessageDialog(null,
                    "MDC VPN Windows x64 sürümü yalnızca Windows'ta çalışır.",
                    "MDC VPN", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> new MainWindow().show());
    }
}
