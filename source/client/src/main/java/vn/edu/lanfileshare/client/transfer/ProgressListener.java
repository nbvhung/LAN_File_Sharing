package vn.edu.lanfileshare.client.transfer;

/** Called synchronously from uploadFile; must not reenter the connection or block waiting for it. */
@FunctionalInterface
public interface ProgressListener { void onProgress(UploadProgress progress); }
