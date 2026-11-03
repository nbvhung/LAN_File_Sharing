package vn.edu.lanfileshare.server.storage;
import java.io.IOException;
import java.util.List;
import vn.edu.lanfileshare.common.model.Messages.FileInfo;
/** TV2 implements this contract. Return completed, immutable file metadata only. */
@FunctionalInterface
public interface FileCatalog { List<FileInfo> listFiles() throws IOException; }
