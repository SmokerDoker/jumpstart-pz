package io.github.jumpstartpz.installer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/** The installer window: what is found, one button to install (or update), one to remove. */
final class InstallerWindow {
    private static final Color GREEN = new Color(0x1E7B34);
    private static final Color AMBER = new Color(0x9A6700);
    private static final Color RED = new Color(0xC62828);

    private final Texts t;
    private final Optional<Path> payloadDir;
    private final JFrame frame;
    private final JLabel gameValue = new JLabel();
    private final JLabel stateValue = new JLabel();
    private final JLabel biteCheckValue = new JLabel();
    private final JLabel updateValue = new JLabel();
    private final JCheckBox autoUpdate;
    private final JLabel payloadLabel = new JLabel(" ");
    private final JTextArea log = new JTextArea(10, 70);
    private final JButton choose;
    private final JButton install;
    private final JButton uninstall;
    private final JButton play;
    private Optional<Path> gameDir;
    private Setup setup;
    private Payload payload;

    private InstallerWindow(Texts t, Optional<Path> gameDir, Optional<Path> payloadDir) {
        this.t = t;
        this.gameDir = gameDir;
        this.payloadDir = payloadDir;
        this.frame = new JFrame(t.get("title"));
        this.choose = new JButton(t.get("game.choose"));
        this.install = new JButton(t.get("install"));
        this.uninstall = new JButton(t.get("uninstall"));
        this.play = new JButton(t.get("play"));
        this.autoUpdate = new JCheckBox(t.get("autoUpdate.check"), true);
    }

    static void open(Texts t, Optional<Path> gameDir, Optional<Path> payloadDir) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // keep the default look
            }
            new InstallerWindow(t, gameDir, payloadDir).show();
        });
    }

    private void show() {
        JLabel title = new JLabel(t.get("title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel subtitle = new JLabel(t.get("subtitle"));
        JPanel head = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        head.add(title, c);
        head.add(subtitle, c);
        head.add(payloadLabel, c);

        JPanel facts = new JPanel(new GridBagLayout());
        row(facts, 0, t.get("game"), gameValue, choose);
        row(facts, 1, t.get("state"), stateValue, null);
        row(facts, 2, t.get("autoUpdate"), updateValue, null);
        row(facts, 3, t.get("bitecheck"), biteCheckValue, null);
        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1;
        cc.gridy = 4;
        cc.gridwidth = 2;
        cc.anchor = GridBagConstraints.WEST;
        facts.add(autoUpdate, cc);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logPane = new JScrollPane(log);
        logPane.setPreferredSize(new Dimension(720, 220));

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(facts, BorderLayout.NORTH);
        center.add(logPane, BorderLayout.CENTER);

        install.setFont(install.getFont().deriveFont(Font.BOLD, 15f));
        play.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(uninstall);
        buttons.add(play);
        buttons.add(install);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        root.add(head, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        choose.addActionListener(e -> chooseGame());
        install.addActionListener(e -> install());
        uninstall.addActionListener(e -> uninstall());
        play.addActionListener(e -> Steam.startGame(setup.location().os()));

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.getRootPane().setDefaultButton(install);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        loadPayload();
        refresh();
    }

    private static void row(JPanel panel, int y, String name, JLabel value, JButton button) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 12);
        c.anchor = GridBagConstraints.WEST;
        JLabel label = new JLabel(name);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        c.gridx = 0;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(value, c);
        if (button != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            panel.add(button, c);
        }
    }

    private void loadPayload() {
        background(() -> payloadDir.isPresent() ? Optional.of(Payload.of(payloadDir.get())) : Payload.bundled(), p -> {
            payload = p.orElse(null);
            payloadLabel.setText(payload == null ? t.get("error.noPayload") : t.get("payload", payload.version()));
            payloadLabel.setForeground(payload == null ? RED : Color.GRAY);
            updateButtons();
        });
    }

    /** Finds the game and shows what is installed. */
    private void refresh() {
        background(() -> {
            Optional<GameLocation> loc = GameLocation.find(gameDir);
            return loc.map(l -> new Setup(l, t, this::append, Optional.empty()));
        }, s -> {
            setup = s.orElse(null);
            if (setup == null) {
                gameValue.setText(t.get("game.missing"));
                gameValue.setForeground(RED);
                stateValue.setText("–");
                updateValue.setText("–");
                biteCheckValue.setText("–");
                append(t.get("error.noGame"));
                updateButtons();
                return;
            }
            gameValue.setText(setup.location().javaDir().toString());
            gameValue.setForeground(GREEN);
            if (!Wrapper.available()) {
                autoUpdate.setSelected(false);
                autoUpdate.setEnabled(false);
            }
            background(setup::status, this::showStatus);
        });
    }

    private void showStatus(Setup.Status s) {
        if (s.installed()) {
            stateValue.setText(t.get("state.installed", s.jumpstartVersion().orElse("?")));
            stateValue.setForeground(GREEN);
        } else if (s.anything()) {
            StringBuilder missing = new StringBuilder();
            addIf(missing, !s.jumpstartJar(), t.get("part.jar"));
            addIf(missing, !s.zombieBuddyMod(), t.get("part.zbmod"));
            addIf(missing, !s.launchReady(), t.get("part.launch"));
            stateValue.setText(t.get("state.incomplete", missing));
            stateValue.setForeground(AMBER);
        } else {
            stateValue.setText(t.get("state.none"));
            stateValue.setForeground(Color.DARK_GRAY);
        }
        updateValue.setText(t.get(s.autoUpdate() ? "autoUpdate.on" : "autoUpdate.off"));
        updateValue.setForeground(s.autoUpdate() ? GREEN : Color.DARK_GRAY);
        biteCheckValue.setText(t.get(s.biteCheck() ? "bitecheck.yes" : "bitecheck.no"));
        biteCheckValue.setForeground(s.biteCheck() ? GREEN : Color.DARK_GRAY);
        install.setText(t.get(s.anything() ? "update" : "install"));
        uninstall.setVisible(s.anything());
        play.setEnabled(s.installed());
        updateButtons();
    }

    private static void addIf(StringBuilder sb, boolean condition, String part) {
        if (condition) {
            sb.append(sb.isEmpty() ? "" : ", ").append(part);
        }
    }

    private void updateButtons() {
        install.setEnabled(setup != null && payload != null);
        uninstall.setEnabled(setup != null);
        choose.setEnabled(true);
    }

    private void busy() {
        install.setEnabled(false);
        uninstall.setEnabled(false);
        choose.setEnabled(false);
        play.setEnabled(false);
        append(t.get("working"));
    }

    private void chooseGame() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(t.get("game.chooseTitle"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            gameDir = Optional.of(chooser.getSelectedFile().toPath());
            refresh();
        }
    }

    private void install() {
        busy();
        // with the auto-update box: Jumpstart's wrapper goes into the launch options too
        Setup installer = new Setup(setup.location(), t, this::append,
                autoUpdate.isSelected() ? Wrapper.prefix(setup.location()) : Optional.empty());
        background(() -> InstallerMain.choosePayload(payloadDir, this::append, t), chosen -> {
            Optional<String> installed = installer.installedVersion();
            if (InstallerMain.isDowngrade(chosen.version(), installed)) {
                int answer = JOptionPane.showConfirmDialog(frame, t.get("error.downgrade", installed.orElse("?"), chosen.version()),
                        t.get("title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    refresh();
                    return;
                }
            }
            installWith(installer, chosen);
        });
    }

    private void installWith(Setup installer, Payload chosen) {
        Setup previous = setup;
        setup = installer; // the launch step below shows this setup's launch options
        background(() -> {
            append(t.get("payload", chosen.version()));
            installer.installFiles(chosen);
            return installer.launchOptions(true);
        }, r -> launchStep(true, r, () -> {
            setup = previous;
            append(t.get("done.install"));
            refresh();
            JOptionPane.showMessageDialog(frame, t.get("done.install"), t.get("title"), JOptionPane.INFORMATION_MESSAGE);
        }));
    }

    private void uninstall() {
        int answer = JOptionPane.showConfirmDialog(frame, t.get("confirm.uninstall"), t.get("title"), JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        busy();
        // launch options first: they decide whether Jumpstart.jar may go
        background(() -> setup.launchOptions(false), r -> launchStep(false, r, () -> background(() -> {
            setup.uninstallFiles();
            if (!setup.status().autoUpdate()) {
                Bootstrap.remove(); // nothing starts it any more
            }
            return Boolean.TRUE;
        }, ok -> {
            append(t.get("done.uninstall"));
            refresh();
        })));
    }

    /** macOS and Linux: Steam may have to close for the launch options; or the player sets them by hand. */
    private void launchStep(boolean install, Setup.LaunchResult r, Runnable then) {
        switch (r) {
            case DONE, NOT_NEEDED -> then.run();
            case STEAM_RUNNING -> {
                String wanted = setup.suggestedLaunchOptions(install);
                Object[] options = {t.get("steam.close"), t.get("steam.manual")};
                int choice = JOptionPane.showOptionDialog(frame, t.get("steam.running", wanted.isEmpty() ? "–" : wanted),
                        t.get("steam.running.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (choice == 0) {
                    append(t.get("steam.closing"));
                    background(() -> {
                        if (!Steam.close(setup.location().os(), Duration.ofSeconds(60))) {
                            return Setup.LaunchResult.STEAM_RUNNING;
                        }
                        Setup.LaunchResult result = setup.launchOptions(install);
                        Steam.open(setup.location().os(), "steam://open/main"); // Steam comes back by itself
                        append(t.get("steam.restarted"));
                        return result;
                    }, again -> {
                                if (again == Setup.LaunchResult.STEAM_RUNNING) {
                                    append(t.get("steam.notClosed"));
                                    manual(install);
                                    then.run();
                                } else {
                                    launchStep(install, again, then);
                                }
                            });
                } else {
                    manual(install);
                    then.run();
                }
            }
            case NO_STEAM_CONFIG -> {
                append(t.get("noSteamConfig"));
                manual(install);
                then.run();
            }
        }
    }

    private void manual(boolean install) {
        String wanted = setup.suggestedLaunchOptions(install);
        String text = t.get(install ? "manual" : "manual.uninstall", wanted);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(wanted), null);
            text = text + "\n\n" + t.get("manual.clipboard");
        } catch (RuntimeException e) {
            // no clipboard: the text is in the dialog and the log
        }
        append(text);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(60);
        area.setBackground(frame.getBackground());
        JOptionPane.showMessageDialog(frame, area, t.get("manual.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void append(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Runs work off the event thread; the result goes to {@code done} on it, an error to the log and a dialog. */
    private <T> void background(Callable<T> work, Consumer<T> done) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    done.accept(get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = t.get("error", cause.getMessage());
                    append(message);
                    JOptionPane.showMessageDialog(frame, message, t.get("title"), JOptionPane.ERROR_MESSAGE);
                    refresh();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }
}
