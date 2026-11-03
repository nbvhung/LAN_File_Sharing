package vn.edu.lanfileshare.client.ui;
import java.util.List;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
public record UiSnapshot(UiState state, boolean connected, boolean busy, boolean closing,
        Endpoint endpoint, String message, List<FileInfo> files, List<TransferItem> transfers) {
    public UiSnapshot { files = List.copyOf(files); transfers = List.copyOf(transfers); }
}
