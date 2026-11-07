package vn.edu.lanfileshare.client.ui;

/** Remembered with each transfer: never resume against a different server by accident. */
public record Endpoint(String host, int port) {
    public Endpoint {
        host = host == null ? "" : host.strip();
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Enter a server IP or hostname without spaces.");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("TCP port must be between 1 and 65535.");
    }

    public static Endpoint parse(String host, String port) {
        try {
            return new Endpoint(host, Integer.parseInt(port.strip()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("TCP port must be a number between 1 and 65535.");
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
