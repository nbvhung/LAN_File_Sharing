package vn.edu.lanfileshare.client.transfer;

/** Bytes from fully written/read chunk frames in this attempt, not local hashing or framing. */
public record ResumeResult<T>(T result, long resumedFromOffset, long payloadBytesThisAttempt) {
}
