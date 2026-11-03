package vn.edu.lanfileshare.client;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import vn.edu.lanfileshare.client.network.*;
class ReconnectPolicyTest {
    @Test void connectionRefusalStopsAfterFiniteBackoff() throws Exception {
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        List<Long> delays=new ArrayList<>();
        try(TcpClient client=new TcpClient(500)) {
            assertThrows(IOException.class,() -> client.connect("127.0.0.1",port));
            var policy=new ReconnectPolicy(3,Duration.ofMillis(10),Duration.ofMillis(40),d -> delays.add(d.toMillis()));
            assertThrows(IOException.class,() -> client.reconnect(policy));assertEquals(List.of(10L,20L),delays);
            assertEquals(ClientState.FAILED,client.state());assertFalse(client.isConnected());
        }
    }
    @Test void reconnectSucceedsWhenTheActualListenerBecomesAvailable() throws Exception {
        int port;try(ServerSocket reserve=new ServerSocket(0)){port=reserve.getLocalPort();}
        ServerSocket[] opened={null};
        try(TcpClient client=new TcpClient(500)) {
            assertThrows(IOException.class,() -> client.connect("127.0.0.1",port));
            var policy=new ReconnectPolicy(3,Duration.ofMillis(10),Duration.ofMillis(20),d -> {
                try{opened[0]=new ServerSocket(port);}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}
            });
            client.reconnect(policy);assertEquals(ClientState.CONNECTED,client.state());
            try(Socket accepted=opened[0].accept()){assertTrue(client.isConnected());}
        } finally {if(opened[0]!=null)opened[0].close();}
    }
    @Test void invalidOrUnboundedPolicyIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> new ReconnectPolicy(0,Duration.ZERO,Duration.ZERO,d -> {}));
        assertThrows(IllegalArgumentException.class,() -> new ReconnectPolicy(3,Duration.ofSeconds(2),Duration.ofSeconds(1),d -> {}));
    }
}
