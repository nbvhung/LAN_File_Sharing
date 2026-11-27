package vn.edu.lanfileshare.client.ui;

import java.util.List;
import vn.edu.lanfileshare.client.discovery.DiscoveredServer;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;

/** Trạng thái tại thời điểm controller yêu cầu view vẽ lại. */
public record UiSnapshot(UiState state, boolean connected, boolean busy, boolean closing, Endpoint endpoint,
        String message, List<FileInfo> files, List<TransferItem> transfers,
        List<DiscoveredServer> discoveredServers, DiscoveredServer selectedServer) {
    public UiSnapshot(UiState state, boolean connected, boolean busy, boolean closing, Endpoint endpoint,
            String message, List<FileInfo> files, List<TransferItem> transfers) {
        this(state, connected, busy, closing, endpoint, message, files, transfers, List.of(), null);
    }

    public UiSnapshot {
        // Sao chép để lần cập nhật tiếp theo của controller không làm đổi snapshot đã gửi.
        files = List.copyOf(files);
        transfers = List.copyOf(transfers);
        discoveredServers = List.copyOf(discoveredServers);
    }
}
