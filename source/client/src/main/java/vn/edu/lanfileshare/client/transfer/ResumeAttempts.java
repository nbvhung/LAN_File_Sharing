package vn.edu.lanfileshare.client.transfer;

import java.io.IOException;
import vn.edu.lanfileshare.client.network.*;
import vn.edu.lanfileshare.common.error.ErrorCode;

/** A busy old lease may still be draining. Retry only that controlled race, with a finite policy. */
final class ResumeAttempts {
    @FunctionalInterface
    interface Attempt<T> {
        T run() throws IOException;
    }

    static <T> T run(TcpClient client, ReconnectPolicy policy, Attempt<T> attempt) throws IOException {
        for (int index = 1;; index++) {
            if (!client.isConnected())
                client.reconnect(policy);
            try {
                return attempt.run();
            } catch (ServerResponseException ex) {
                if (ex.code() != ErrorCode.SERVER_BUSY || index >= policy.maxAttempts())
                    throw ex;
                policy.backoff(index);
            }
        }
    }
}
