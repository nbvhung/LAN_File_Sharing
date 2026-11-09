package vn.edu.lanfileshare.client.ui;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.event.AWTEventListener;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.server.network.*;
import vn.edu.lanfileshare.server.discovery.*;
import vn.edu.lanfileshare.client.discovery.*;
import vn.edu.lanfileshare.server.storage.*;
import vn.edu.lanfileshare.server.transfer.*;

/** Real JFrame on an available display. Explicitly skipped in headless CI. */
class DesktopSmokeTest {
    @TempDir
    Path root;

    static <T extends Component> T find(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child))
                return type.cast(child);
            if (child instanceof Container container) {
                T value = find(container, name, type);
                if (value != null)
                    return value;
            }
        }
        return null;
    }

    static JFileChooser chooser(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JFileChooser value)
                return value;
            if (child instanceof Container container) {
                JFileChooser value = chooser(container);
                if (value != null)
                    return value;
            }
        }
        return null;
    }

    static UiSnapshot await(MainFrame frame, Predicate<UiSnapshot> condition) throws Exception {
        CompletableFuture<UiSnapshot> result = new CompletableFuture<>();
        AtomicReference<Timer> timer = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            timer.set(new Timer(20, event -> {
                UiSnapshot value = frame.controller().snapshot();
                if (condition.test(value)) {
                    ((Timer) event.getSource()).stop();
                    result.complete(value);
                }
            }));
            timer.get().start();
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(() -> timer.get().stop());
        }
    }

    @Test
    void opensConnectsRendersResizesDisconnectsAndCloses() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "Desktop display unavailable; headless controller tests still exercise real TCP transfers");
        FileStorageService storage = new FileStorageService(new StorageConfiguration(root.resolve("server")));
        JsonFileCatalog catalog = new JsonFileCatalog(storage);
        try (TransferCoordinator transfers = new TransferCoordinator(storage, catalog, 1L << 30);
                TcpServer server = new TcpServer(java.net.InetAddress.getLoopbackAddress(), 0, 4, 10000,
                        new RequestDispatcher(catalog, transfers));
                DiscoveryServer discovery = new DiscoveryServer(new DiscoveryConfiguration(
                        java.net.InetAddress.getLoopbackAddress(), 0, server.port()))) {
            server.start();
            discovery.start();
            AtomicReference<MainFrame> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UiTheme.install();
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
                MainFrame frame = new MainFrame(new ServiceClientBackend(root.resolve("client")),
                        new ServiceServerDiscovery(() -> new DiscoveryClient(new DiscoveryOptions(100, 2)),
                                port -> java.util.List.of(DiscoveryTarget.unicast(
                                        java.net.InetAddress.getLoopbackAddress(), port))),
                        "manual.invalid", 49123, discovery.port());
                ref.set(frame);
                frame.setVisible(true);
            });
            MainFrame frame = ref.get();
            Path source = root.resolve("desktop-source.bin"), target = root.resolve("desktop-received.bin");
            byte[] data = new byte[1024 * 1024];
            new java.util.Random(71).nextBytes(data);
            data[0] = 0;
            Files.write(source, data);
            ConcurrentLinkedQueue<Path> selections = new ConcurrentLinkedQueue<>();
            selections.add(source);
            selections.add(target);
            AWTEventListener dialogs = event -> {
                if (event instanceof WindowEvent window && window.getID() == WindowEvent.WINDOW_OPENED
                        && window.getWindow() instanceof JDialog dialog) {
                    SwingUtilities.invokeLater(() -> {
                        JFileChooser selected = chooser(dialog);
                        if (selected != null) {
                            Path path = selections.poll();
                            assertNotNull(path, "Unexpected file chooser");
                            selected.setSelectedFile(path.toFile());
                            selected.approveSelection();
                        } else
                            dialog.dispose(); // Acknowledge real success/error message; assertions still require Completed.
                    });
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(dialogs, AWTEvent.WINDOW_EVENT_MASK);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("LAN File Sharing", frame.getTitle());
                    assertFalse(find(frame, "refresh", JButton.class).isEnabled());
                    find(frame, "discover", JButton.class).doClick(0);
                });
                UiSnapshot found = await(frame, s -> !s.busy() && s.discoveredServers().size() == 1);
                assertNull(found.selectedServer());
                assertFalse(found.connected());
                assertEquals(0, server.statistics().activeConnections());
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("manual.invalid", find(frame, "serverHost", JTextField.class).getText());
                    assertEquals("49123", find(frame, "serverPort", JTextField.class).getText());
                    JComboBox<?> choices = find(frame, "discoveredServers", JComboBox.class);
                    assertNull(choices.getSelectedItem());
                    choices.setSelectedIndex(1);
                    assertEquals("127.0.0.1", find(frame, "serverHost", JTextField.class).getText());
                    assertEquals(Integer.toString(server.port()), find(frame, "serverPort", JTextField.class).getText());
                    assertFalse(frame.controller().snapshot().connected());
                    find(frame, "connect", JButton.class).doClick(0);
                });
                assertTrue(await(frame, s -> s.connected() && !s.busy()).files().isEmpty());
                SwingUtilities.invokeLater(() -> find(frame, "upload", JButton.class).doClick(0));
                UiSnapshot uploaded = await(frame, s -> !s.busy() && s.state() == UiState.COMPLETED);
                assertEquals(1, uploaded.files().size());
                SwingUtilities.invokeLater(() -> {
                    find(frame, "workspaceTabs", JTabbedPane.class).setSelectedIndex(0);
                    find(frame, "serverFiles", JTable.class).setRowSelectionInterval(0, 0);
                    find(frame, "download", JButton.class).doClick(0);
                });
                await(frame, s -> !s.busy() && s.state() == UiState.COMPLETED && s.transfers().size() == 2);
                assertEquals(-1, Files.mismatch(source, target));
                assertEquals(vn.edu.lanfileshare.common.io.FileHashes.sha256(source),
                        vn.edu.lanfileshare.common.io.FileHashes.sha256(target));
                assertTrue(selections.isEmpty());
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(find(frame, "upload", JButton.class).isEnabled());
                    assertEquals(1, find(frame, "workspaceTabs", JTabbedPane.class).getSelectedIndex());
                    assertTrue(find(frame, "transfers", JTable.class).getVisibleRect().height >= 96);
                    frame.setSize(1000, 680);
                    frame.validate();
                });
                SwingUtilities.invokeAndWait(() -> {
                    frame.setSize(1180, 900);
                    frame.validate();
                });
                SwingUtilities.invokeAndWait(() -> {
                    Container content = frame.getContentPane();
                    BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    content.paint(graphics);
                    graphics.dispose();
                    try {
                        Files.createDirectories(Path.of("target"));
                        ImageIO.write(image, "png", Path.of("target/swing-client-complete.png").toFile());
                    } catch (java.io.IOException ex) {
                        throw new AssertionError(ex);
                    }
                    find(frame, "disconnect", JButton.class).doClick(0);
                });
                await(frame, s -> !s.connected() && !s.busy());
                SwingUtilities
                        .invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
                frame.controller().termination().get(3, TimeUnit.SECONDS);
                assertFalse(frame.isDisplayable());
                System.out.println(
                        "SWING_DESKTOP_PASS display=true discoverySelection=true uploadDownloadBytes=1048576 byteForByte=true screenshot=target/swing-client-complete.png");
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(dialogs);
                SwingUtilities.invokeAndWait(() -> frame.controller().close());
                frame.controller().termination().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
