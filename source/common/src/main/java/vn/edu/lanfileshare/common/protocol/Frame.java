package vn.edu.lanfileshare.common.protocol;

import java.util.Arrays;
import java.util.Objects;

public record Frame(MessageType type, byte[] payload) {
    public Frame {
        Objects.requireNonNull(type, "type");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
