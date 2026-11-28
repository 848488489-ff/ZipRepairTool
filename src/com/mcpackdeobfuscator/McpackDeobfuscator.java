package com.mcpackdeobfuscator;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.prefs.Preferences;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * mcpack-deobfuscator - GUI and command-line front end.
 *
 * The GUI has two areas: Repair and Obfuscate (Encrypt). The Obfuscate area offers
 * a set of tunable parameters (name rewrite mode, percentage, forged EOCD,
 * CRC perturbation, RNG seed) on top of named presets.
 *
 * Without arguments the Swing GUI is shown. Command-line modes:
 *   java -jar mcpack-deobfuscator.jar -deobfuscate <in.zip> <out.zip>
 *   java -jar mcpack-deobfuscator.jar -detect <in.zip>
 *   java -jar mcpack-deobfuscator.jar -obfuscate <in.zip> <out.zip> <preset>
 *       [--name <none|upper|digit|random>] [--percent <0-100>]
 *       [--eocd] [--crc] [--seed <n>]
 *
 * All user-visible strings are localized (EN / ZH, AUTO follows the system
 * locale); source comments stay in English.
 */
public class McpackDeobfuscator extends JFrame {

    private static final long serialVersionUID = 1L;

    /** Tool version, read from the bundled version.json resource. */
    public static final String VERSION = loadMeta("version", "0.0.0");

    /** Project repository URL (shown in the About dialog and CLI). */
    public static final String GITHUB_URL = loadMeta("repo", "https://github.com/848488489-ff/mcpack-deobfuscator");

    /** Preference key marking that the first-run license notice was shown. */
    private static final String PREF_LICENSE_ACCEPTED = "licenseAccepted";
    /** License file name (bundled in the jar, or in the working directory). */
    private static final String LICENSE_FILE = "LICENSE";

    /**
     * Read a string value from the bundled {@code /version.json} resource.
     * This keeps the version and repository URL in one place: edit version.json
     * and rebuild. The fallback is used only if the resource is missing or
     * malformed.
     */
    private static String loadMeta(String key, String fallback) {
        try (InputStream in = McpackDeobfuscator.class.getResourceAsStream("/version.json")) {
            if (in == null) {
                return fallback;
            }
            String json = readAll(in);
            return readJsonString(json, key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Read a localized value from {@code /version.json}. For Chinese the
     * {@code <key>_Chinese} field is preferred and falls back to the plain
     * {@code <key>} (English) field. {@code zh=false} always uses {@code <key>}.
     */
    private static String loadMetaLang(String key, boolean zh, String fallback) {
        try (InputStream in = McpackDeobfuscator.class.getResourceAsStream("/version.json")) {
            if (in == null) {
                return fallback;
            }
            String json = readAll(in);
            if (zh) {
                String localized = readJsonString(json, key + "_Chinese", null);
                if (localized != null) {
                    return localized;
                }
            }
            return readJsonString(json, key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Extract a JSON string value, or {@code fallback} if absent/malformed. */
    private static String readJsonString(String json, String key, String fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return fallback;
    }

    /** Decode JSON {@code \\uXXXX} escapes into the actual characters. */
    private static String unescapeJson(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignore) {
                    // fall through and keep the literal
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Interface for panels that need re-localizing on language change. */
    private interface Refreshable {
        void refresh();
    }

    private final JComboBox<Lang> langBox = new JComboBox<Lang>(new Lang[]{Lang.AUTO, Lang.EN, Lang.ZH});
    private final JTabbedPane tabs = new JTabbedPane();
    private final CheckPanel checkPanel = new CheckPanel();
    private final RepairPanel repairPanel = new RepairPanel();
    private final ObfuscatePanel obfuscatePanel = new ObfuscatePanel();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel();
    private final JMenu helpMenu = new JMenu();
    private final JMenuItem aboutItem = new JMenuItem();

    /** Currently selected language mode. */
    private Lang mode = Lang.AUTO;

    public McpackDeobfuscator() {
        super();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(760, 620));
        setLocationByPlatform(true);

        buildUi();
        setupLanguageBox();
        langBox.setSelectedItem(Lang.AUTO);
        refreshTexts();
    }

    private void buildUi() {
        // Top: language selector.
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JLabel langLabel = new JLabel("lang.label");
        langLabel.setName("lang.label");
        top.add(langLabel);
        top.add(langBox);
        top.setBorder(BorderFactory.createEmptyBorder(6, 12, 0, 12));

        tabs.addTab("", checkPanel);
        tabs.addTab("", repairPanel);
        tabs.addTab("", obfuscatePanel);

        // Bottom: progress + status.
        JPanel bottom = new JPanel(new BorderLayout(8, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        bottom.add(progressBar, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.EAST);

        // Help menu with About (version + GitHub link).
        aboutItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showAbout();
            }
        });
        helpMenu.add(aboutItem);
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(helpMenu);
        setJMenuBar(menuBar);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
    }

    /** About dialog: version, repository link and update check, localized. */
    private void showAbout() {
        boolean zh = Lang.resolve(mode) == Lang.ZH;
        String name = loadMetaLang("name", zh, tr("app.title"));
        String desc = loadMetaLang("description", zh, "");
        Object[] options = {tr("about.checkUpdate"), tr("about.open"), tr("about.close")};
        String msg = name + " v" + VERSION + "\n\n"
                + (desc.isEmpty() ? "" : desc + "\n\n")
                + (zh ? "\u4ed3\u5e93\u5730\u5740\uff1a\n" : "Repository:\n")
                + GITHUB_URL;
        int r = JOptionPane.showOptionDialog(this,
                msg,
                name, JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (r == 0) {
            checkUpdateGui(zh);
        } else if (r == 1) {
            try {
                Desktop.getDesktop().browse(new URI(GITHUB_URL));
            } catch (Exception ex) {
                // Desktop browsing unavailable; the URL is still shown.
            }
        }
    }

    /** First-run gate: show full Unlicense text; must agree to continue, else exit. */
    private void maybeShowLicenseOnFirstRun() {
        Preferences prefs = Preferences.userNodeForPackage(McpackDeobfuscator.class);
        if (prefs.getBoolean(PREF_LICENSE_ACCEPTED, false)) {
            return;
        }
        JTextArea area = new JTextArea(readLicenseText(), 20, 64);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        Object[] options = {tr("license.agree"), tr("license.decline")};
        int r = JOptionPane.showOptionDialog(this, scroll, tr("license.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[0]);
        if (r == 0) {
            prefs.putBoolean(PREF_LICENSE_ACCEPTED, true);
            return;
        }
        // Declined or dialog closed: cannot continue.
        System.exit(0);
    }

    /** Read license text from classpath or working directory, else a minimal notice. */
    private static String readLicenseText() {
        try (InputStream in = McpackDeobfuscator.class.getResourceAsStream("/" + LICENSE_FILE)) {
            if (in != null) {
                return readAll(in);
            }
        } catch (Exception ignored) {
            // fall through
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(LICENSE_FILE);
            if (java.nio.file.Files.exists(p)) {
                return new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "Unlicense (public domain). See https://unlicense.org\n";
    }

    /** Check for a newer release on a background thread, then report. */
    private void checkUpdateGui(final boolean zh) {
        setBusy(true);
        SwingWorker<String, Void> w = new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                URL url = new URL("https://api.github.com/repos/848488489-ff/mcpack-deobfuscator/releases/latest");
                HttpURLConnection conn = openConn(url);
                int code = conn.getResponseCode();
                return code == 200 ? parseTagName(readAll(conn.getInputStream())) : null;
            }
            protected void done() {
                String title = tr("about.update.title");
                try {
                    String latest = get();
                    String msg;
                    int type;
                    if (latest == null) {
                        msg = tr("about.update.failed");
                        type = JOptionPane.ERROR_MESSAGE;
                    } else if (latest.equals("v" + VERSION)) {
                        msg = trf("about.update.latest", VERSION);
                        type = JOptionPane.INFORMATION_MESSAGE;
                    } else {
                        msg = trf("about.update.new", latest, GITHUB_URL + "/releases/latest");
                        type = JOptionPane.INFORMATION_MESSAGE;
                    }
                    JOptionPane.showMessageDialog(McpackDeobfuscator.this, msg, title, type);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(McpackDeobfuscator.this,
                            tr("about.update.failed"), title, JOptionPane.ERROR_MESSAGE);
                } finally {
                    setBusy(false);
                }
            }
        };
        w.execute();
    }


    private void setupLanguageBox() {
        langBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Lang) {
                    setText(nativeName((Lang) value));
                }
                return this;
            }
        });
        langBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Lang sel = (Lang) langBox.getSelectedItem();
                if (sel != null) {
                    mode = sel;
                    refreshTexts();
                }
            }
        });
    }

    private String nativeName(Lang l) {
        switch (l) {
            case ZH:
                return "Chinese";
            case EN:
                return "English";
            default:
                return "Auto";
        }
    }

    /** Re-apply all localized strings after a language change. */
    private void refreshTexts() {
        boolean zh = Lang.resolve(mode) == Lang.ZH;
        setTitle(loadMetaLang("name", zh, tr("app.title")));
        ((JLabel) ((JPanel) getContentPane().getComponent(0)).getComponent(0)).setText(tr("lang.label"));
        tabs.setTitleAt(0, tr("tab.check"));
        tabs.setTitleAt(1, tr("tab.repair"));
        tabs.setTitleAt(2, tr("tab.obfuscate"));
        helpMenu.setText(tr("menu.help"));
        aboutItem.setText(tr("menu.about"));
        checkPanel.refresh();
        repairPanel.refresh();
        obfuscatePanel.refresh();
        statusLabel.setText(tr("status.ready"));
    }

    private String tr(String key) {
        return Lang.tr(mode, key);
    }

    private String trf(String key, Object... args) {
        return String.format(Lang.tr(mode, key), args);
    }

    private void setBusy(boolean busy) {
        checkPanel.setEnabledAll(!busy);
        repairPanel.setEnabledAll(!busy);
        obfuscatePanel.setEnabledAll(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
                : Cursor.getDefaultCursor());
    }

    // -----------------------------------------------------------------
    // Shared helpers for file chooser and drag & drop.
    // -----------------------------------------------------------------

    private static void installDrop(JPanel panel, final JTextField field) {
        panel.setDropTarget(new DropTarget(panel, new DropTargetAdapter() {
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    Object data = evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List) {
                        List<?> files = (List<?>) data;
                        if (!files.isEmpty() && files.get(0) instanceof File) {
                            field.setText(((File) files.get(0)).getAbsolutePath());
                        }
                    }
                    evt.dropComplete(true);
                } catch (Exception ex) {
                    evt.dropComplete(false);
                }
            }
        }));
    }

    private void choose(JTextField field, boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
        if (!field.getText().trim().isEmpty()) {
            chooser.setSelectedFile(new File(field.getText().trim()));
        }
        int r = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (r == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private static Path defaultOutput(Path input, String suffix) {
        String name = input.getFileName().toString();
        if (name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        return input.resolveSibling(name + suffix + ".zip");
    }

    /** Whether two paths point at the same file (normalized; case-insensitive on Windows). */
    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        String sa = a.toAbsolutePath().normalize().toString();
        String sb = b.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return sa.equalsIgnoreCase(sb);
        }
        return sa.equals(sb);
    }

    // -----------------------------------------------------------------
    // Check (inspect) area.
    // -----------------------------------------------------------------

    private class CheckPanel extends JPanel implements Refreshable {
        private static final long serialVersionUID = 1L;

        private final JTextField inputField = new JTextField(44);
        private final JButton browseIn = new JButton();
        private final JButton checkBtn = new JButton();
        private final JTextArea logArea = new JTextArea();

        CheckPanel() {
            setLayout(new BorderLayout());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;

            JLabel inLabel = new JLabel("input.label");
            inLabel.setName("input.label");
            c.gridx = 0; c.gridy = 0; c.weightx = 0;
            form.add(inLabel, c);
            c.gridx = 1; c.weightx = 1;
            form.add(inputField, c);
            c.gridx = 2; c.weightx = 0;
            form.add(browseIn, c);

            add(form, BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createTitledBorder(""));
            add(scroll, BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            south.add(checkBtn);
            add(south, BorderLayout.SOUTH);

            installDrop(this, inputField);
            browseIn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    choose(inputField, false);
                }
            });
            checkBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    startCheck();
                }
            });
        }

        public void refresh() {
            setLabelText("input.label", tr("input.label"));
            browseIn.setText(tr("browse"));
            checkBtn.setText(tr("check.btn"));
            ((javax.swing.border.TitledBorder) findScrollBorder()).setTitle(tr("log.header"));
        }

        private javax.swing.border.TitledBorder findScrollBorder() {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JScrollPane && ((JScrollPane) comp).getBorder() instanceof javax.swing.border.TitledBorder) {
                    return (javax.swing.border.TitledBorder) ((JScrollPane) comp).getBorder();
                }
            }
            return BorderFactory.createTitledBorder("");
        }

        private void setLabelText(String name, String text) {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JPanel) {
                    for (java.awt.Component sub : ((JPanel) comp).getComponents()) {
                        if (name.equals(sub.getName()) && sub instanceof JLabel) {
                            ((JLabel) sub).setText(text);
                        }
                    }
                }
            }
        }

        void setEnabledAll(boolean b) {
            inputField.setEnabled(b);
            browseIn.setEnabled(b);
            checkBtn.setEnabled(b);
        }

        void appendLog(String line) {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        private void startCheck() {
            final String in = inputField.getText().trim();
            if (in.isEmpty()) {
                JOptionPane.showMessageDialog(McpackDeobfuscator.this, tr("error.paths"),
                        tr("app.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            final Path input = Paths.get(in);
            final Lang langAtStart = mode;

            setBusy(true);
            logArea.setText("");
            appendLog(tr("check.header"));

            final long startNanos = System.nanoTime();
            SwingWorker<CheckEngine.CheckResult, String> worker =
                    new SwingWorker<CheckEngine.CheckResult, String>() {
                @Override
                protected CheckEngine.CheckResult doInBackground() throws Exception {
                    return CheckEngine.check(input, langAtStart);
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        CheckEngine.CheckResult r = get();
                        appendLog("");
                        appendLog(trf("check.done", r.entryCount));
                        if (r.details.isEmpty()) {
                            appendLog("  " + tr("detect.none"));
                        } else {
                            for (String d : r.details) {
                                appendLog("  " + d);
                            }
                        }
                        appendLog("");
                        appendLog(tr("check.verdict.header") + " " + tr(r.verdictKey));
                        appendLog(trf("check.elapsed", (System.nanoTime() - startNanos) / 1e9));
                        statusLabel.setText(tr(r.verdictKey));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException ee) {
                        appendLog(tr("error.open") + ": " + ee.getCause());
                        statusLabel.setText(tr("status.error"));
                    }
                }
            };
            worker.execute();
        }
    }

    // -----------------------------------------------------------------
    // Repair area.
    // -----------------------------------------------------------------

    private class RepairPanel extends JPanel implements Refreshable {
        private static final long serialVersionUID = 1L;

        private final JTextField inputField = new JTextField(38);
        private final JTextField outputField = new JTextField(38);
        private final JButton browseIn = new JButton();
        private final JButton browseOut = new JButton();
        private final JButton repairBtn = new JButton();
        private final JTextArea logArea = new JTextArea();

        RepairPanel() {
            setLayout(new BorderLayout());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;

            JLabel inLabel = new JLabel("input.label");
            inLabel.setName("input.label");
            c.gridx = 0; c.gridy = 0; c.weightx = 0;
            form.add(inLabel, c);
            c.gridx = 1; c.weightx = 1;
            form.add(inputField, c);
            c.gridx = 2; c.weightx = 0;
            form.add(browseIn, c);

            JLabel outLabel = new JLabel("output.label");
            outLabel.setName("output.label");
            c.gridx = 0; c.gridy = 1; c.weightx = 0;
            form.add(outLabel, c);
            c.gridx = 1; c.weightx = 1;
            form.add(outputField, c);
            c.gridx = 2; c.weightx = 0;
            form.add(browseOut, c);

            add(form, BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createTitledBorder(""));
            scroll.setName("log.border");
            add(scroll, BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            south.add(repairBtn);
            add(south, BorderLayout.SOUTH);

            installDrop(this, inputField);
            browseIn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    choose(inputField, false);
                }
            });
            browseOut.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    choose(outputField, true);
                }
            });
            repairBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    startRepair();
                }
            });
        }

        public void refresh() {
            setLabelText("input.label", tr("input.label"));
            setLabelText("output.label", tr("output.label"));
            browseIn.setText(tr("browse"));
            browseOut.setText(tr("browse"));
            repairBtn.setText(tr("repair"));
            ((javax.swing.border.TitledBorder) findScrollBorder()).setTitle(tr("log.header"));
        }

        private javax.swing.border.TitledBorder findScrollBorder() {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JScrollPane && ((JScrollPane) comp).getBorder() instanceof javax.swing.border.TitledBorder) {
                    return (javax.swing.border.TitledBorder) ((JScrollPane) comp).getBorder();
                }
            }
            return BorderFactory.createTitledBorder("");
        }

        private void setLabelText(String name, String text) {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JPanel) {
                    for (java.awt.Component sub : ((JPanel) comp).getComponents()) {
                        if (name.equals(sub.getName()) && sub instanceof JLabel) {
                            ((JLabel) sub).setText(text);
                        }
                    }
                }
            }
        }

        void setEnabledAll(boolean b) {
            inputField.setEnabled(b);
            outputField.setEnabled(b);
            browseIn.setEnabled(b);
            browseOut.setEnabled(b);
            repairBtn.setEnabled(b);
        }

        void appendLog(String line) {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        private void startRepair() {
            final String in = inputField.getText().trim();
            final String out = outputField.getText().trim();
            if (in.isEmpty() || out.isEmpty()) {
                JOptionPane.showMessageDialog(McpackDeobfuscator.this, tr("error.paths"),
                        tr("app.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            final Path input = Paths.get(in);
            final Path output = Paths.get(out);
            if (samePath(input, output)) {
                int choice = JOptionPane.showConfirmDialog(McpackDeobfuscator.this,
                        tr("repair.samefile"), tr("repair.samefile.title"),
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            if (outputField.getText().trim().isEmpty()) {
                outputField.setText(defaultOutput(input, "_repaired").toString());
            }

            setBusy(true);
            logArea.setText("");
            appendLog(tr("detect.header"));
            try {
                List<String> findings = RepairEngine.detect(input);
                if (findings.isEmpty()) {
                    appendLog("  " + tr("detect.none"));
                } else {
                    for (String f : findings) {
                        appendLog("  [!] " + f);
                    }
                }
            } catch (Exception ex) {
                appendLog("  " + tr("error.open") + ": " + ex.getMessage());
            }
            appendLog(tr("repair.header"));

            final long startNanos = System.nanoTime();
            SwingWorker<RepairEngine.Result, String> worker = new SwingWorker<RepairEngine.Result, String>() {
                @Override
                protected RepairEngine.Result doInBackground() throws Exception {
                    return RepairEngine.repair(input, output, new RepairEngine.Progress() {
                        public void update(int done, int total) {
                            setProgress(total > 0 ? (int) (done * 100L / total) : 0);
                        }
                    });
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        RepairEngine.Result r = get();
                        appendLog("");
                        appendLog(trf("repair.done", r.ok, r.skipped, r.fakeDirs, r.bytes));
                        appendLog(trf("repair.content",
                                r.pngFixed, r.oggFixed, r.jsonPrettified, r.nbtChecked, r.nbtBad));
                        appendLog(trf("repair.elapsed", (System.nanoTime() - startNanos) / 1e9));
                        if (!r.warnings.isEmpty()) {
                            appendLog(tr("warnings.header"));
                            for (String w : r.warnings) {
                                appendLog("  ! " + w);
                            }
                        }
                        appendLog("");
                        appendLog(trf("status.done", output.toString()));
                        statusLabel.setText(tr("status.done.short"));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException ee) {
                        appendLog(tr("error.open") + ": " + ee.getCause());
                        statusLabel.setText(tr("status.error"));
                    }
                }
            };
            worker.addPropertyChangeListener(new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent evt) {
                    if ("progress".equals(evt.getPropertyName())) {
                        progressBar.setValue((Integer) evt.getNewValue());
                    }
                }
            });
            worker.execute();
        }
    }

    // -----------------------------------------------------------------
    // Obfuscate area.
    // -----------------------------------------------------------------

    private class ObfuscatePanel extends JPanel implements Refreshable {
        private static final long serialVersionUID = 1L;

        private final JTextField inputField = new JTextField(38);
        private final JTextField outputField = new JTextField(38);
        private final JButton browseIn = new JButton();
        private final JButton browseOut = new JButton();
        private final JButton obfuscateBtn = new JButton();
        private final JTextArea logArea = new JTextArea();

        private final JComboBox<ObfuscateEngine.ObfuscateOptions.NameMode> nameModeBox =
                new JComboBox<ObfuscateEngine.ObfuscateOptions.NameMode>(
                        ObfuscateEngine.ObfuscateOptions.NameMode.values());
        private final JSlider percentSlider = new JSlider(0, 100, 100);
        private final JLabel percentLabel = new JLabel("100%");
        private final JSlider dirPercentSlider = new JSlider(0, 100, 0);
        private final JLabel dirPercentLabel = new JLabel("0%");
        private final JCheckBox fakeEocdChk = new JCheckBox();
        private final JCheckBox crcChk = new JCheckBox();
        private final JCheckBox sizesChk = new JCheckBox("", true);
        private final JCheckBox contentPngChk = new JCheckBox();
        private final JCheckBox contentOggChk = new JCheckBox();
        private final JCheckBox contentJsonChk = new JCheckBox();
        private final JTextField seedField = new JTextField("24237", 10);
        private final DefaultListModel<String> whitelistModel = new DefaultListModel<String>();
        private final JList<String> whitelistList = new JList<String>(whitelistModel);
        private final JTextField whitelistInput = new JTextField(20);
        private final JButton whitelistAddBtn = new JButton("+");
        private final JButton whitelistRemoveBtn = new JButton("-");

        private final JLabel nameModeLabel = new JLabel("namemode.label");
        private final JLabel percentLabelTitle = new JLabel("percent.label");
        private final JLabel dirPercentLabelTitle = new JLabel("fakedir.label");
        private final JLabel seedLabel = new JLabel("seed.label");
        private final JLabel whitelistLabel = new JLabel("whitelist.label");

        ObfuscatePanel() {
            setLayout(new BorderLayout());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;

            // Input.
            JLabel inLabel = new JLabel("input.label");
            inLabel.setName("input.label");
            c.gridx = 0; c.gridy = 0; c.weightx = 0;
            form.add(inLabel, c);
            c.gridx = 1; c.weightx = 1;
            form.add(inputField, c);
            c.gridx = 2; c.weightx = 0;
            form.add(browseIn, c);

            // Output.
            JLabel outLabel = new JLabel("output.label");
            outLabel.setName("output.label");
            c.gridx = 0; c.gridy = 1; c.weightx = 0;
            form.add(outLabel, c);
            c.gridx = 1; c.weightx = 1;
            form.add(outputField, c);
            c.gridx = 2; c.weightx = 0;
            form.add(browseOut, c);

            // Name mode.
            c.gridx = 0; c.gridy = 2; c.weightx = 0;
            form.add(nameModeLabel, c);
            c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
            form.add(nameModeBox, c);
            c.gridwidth = 1;

            // Percent.
            c.gridx = 0; c.gridy = 3; c.weightx = 0;
            form.add(percentLabelTitle, c);
            JPanel percentRow = new JPanel(new BorderLayout(6, 0));
            percentRow.add(percentSlider, BorderLayout.CENTER);
            percentRow.add(percentLabel, BorderLayout.EAST);
            c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
            form.add(percentRow, c);
            c.gridwidth = 1;

            // Options.
            JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            opts.add(fakeEocdChk);
            opts.add(crcChk);
            opts.add(sizesChk);
            opts.add(contentPngChk);
            opts.add(contentOggChk);
            opts.add(contentJsonChk);
            c.gridx = 0; c.gridy = 4; c.gridwidth = 3; c.weightx = 1;
            form.add(opts, c);
            c.gridwidth = 1;

            // Seed.
            c.gridx = 0; c.gridy = 5; c.weightx = 0;
            form.add(seedLabel, c);
            c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
            form.add(seedField, c);
            c.gridwidth = 1;

            // Fake directory percentage.
            c.gridx = 0; c.gridy = 6; c.weightx = 0;
            form.add(dirPercentLabelTitle, c);
            JPanel dirRow = new JPanel(new BorderLayout(6, 0));
            dirRow.add(dirPercentSlider, BorderLayout.CENTER);
            dirRow.add(dirPercentLabel, BorderLayout.EAST);
            c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
            form.add(dirRow, c);
            c.gridwidth = 1;

            // Whitelist (editable list: exact entry names or path prefixes).
            c.gridx = 0; c.gridy = 7; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
            form.add(whitelistLabel, c);
            c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
            JPanel wlPanel = new JPanel(new BorderLayout(6, 4));
            JPanel wlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            wlRow.add(whitelistInput);
            wlRow.add(whitelistAddBtn);
            wlRow.add(whitelistRemoveBtn);
            wlPanel.add(wlRow, BorderLayout.NORTH);
            JScrollPane wlScroll = new JScrollPane(whitelistList);
            wlScroll.setPreferredSize(new Dimension(420, 70));
            wlPanel.add(wlScroll, BorderLayout.CENTER);
            form.add(wlPanel, c);
            c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;

            whitelistAddBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    addWhitelistItem();
                }
            });
            whitelistRemoveBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    removeWhitelistItem();
                }
            });
            whitelistInput.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    addWhitelistItem();
                }
            });

            add(form, BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createTitledBorder(""));
            add(scroll, BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            south.add(obfuscateBtn);
            add(south, BorderLayout.SOUTH);

            installDrop(this, inputField);
            browseIn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    choose(inputField, false);
                }
            });
            browseOut.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    choose(outputField, true);
                }
            });
            obfuscateBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    startObfuscate();
                }
            });

            percentSlider.addChangeListener(new javax.swing.event.ChangeListener() {
                public void stateChanged(javax.swing.event.ChangeEvent e) {
                    percentLabel.setText(percentSlider.getValue() + "%");
                }
            });
            dirPercentSlider.addChangeListener(new javax.swing.event.ChangeListener() {
                public void stateChanged(javax.swing.event.ChangeEvent e) {
                    dirPercentLabel.setText(dirPercentSlider.getValue() + "%");
                }
            });
        }

        /** Build ObfuscateOptions from the current control state. */
        private ObfuscateEngine.ObfuscateOptions readOptions() {
            ObfuscateEngine.ObfuscateOptions o = new ObfuscateEngine.ObfuscateOptions();
            o.nameMode = (ObfuscateEngine.ObfuscateOptions.NameMode) nameModeBox.getSelectedItem();
            o.namePercent = percentSlider.getValue();
            o.fakeDirPercent = dirPercentSlider.getValue();
            o.fakeEocd = fakeEocdChk.isSelected();
            o.perturbCrc = crcChk.isSelected();
            o.perturbSizes = sizesChk.isSelected();
            o.obfuscatePng = contentPngChk.isSelected();
            o.obfuscateOgg = contentOggChk.isSelected();
            o.obfuscateJson = contentJsonChk.isSelected();
            try {
                o.seed = parseSeed(seedField.getText());
            } catch (NumberFormatException ex) {
                o.seed = 0x5EEDL;
                JOptionPane.showMessageDialog(
                        SwingUtilities.getWindowAncestor(this),
                        tr("seed.invalid"), tr("seed.invalid.title"),
                        JOptionPane.WARNING_MESSAGE);
            }
            // Whitelist: the list model holds exact names or path prefixes.
            o.whitelist.clear();
            for (int i = 0; i < whitelistModel.size(); i++) {
                String item = whitelistModel.get(i).trim();
                if (!item.isEmpty()) {
                    o.whitelist.add(item);
                }
            }
            return o;
        }

        public void refresh() {
            setLabelText("input.label", tr("input.label"));
            setLabelText("output.label", tr("output.label"));
            browseIn.setText(tr("browse"));
            browseOut.setText(tr("browse"));
            obfuscateBtn.setText(tr("obfuscate"));
            nameModeLabel.setText(tr("namemode.label"));
            percentLabelTitle.setText(tr("percent.label"));
            dirPercentLabelTitle.setText(tr("fakedir.label"));
            seedLabel.setText(tr("seed.label"));
            whitelistLabel.setText(tr("whitelist.label"));
            fakeEocdChk.setText(tr("fakeeocd"));
            crcChk.setText(tr("crc"));
            sizesChk.setText(tr("sizes"));
            contentPngChk.setText(tr("content.png"));
            contentOggChk.setText(tr("content.ogg"));
            contentJsonChk.setText(tr("content.json"));
            whitelistAddBtn.setText(tr("whitelist.add"));
            whitelistRemoveBtn.setText(tr("whitelist.remove"));
            ((javax.swing.border.TitledBorder) findScrollBorder()).setTitle(tr("log.header"));
        }

        private javax.swing.border.TitledBorder findScrollBorder() {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JScrollPane && ((JScrollPane) comp).getBorder() instanceof javax.swing.border.TitledBorder) {
                    return (javax.swing.border.TitledBorder) ((JScrollPane) comp).getBorder();
                }
            }
            return BorderFactory.createTitledBorder("");
        }

        private void setLabelText(String name, String text) {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JPanel) {
                    for (java.awt.Component sub : ((JPanel) comp).getComponents()) {
                        if (name.equals(sub.getName()) && sub instanceof JLabel) {
                            ((JLabel) sub).setText(text);
                        }
                    }
                }
            }
        }

        private void addWhitelistItem() {
            String t = whitelistInput.getText().trim();
            if (t.isEmpty()) {
                return;
            }
            if (!whitelistModel.contains(t)) {
                whitelistModel.addElement(t);
            }
            whitelistInput.setText("");
        }

        private void removeWhitelistItem() {
            int idx = whitelistList.getSelectedIndex();
            if (idx >= 0) {
                whitelistModel.remove(idx);
            }
        }

        void setEnabledAll(boolean b) {
            inputField.setEnabled(b);
            outputField.setEnabled(b);
            browseIn.setEnabled(b);
            browseOut.setEnabled(b);
            obfuscateBtn.setEnabled(b);
        }

        void appendLog(String line) {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        private void startObfuscate() {
            final String in = inputField.getText().trim();
            final String out = outputField.getText().trim();
            if (in.isEmpty() || out.isEmpty()) {
                JOptionPane.showMessageDialog(McpackDeobfuscator.this, tr("error.paths"),
                        tr("app.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            final Path input = Paths.get(in);
            final Path output = out.isEmpty() ? defaultOutput(input, "_obfuscated")
                    : Paths.get(out);
            if (outputField.getText().trim().isEmpty()) {
                outputField.setText(output.toString());
            }
            final ObfuscateEngine.ObfuscateOptions opts = readOptions();

            setBusy(true);
            logArea.setText("");
            appendLog(tr("obfuscate.header"));
            appendLog("  " + trf("obfuscate.params", opts.nameMode, opts.namePercent,
                    opts.fakeDirPercent, opts.fakeEocd, opts.perturbCrc,
                    opts.perturbSizes, opts.seed));

            final long startNanos = System.nanoTime();
            SwingWorker<ObfuscateEngine.ObfuscateResult, String> worker =
                    new SwingWorker<ObfuscateEngine.ObfuscateResult, String>() {
                @Override
                protected ObfuscateEngine.ObfuscateResult doInBackground() throws Exception {
                    return ObfuscateEngine.obfuscate(input, output, opts);
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        ObfuscateEngine.ObfuscateResult r = get();
                        appendLog("");
                        appendLog(trf("obfuscate.done", r.entries, r.modified, r.bytes));
                        if (opts.anyContent()) {
                            appendLog(trf("obfuscate.content",
                                    r.pngObfuscated, r.oggObfuscated, r.jsonMinified));
                        }
                        appendLog(trf("obfuscate.elapsed", (System.nanoTime() - startNanos) / 1e9));
                        appendLog("");
                        appendLog(trf("status.done", output.toString()));
                        statusLabel.setText(tr("status.done.short"));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException ee) {
                        Throwable c = ee.getCause();
                        appendLog(c instanceof IllegalArgumentException
                                ? c.getMessage()
                                : tr("error.open") + ": " + c);
                        statusLabel.setText(tr("status.error"));
                    }
                }
            };
            worker.execute();
        }
    }

    // -----------------------------------------------------------------
    // Entry point.
    // -----------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // Extract -lang/--lang and the standalone -cli flag; everything else
        // keeps its order so CLI commands can appear anywhere (not just args[0]).
        String lang = "english";
        boolean wantCli = false;
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("--lang".equals(args[i]) || "-lang".equals(args[i])) {
                if (i + 1 < args.length) {
                    String v = args[i + 1].toLowerCase(java.util.Locale.ROOT);
                    if (v.startsWith("chinese")) {
                        lang = "chinese";
                    } else if (v.startsWith("english")) {
                        lang = "english";
                    }
                    i++;
                }
            } else if ("-cli".equals(args[i])) {
                wantCli = true;
            } else {
                rest.add(args[i]);
            }
        }
        String[] cargs = rest.toArray(new String[0]);

        // CLI mode: an explicit -cli flag, or any recognized CLI command.
        if (cargs.length > 0 && dispatchCli(cargs, lang)) {
            return;
        }
        if (wantCli) {
            // -cli given but no command: show usage instead of the GUI.
            printHelp(lang);
            return;
        }

        // GUI mode.
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                McpackDeobfuscator app = new McpackDeobfuscator();
                app.setVisible(true);
                app.maybeShowLicenseOnFirstRun();
            }
        });
    }

    /**
     * Handle a CLI command. Returns true if {@code args} matched a command
     * (help / update / version / deobfuscate / detect / check / obfuscate).
     */
    private static boolean dispatchCli(String[] args, String lang) throws Exception {
        if (args.length >= 1 && ("-help".equals(args[0]) || "--help".equals(args[0])
                || "-h".equals(args[0]))) {
            printHelp(lang);
            return true;
        }
        if (args.length >= 1 && ("-update".equals(args[0]) || "--update".equals(args[0]))) {
            checkUpdate(lang);
            return true;
        }
        if (args.length >= 1 && "-version".equals(args[0])) {
            System.out.println("McpackDeobfuscator " + VERSION + " (" + GITHUB_URL + ")");
            return true;
        }
        if (args.length >= 1 && ("-repair".equals(args[0]) || "-deobfuscate".equals(args[0]))) {
            if (args.length < 3) {
                System.err.println("usage: -deobfuscate <in.zip> <out.zip>");
                System.exit(2);
            }
            requireInput(Paths.get(args[1]));
            runCliRepair(Paths.get(args[1]), Paths.get(args[2]));
            return true;
        }
        if (args.length >= 1 && "-detect".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("usage: -detect <in.zip>");
                System.exit(2);
            }
            requireInput(Paths.get(args[1]));
            runCliDetect(Paths.get(args[1]));
            return true;
        }
        if (args.length >= 1 && "-check".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("usage: -check <in.zip>");
                System.exit(2);
            }
            requireInput(Paths.get(args[1]));
            runCliCheck(Paths.get(args[1]));
            return true;
        }
        if (args.length >= 1 && "-obfuscate".equals(args[0])) {
            if (args.length < 3) {
                System.err.println("usage: -obfuscate <in.zip> <out.zip> "
                        + "[--name <none|upper|digit|random>] [--percent <0-100>] "
                        + "[--fake-dir <0-100>] [--eocd] [--crc] [--no-sizes] "
                        + "[--content|--content-png|--content-ogg|--content-json] "
                        + "[--seed <n>] [--whitelist <a,b,c>]");
                System.exit(2);
            }
            runCliObfuscate(args);
            return true;
        }
        return false;
    }

    private static void runCliRepair(final Path input, final Path output) throws Exception {
        System.out.println("== mcpack-deobfuscator ==");
        System.out.println("input : " + input.toAbsolutePath());
        System.out.println("output: " + output.toAbsolutePath());

        List<String> findings = RepairEngine.detect(input);
        System.out.println("findings: " + (findings.isEmpty() ? "none" : findings));

        long t0 = System.nanoTime();
        RepairEngine.Result r = RepairEngine.repair(input, output, null);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.println("ok=" + r.ok + " skipped=" + r.skipped
                + " fakeDirs=" + r.fakeDirs + " bytes=" + r.bytes);
        System.out.println("content: pngFixed=" + r.pngFixed + " oggFixed=" + r.oggFixed
                + " jsonPrettified=" + r.jsonPrettified
                + " nbtChecked=" + r.nbtChecked + " nbtBad=" + r.nbtBad);
        for (String w : r.warnings) {
            System.out.println("WARN " + w);
        }
        System.out.println(String.format(java.util.Locale.ROOT, "elapsed: %.2f s", sec));
        System.out.println("done: " + output.toAbsolutePath());
    }

    private static void runCliDetect(final Path input) throws Exception {
        List<String> findings = RepairEngine.detect(input);
        System.out.println("findings: " + (findings.isEmpty() ? "none" : findings));
    }

    /** Command-line check entry point. */
    private static void runCliCheck(final Path input) throws Exception {
        long t0 = System.nanoTime();
        CheckEngine.CheckResult r = CheckEngine.check(input, Lang.EN);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.println("verdict: " + r.verdictKey);
        System.out.println("entries=" + r.entryCount
                + " nameMismatch=" + r.nameMismatch
                + " sizeMismatch=" + r.sizeMismatch
                + " crcFieldMismatch=" + r.crcFieldMismatch
                + " fakeDirs=" + r.fakeDirs
                + " forgedEocd=" + r.forgedEocdAt0
                + " badCrc=" + r.badCrc
                + " unreadable=" + r.unreadable
                + " jvmReadable=" + r.jvmReadable);
        System.out.println("obfuscated=" + r.contentObfuscation
                + " png=" + r.packSquashPng
                + " ogg=" + r.packSquashOgg
                + " json=" + r.packSquashJson
                + " crcFingerprint=" + r.packSquashCrcFingerprint);
        for (String d : r.details) {
            System.out.println("  " + d);
        }
        System.out.println(String.format(java.util.Locale.ROOT, "elapsed: %.2f s", sec));
    }

    /** Command-line protection entry point with tunable parameters. */
    private static void runCliObfuscate(String[] args) throws Exception {
        Path input = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        requireInput(input);
        ObfuscateEngine.ObfuscateOptions opts = new ObfuscateEngine.ObfuscateOptions();

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--name".equals(a) && i + 1 < args.length) {
                opts.nameMode = ObfuscateEngine.ObfuscateOptions.NameMode.valueOf(
                        args[++i].toUpperCase(java.util.Locale.ROOT));
            } else if ("--percent".equals(a) && i + 1 < args.length) {
                opts.namePercent = Integer.parseInt(args[++i]);
            } else if ("--fake-dir".equals(a) && i + 1 < args.length) {
                opts.fakeDirPercent = Integer.parseInt(args[++i]);
            } else if ("--eocd".equals(a)) {
                opts.fakeEocd = true;
            } else if ("--crc".equals(a)) {
                opts.perturbCrc = true;
            } else if ("--no-sizes".equals(a)) {
                opts.perturbSizes = false;
            } else if ("--content".equals(a)) {
                opts.obfuscatePng = true;
                opts.obfuscateOgg = true;
                opts.obfuscateJson = true;
            } else if ("--content-png".equals(a)) {
                opts.obfuscatePng = true;
            } else if ("--content-ogg".equals(a)) {
                opts.obfuscateOgg = true;
            } else if ("--content-json".equals(a)) {
                opts.obfuscateJson = true;
            } else if ("--seed".equals(a) && i + 1 < args.length) {
                opts.seed = parseSeed(args[++i]);
            } else if ("--whitelist".equals(a) && i + 1 < args.length) {
                for (String part : args[++i].split(",")) {
                    if (!part.trim().isEmpty()) {
                        opts.whitelist.add(part.trim());
                    }
                }
            }
        }

        System.out.println("== mcpack-deobfuscator ==");
        System.out.println("input : " + input.toAbsolutePath());
        System.out.println("output: " + output.toAbsolutePath());
        System.out.println("mode=" + opts.nameMode + " percent=" + opts.namePercent
                + " fakeDir=" + opts.fakeDirPercent
                + " eocd=" + opts.fakeEocd + " crc=" + opts.perturbCrc
                + " sizes=" + opts.perturbSizes
                + " contentPng=" + opts.obfuscatePng
                + " contentOgg=" + opts.obfuscateOgg
                + " contentJson=" + opts.obfuscateJson
                + " seed=" + opts.seed + " whitelist=" + opts.whitelist);
        ObfuscateEngine.ObfuscateResult r;
        long t0 = System.nanoTime();
        try {
            r = ObfuscateEngine.obfuscate(input, output, opts);
        } catch (IllegalArgumentException ex) {
            System.err.println("error: " + ex.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("entries=" + r.entries + " modified=" + r.modified
                + " bytes=" + r.bytes);
        System.out.println("content: png=" + r.pngObfuscated + " ogg=" + r.oggObfuscated
                + " json=" + r.jsonMinified);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.println(String.format(java.util.Locale.ROOT, "elapsed: %.2f s", sec));
        System.out.println("done: " + output.toAbsolutePath());
    }

    /**
     * Parse a seed from CLI or GUI input. Accepts decimal or {@code 0x}-prefixed
     * hexadecimal and covers the full 64-bit unsigned range (0 .. 2^64-1) by
     * storing the value as the corresponding bit pattern in a {@code long};
     * negative values are still accepted for backwards compatibility. Throws
     * {@link NumberFormatException} if the value is out of range or malformed.
     */
    private static long parseSeed(String s) {
        String t = s.trim();
        boolean hex = t.startsWith("0x") || t.startsWith("0X");
        String num = hex ? t.substring(2) : t;
        if (num.isEmpty()) {
            throw new NumberFormatException("empty seed");
        }
        if (num.charAt(0) == '-') {
            return Long.parseLong(num, hex ? 16 : 10);
        }
        // Unsigned parse keeps the full 64-bit bit pattern (Random accepts any
        // long), so 0xFFFFFFFFFFFFFFFF and 18446744073709551615 are valid.
        return Long.parseUnsignedLong(num, hex ? 16 : 10);
    }

    /** Print the full CLI help (usage, options, about). {@code chinese} selects Chinese. */
    private static void printHelp(String lang) {
        boolean zh = "chinese".equals(lang);
        if (zh) {
            System.out.println("== " + loadMetaLang("name", true, "\u6211\u7684\u4e16\u754c\u8d44\u6e90\u5305\u53cd\u6df7\u6dc6\u5de5\u5177") + " ==");
            System.out.println(loadMetaLang("description", true,
                    "\u53cd\u6df7\u6dc6\u88ab PackSquash \u5f0f\u5de5\u5177\u6df7\u6dc6/\u4fdd\u62a4\u7684 Minecraft \u8d44\u6e90\u5305\uff08mcpack\uff09\uff0c\u5e76\u6821\u9a8c\u5176\u5b8c\u6574\u6027\u3002"));
            System.out.println("注意: 中文输出不推荐,请优先使用英文。");
            System.out.println();
            System.out.println("用法:");
            System.out.println("  java -jar mcpack-deobfuscator.jar                          启动图形界面");
            System.out.println("  java -jar mcpack-deobfuscator.jar -cli <命令> [参数]          强制命令行模式(不启动图形界面)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -help                     显示本帮助(关于)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -version                  显示版本");
            System.out.println("  java -jar mcpack-deobfuscator.jar -update                   检查更新(GitHub API,无需令牌)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -check <输入.zip>         检查压缩包");
            System.out.println("  java -jar mcpack-deobfuscator.jar -deobfuscate <输入.zip> <输出.zip>  反混淆资源包");
            System.out.println("  java -jar mcpack-deobfuscator.jar -detect <输入.zip>        检测内容混淆");
            System.out.println("  java -jar mcpack-deobfuscator.jar -obfuscate <输入.zip> <输出.zip> [选项]  施加混淆");
            System.out.println();
            System.out.println("混淆选项:");
            System.out.println("  --name <none|upper|digit|random>     改名方式(默认 upper)");
            System.out.println("  --percent <0-100>                    改名条目比例(默认 100)");
            System.out.println("  --fake-dir <0-100>                   伪装成目录的比例(默认 0)");
            System.out.println("  --eocd                               在偏移 0 处附加伪 EOCD 签名");
            System.out.println("  --crc                                扰动 CRC-32 字段");
            System.out.println("  --no-sizes                           不扰动中央目录大小");
            System.out.println("  --content                            混淆 PNG+Ogg+JSON 内容");
            System.out.println("  --content-png|--content-ogg|--content-json  只混淆一种内容类型");
            System.out.println("  --seed <n>                           随机种子(十进制或 0x 十六进制,0..2^64-1)");
            System.out.println("  --whitelist <a,b,c>                  白名单(精确名或路径前缀,逗号分隔)");
            System.out.println("  -lang|--lang <chinese|english>           \u8f93\u51fa\u8bed\u8a00(\u9ed8\u8ba4 english;chinese \u4e0d\u63a8\u8350)");
            System.out.println();
            System.out.println("关于:");
            System.out.println("  许可证: Unlicense(公有领域)");
            System.out.println("  仓库: " + GITHUB_URL);
            System.out.println("  版本: " + VERSION);
        } else {
            System.out.println("== " + loadMetaLang("name", false, "mcpack-deobfuscator") + " ==");
            System.out.println(loadMetaLang("description", false,
                    "Deobfuscate Minecraft resource packs (mcpack) protected or obfuscated by PackSquash tools, and verify their integrity."));
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  java -jar mcpack-deobfuscator.jar                        Start the GUI");
            System.out.println("  java -jar mcpack-deobfuscator.jar -cli <command> [args]    Force CLI mode (no GUI)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -help                   Show this help (about)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -version                Show version");
            System.out.println("  java -jar mcpack-deobfuscator.jar -update                 Check for updates (GitHub API, no token)");
            System.out.println("  java -jar mcpack-deobfuscator.jar -check <in.zip>         Inspect an archive");
            System.out.println("  java -jar mcpack-deobfuscator.jar -deobfuscate <in.zip> <out.zip>   Deobfuscate a protected resource pack");
            System.out.println("  java -jar mcpack-deobfuscator.jar -detect <in.zip>        Detect PackSquash obfuscation");
            System.out.println("  java -jar mcpack-deobfuscator.jar -obfuscate <in.zip> <out.zip> [options]   Re-apply obfuscation");
            System.out.println();
            System.out.println("Obfuscate options:");
            System.out.println("  --name <none|upper|digit|random>   Name rewrite mode (default upper)");
            System.out.println("  --percent <0-100>                  % of entries renamed (default 100)");
            System.out.println("  --fake-dir <0-100>                 % of entries disguised as dirs (default 0)");
            System.out.println("  --eocd                             Prepend a forged EOCD at offset 0");
            System.out.println("  --crc                              Perturb CRC-32 fields");
            System.out.println("  --no-sizes                         Do not perturb central-directory sizes");
            System.out.println("  --content                          Obfuscate PNG + Ogg + JSON contents");
            System.out.println("  --content-png|--content-ogg|--content-json   Obfuscate one content type");
            System.out.println("  --seed <n>                         RNG seed (decimal or 0x-hex, 0..2^64-1)");
            System.out.println("  --whitelist <a,b,c>                Entries (exact name or path prefix) untouched");
            System.out.println("  -lang|--lang <chinese|english>            Output language (default english; chinese not recommended)");
            System.out.println();
            System.out.println("About:");
            System.out.println("  License: Unlicense (public domain)");
            System.out.println("  Repo: " + GITHUB_URL);
            System.out.println("  Version: " + VERSION);
        }
    }

    /**
     * Check for a newer release against the GitHub API. The request is
     * anonymous: no token is used or accepted, only the public
     * {@code releases/latest} endpoint.
     */
    private static void checkUpdate(String lang) {
        boolean zh = "chinese".equals(lang);
        String latest = null;
        String err = null;
        try {
            URL url = new URL("https://api.github.com/repos/848488489-ff/McpackDeobfuscator/releases/latest");
            // No Authorization header anywhere: anonymous request, no token.
            // TLS validation failures are reported as errors, never bypassed.
            HttpURLConnection conn = openConn(url);
            int code = conn.getResponseCode();
            if (code == 200) {
                latest = parseTagName(readAll(conn.getInputStream()));
            } else {
                err = "HTTP " + code;
            }
        } catch (Exception e) {
            err = e.getMessage();
        }
        if (zh) {
            if (latest == null) {
                System.out.println("检查更新失败: " + (err == null ? "无发布信息" : err));
                return;
            }
            System.out.println("当前版本: " + VERSION);
            System.out.println("最新发布: " + latest);
            System.out.println(latest.equals("v" + VERSION)
                    ? "已是最新版本。" : "发现新版本: " + latest);
        } else {
            if (latest == null) {
                System.out.println("update check failed: " + (err == null ? "no release info" : err));
                return;
            }
            System.out.println("current version: " + VERSION);
            System.out.println("latest release : " + latest);
            System.out.println(latest.equals("v" + VERSION)
                    ? "You are up to date." : "A new version is available: " + latest);
        }
    }

    /** Exit with a clear error if the input path does not exist. */
    private static void requireInput(Path p) {
        if (!Files.exists(p)) {
            System.err.println("error: file not found: " + p.toAbsolutePath());
            System.exit(1);
        }
    }

    /** Open an HTTPS connection to the anonymous update-check endpoint. */
    private static HttpURLConnection openConn(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "McpackDeobfuscator/" + VERSION);
        return conn;
    }

    /** Extract the {@code tag_name} field from a GitHub API JSON response. */
    private static String parseTagName(String body) {
        if (body == null) {
            return null;
        }
        String key = "\"tag_name\":\"";
        int i = body.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = body.indexOf('"', start);
        return end < 0 ? null : body.substring(start, end);
    }

    /** Read an entire stream as UTF-8 text. */
    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
