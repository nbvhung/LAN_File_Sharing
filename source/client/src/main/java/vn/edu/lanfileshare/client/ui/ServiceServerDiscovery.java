package vn.edu.lanfileshare.client.ui;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import vn.edu.lanfileshare.client.discovery.BroadcastTargets;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;
import vn.edu.lanfileshare.client.discovery.DiscoveryClient;
import vn.edu.lanfileshare.client.discovery.DiscoveryTarget;

/** Chọn đích UDP và giữ lượt tìm đang chạy để nút Cancel có thể dừng đúng lượt. */
public final class ServiceServerDiscovery implements ServerDiscovery {
    @FunctionalInterface
    public interface Targets {
        List<DiscoveryTarget> forPort(int udpPort) throws IOException;
    }

    private final Supplier<DiscoveryClient> clientFactory;
    private final Targets targetsForPort;
    private final Object searchLock = new Object();
    private DiscoveryClient activeSearch;
    private boolean closed;

    public ServiceServerDiscovery() {
        this(DiscoveryClient::new, BroadcastTargets::forPort);
    }

    /** Test có thể chỉ định đích localhost và vẫn dùng dịch vụ UDP thật. */
    public ServiceServerDiscovery(Supplier<DiscoveryClient> clientFactory, Targets targetsForPort) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
        this.targetsForPort = Objects.requireNonNull(targetsForPort);
    }

    @Override
    public List<DiscoveredServer> discover(int udpPort) throws IOException {
        ClientController.requireOffEdt();
        DiscoveryClient search;
        // Khóa bảo vệ lượt tìm hiện tại và trạng thái đóng khi worker và luồng điều khiển cùng truy cập.
        synchronized (searchLock) {
            if (closed) {
                throw new SocketException("Desktop discovery is closed");
            }
            if (activeSearch != null) {
                throw new IllegalStateException("Discovery already active");
            }
            search = clientFactory.get();
            activeSearch = search;
        }
        // Nhả khóa trước khi chờ UDP để luồng điều khiển còn gọi được cancel().
        try (search) {
            return search.discover(targetsForPort.forPort(udpPort));
        } finally {
            synchronized (searchLock) {
                if (activeSearch == search) {
                    activeSearch = null;
                }
            }
        }
    }

    @Override
    public void cancel() {
        // Đóng các socket của lượt tìm UDP đang chạy để thoát khỏi thao tác nhận gói tin.
        synchronized (searchLock) {
            if (activeSearch != null) {
                activeSearch.close();
            }
        }
    }

    @Override
    public void close() {
        synchronized (searchLock) {
            closed = true;
            cancel();
        }
    }
}
