import java.nio.ByteBuffer;
import java.util.Arrays;

public class Test {
    enum State {
        A,B
    }
    public static void main(String[] args) {
        State a = State.A;
        State b = State.A;

        System.out.println(a == b);
    }
}
