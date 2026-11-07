package vn.edu.lanfileshare.client.network;

import java.io.*;
import java.net.SocketException;

public final class TransferFailures {
    private TransferFailures() {
    }

    public static boolean interrupted(Throwable failure) {
        for (int i = 0; failure != null && i < 16; i++, failure = failure.getCause())
            if (failure instanceof EOFException || failure instanceof SocketException
                    || failure instanceof InterruptedIOException)
                return true;
        return false;
    }
}
