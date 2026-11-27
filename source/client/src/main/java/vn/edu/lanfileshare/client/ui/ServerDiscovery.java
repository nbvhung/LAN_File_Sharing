package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.util.List;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;

/** Tìm server qua UDP trên luồng nền và cho phép luồng điều khiển hủy lượt tìm. */
public interface ServerDiscovery extends AutoCloseable {
    List<DiscoveredServer> discover(int udpPort) throws IOException;

    void cancel();

    @Override
    void close();
}
