package vn.edu.lanfileshare.client.transfer;

/** Called on the transfer thread. Do not reenter or wait for the same connection from callbacks. */
@FunctionalInterface
public interface ProgressListener<P extends TransferProgress> {
    void onProgress(P progress);
}
