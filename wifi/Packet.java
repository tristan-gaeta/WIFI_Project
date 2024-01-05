package wifi;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import rf.RF;

/** 
 * This class represents a data packet that will be transmitted over 
 * the RF layer. It provides methods to access data and metadata fields
 * and calculate checksums. See the packet structure specified in the 
 * documentation directory.
 */
public class Packet {
    public static final int DATA = 0, ACK = 1, BEACON = 2;

    public static final int MIN_PACKET_SIZE = 10;
    public static final int MAX_DATA_SIZE = RF.aMPDUMaximumLength - MIN_PACKET_SIZE;

    private final CRC32 checksum = new CRC32();
    private final ByteBuffer buf;

    /**
     * Create a data packed given the specified fields. An exception
     * will be thrown of {@code len} is greater than the length of 
     * the {@code data} array
     * 
     * @param type      the type of packet
     * @param seq       The sequence number of this packet
     * @param dest      The destination MAC address
     * @param source    The source MAC address
     * @param data      The array from which the data portion of this packet will be copied
     * @param len       The number of bytes to copy from the given array
     */
    public Packet(int type, int seq, short dest, short source, byte[] data, int len) throws IndexOutOfBoundsException{
        short control = (short) (type << 13); // last 3 bits of type at head
        control |= seq & 0xFFF; // last 12 bits of seq at tail

        this.buf = ByteBuffer.allocate(MIN_PACKET_SIZE + len);
        this.buf.putShort(control).putShort(dest).putShort(source);
        if (data != null) {
            this.buf.put(data, 0, len);
        }
        this.buf.putInt(this.checkSum());
    }

    /**
     * This method will create a packet from the given byte array. It will
     * not copy the data, but rather use the array reference.
     * @param array
     */
    public Packet(byte[] array) {
        this.buf = ByteBuffer.wrap(array);
    }

    /**
     * This constructor is used specifically for beacon packets.
     * @param source the source MAC address
     * @param time 
     */
    public Packet(short source, long time) {
        short control = (short) (BEACON << 13);
        this.buf = ByteBuffer.allocate(MIN_PACKET_SIZE + 8);
        this.buf.putShort(control).putShort((short) -1).putShort(source).putLong(time).putInt(this.checkSum());
    }

    /**
     * returns a reference to the array used to back this packet
     */
    public byte[] asBytes() {
        return this.buf.array();
    }

    /**
     * Copies the data portion of this packet into a new array and
     * returns a reference to the newly allocated array.
     */
    public byte[] extractData() {
        // make a new array and fill it with just the data segment of the packet
        return Arrays.copyOfRange(this.buf.array(), 6, this.size() - 4);
    }

    /**
     * Gets the destination MAC address of this packet
     */
    public short getDest() {
        return this.buf.getShort(2);
    }

    /**
     * Gets the source MAC address of this packet
     */
    public short getSource() {
        return this.buf.getShort(4);
    }

    /**
     * Gets the type of this packet.
     */
    public int getFrameType() {
        byte control = this.buf.get(0);
        return control >>> 5;
    }

    /**
     * Returns true iff this packet has been flagged as a resend
     */
    public boolean isResend() {
        byte control = this.buf.get(0);
        return (control & 0x10) != 0;
    }

    /**
     * Gets the sequence number of this packet
     */
    public short getSeqNum() {
        short control = this.buf.getShort(0);
        return (short) (control & 0xFFF);
    }

    /**
     * Calculates the CRC checksum for this packet, and writes
     * it to the end of the packet
     */
    public int checkSum() {
        this.checksum.reset();
        this.checksum.update(this.buf.array(), 0, this.size() - 4);
        return (int) this.checksum.getValue();
    }

    /**
     * Gets the checksum from this packet
     */
    public int getCrc() {
        return this.buf.getInt(this.size() - 4);
    }

    /**
     * Returns true iff the packet is a valid size and passes the checksum
     */
    public boolean isValid() {
        int size = this.size();
        if (size < MIN_PACKET_SIZE || size > RF.aMPDUMaximumLength) {
            return false;
        }
        return this.getCrc() == this.checkSum();
    }

    /**
     * Mark this packet as a resend
     */
    public void flagAsResend() {
        byte control = this.buf.get(0);
        control |= 0x10;
        this.buf.put(0, control);
        this.buf.putInt(this.size() - 4, this.checkSum());
    }

    /**
     * This method is specific to beacon packets. It will
     * get the time listed in the data portion
     */
    public long getTime(){
        return this.buf.getLong(6);
    }

    /**
     * Returns how many bytes this packet is
     */
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
            case BEACON:
                str += "BEACON";
                break;
            default:
                return str + "INVALID]";
        }
        str += "#" + this.getSeqNum() + " ";
        str += this.getSource() + "->" + this.getDest();
        int size = this.size();
        if (size > MIN_PACKET_SIZE) {
            str += " ";
            for (int i = 6; i < size - 4; i++) {
                str += (char) this.buf.get(i);
            }
        }
        return str + "]";
    }
}
