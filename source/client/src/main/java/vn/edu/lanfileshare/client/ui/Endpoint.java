package vn.edu.lanfileshare.client.ui;

/** Địa chỉ TCP của server, được lưu cùng lượt truyền để kiểm tra khi Resume. */
public record Endpoint(String host, int port) {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    public Endpoint {
        host = host == null ? "" : host.strip();
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Enter a server IP or hostname without spaces.");
        }
        requireValidPort(port, "TCP");
    }

    public static Endpoint parse(String host, String port) {
        try {
            return new Endpoint(host, Integer.parseInt(port.strip()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("TCP port must be a number between 1 and 65535.");
        }
    }

    static int requireValidPort(int port, String transport) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(transport + " port must be between " + MIN_PORT + " and " + MAX_PORT + ".");
        }
        return port;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
