package wifi;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Sender {
    public static State state;
    public static final Queue<Packet> queue = new ConcurrentLinkedQueue<>();

    private static void send() {
        // Wait until we need to do stuff
        synchronized (state) {
            if (queue.isEmpty()) {
                state = State.IDLE;
                try {
                    state.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }
}
