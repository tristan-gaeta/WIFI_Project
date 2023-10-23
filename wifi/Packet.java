package wifi;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Packet {
    private static final int MAX_NUM_BYTES = 2038;
    private static final int MIN_PACKET_SIZE = 10;
    private static final int MAX_PACKET_SIZE = MIN_PACKET_SIZE + MAX_NUM_BYTES;

    private short control;
    public final short dest;
    public final short source;
    public final byte[] data;
    private final int crc;

    public Packet(short control, short dest, short source, byte[] data, int len, int offset) {
        this.control = control;
        this.dest = dest;
        this.source = source;
        this.data = Arrays.copyOfRange(data, offset, offset + len);
        this.crc = -1; //TODO
    }

    public static Packet fromBytes(byte[] packet) {
        if (packet.length > MAX_PACKET_SIZE)
            return null;
        if (packet.length < MIN_PACKET_SIZE)
            return null;

        ByteBuffer bb = ByteBuffer.wrap(packet);
        short control = bb.getShort();
        short dest = bb.getShort();
        short source = bb.getShort();
        int crc = bb.getInt(bb.limit() - 4);

        Packet p = new Packet(control, dest, source, packet, packet.length - 10, 6);
        if (p.checkSum(crc))
            return p;
        else
            return null;
    }

    public byte[] asBytes() {
        ByteBuffer bb = ByteBuffer.allocate(MIN_PACKET_SIZE + this.data.length);
        bb.putShort(this.control).putShort(this.dest).putShort(this.source);
        bb.put(this.data, 0, this.data.length);
        bb.putInt(this.crc);
        return bb.array();
    }

    public int getFrameType() {
        return this.control >>> 13;
    }

    // public void setFrameType(int frameType) {
    // this.control = (short) ((frameType << 13) | (this.control & 0x1FFF));
    // }

    public boolean isRetry() {
        return ((this.control >>> 12) & 1) == 1;
    }

    public int sequenceNum() {
        return this.control % 0xFFF;
    }

    //TODO
    public boolean checkSum(int crc){
        return true;
    }

}
