package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.util.List;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;

/** Desktop boundary over the existing UDP service; operations run off the EDT. */
public interface ServerDiscovery extends AutoCloseable {
    List<DiscoveredServer> discover(int udpPort) throws IOException;

    void cancel();

    @Override
    void close();
}
