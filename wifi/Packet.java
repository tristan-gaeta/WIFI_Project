package wifi;

import java.nio.ByteBuffer;
import java.util.Arrays;

import rf.RF;

public class Packet {
    public static final int DATA = 0;
    public static final int ACK = 1;

    public static final int MIN_PACKET_SIZE = 10;
    public static final int MAX_DATA_SIZE = RF.aMPDUMaximumLength - MIN_PACKET_SIZE;

    private final ByteBuffer buf;

    public Packet(int type, int seq, short dest, short source) {
        short control = (short) (type << 13); // last 3 bits of type at head of short
        control |= seq & 0xFFF; // last 12 bits of seq at tail

        this.buf = ByteBuffer.allocate(MIN_PACKET_SIZE);
        this.buf.putShort(control).putShort(dest).putShort(source);
        this.buf.putInt(this.checkSum());
    }

    public Packet(int type, int seq, short dest, short source, byte[] data, int len) {
        short control = (short) (type << 13); // last 3 bits of type at head
        control |= seq & 0xFFF; // last 12 bits of seq at tail

        this.buf = ByteBuffer.allocate(MIN_PACKET_SIZE + len);
        this.buf.putShort(control).putShort(dest).putShort(source);
        this.buf.put(data, 0, len);
        this.buf.putInt(this.checkSum());
    }

    public Packet(byte[] array) {
        this.buf = ByteBuffer.wrap(array);
    }

    public byte[] asBytes() {
        return this.buf.array();
    }

    public byte[] extractData() {
        // make a new array and fill it with just the data segment of the packet
        return Arrays.copyOfRange(this.buf.array(), 6, this.size() - 4);
    }

    public short getDest() {
        return this.buf.getShort(2);
    }

    public short getSource() {
        return this.buf.getShort(4);
    }

    public int getFrameType() {
        byte control = this.buf.get(0);
        return control >>> 5;
    }

    public boolean isResend() {
        byte control = this.buf.get(0);
        return (control & 0x10) != 0;
    }

    public short getSeqNum() {
        short control = this.buf.getShort(0);
        return (short) (control & 0xFFF);
    }

    public int checkSum() {
        return -1; // TODO
    }

    public int getCrc() {
        return this.buf.getInt(this.size() - 4);
    }

    public boolean isValid() {
        int size = this.size();
        if (size < MIN_PACKET_SIZE || size > RF.aMPDUMaximumLength) {
            return false;
        }
        return this.getCrc() == this.checkSum();
    }

    public void flagAsResend() {
        byte control = this.buf.get(0);
        control |= 0x10;
        this.buf.put(0, control);
        this.buf.putInt(this.size() - 4, this.checkSum());
    }

    public int size() {
        return this.buf.array().length;
    }

    @Override
    public String toString() {
        String str = "[";
        switch (this.getFrameType()) {
            case DATA:
                str += "DATA ";
                break;
            case ACK:
                str += "ACK ";
                break;
            // TODO others
            default:
                return str + "INVALID]";
        }
        str += "#" + this.getSeqNum() + " ";
        str += this.getSource() + "->" + this.getDest();
        int size = this.size();
        if (size > MIN_PACKET_SIZE) {
            str += " ";
            for (int i = 6; i < this.size() - 4; i++) {
                str += (char) this.buf.get(i);
            }
        }
        return str + "]";
    }
}
