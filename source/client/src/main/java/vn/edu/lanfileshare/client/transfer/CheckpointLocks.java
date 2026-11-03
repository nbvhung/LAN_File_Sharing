package vn.edu.lanfileshare.client.transfer;
import java.nio.file.Path;
/** Bounded locks shared by checkpoint readers/writers across service instances in this JVM. */
final class CheckpointLocks {
    private static final Object[] LOCKS=new Object[64];
    static { java.util.Arrays.setAll(LOCKS,i -> new Object()); }
    static Object forPath(Path path) { return LOCKS[Math.floorMod(path.toAbsolutePath().normalize().hashCode(),LOCKS.length)]; }
    private CheckpointLocks() {}
}
