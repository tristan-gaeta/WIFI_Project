package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import rf.RF;

/**
 * This class implements methods for the receiving thread of the {@link LinkLayer}.
 * @author Tristan Gaeta
 */
public class Receiver implements Runnable {
    public static final int BUFFER_CAPACITY = 4;

    private final LinkLayer ll;
    private final BlockingQueue<Packet> queue;
    private final HashMap<Short, Short> seqNums;

    public Receiver(LinkLayer ll) {
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
        this.seqNums = new HashMap<>();
    }

    public Packet nextPacket() {
        try {
            return this.queue.take();
        } catch (InterruptedException e) {
            this.ll.log("Interrupted while blocking for incoming data.", LinkLayer.ERROR);
            return null;
        }
    }

    /**
     * This method will run indefinitely. It will wait for data incoming data,
     * queueing it to be passed to the layer above only if it has a valid 
     * checksum and sequence number.
     */
    @Override
    public void run() {
        long beaconUnpackTime;
        while (!Thread.interrupted()) {
            byte[] data = this.ll.rf.receive(); // block until data arrives
            beaconUnpackTime = System.currentTimeMillis();
            long txEndTime = this.ll.nextBoundary(); // record time transmission ends
            Packet pkt = new Packet(data);

            // Perform checksum
            if (pkt.isValid()) {

                int frameType = pkt.getFrameType();
                this.ll.log("Received packet: " + pkt, LinkLayer.DEBUG);

                switch (frameType) {
                    case Packet.DATA: {
                        short dest = pkt.getDest();
                        short source = pkt.getSource();
                        short seqNum = pkt.getSeqNum();
                        if (dest == this.ll.macAddr || dest == -1) {
                            this.sendAck(source, dest, seqNum, txEndTime);

                            // queue data
                            this.seqNums.putIfAbsent(source, (short) 0);
                            short expected = this.seqNums.get(source);

                            if (seqNum >= expected) {
                                if (seqNum > expected) {
                                    this.ll.log("MAC " + source + " used a larger sequence number than expected", LinkLayer.DEBUG);
                                }
                                if (!this.queue.offer(pkt)) {
                                    this.ll.log("Dropping incoming packet because queue is full", LinkLayer.ERROR);
                                }
                                this.seqNums.put(source, (short) ((seqNum + 1) & 0xFFF));
                            } else {
                                this.ll.log("Dropping incoming data with wrong sequence number", LinkLayer.DEBUG);
                            }
                        }
                        break;
                    }

                    case Packet.ACK: {
                        int seqNum = pkt.getSeqNum();
                        short src = pkt.getSource();
                        this.ll.sender.acknowledgePacket(seqNum, src);
                        break;
                    }

                    case Packet.BEACON: {
                        long suggestedTime = pkt.getTime() + LinkLayer.BEACON_UNPACK_TIME;
                        long curTime = this.ll.time();
                        if (this.ll.timing) {
                            this.ll.log("Beacon unpack time: " + (System.currentTimeMillis() - beaconUnpackTime), LinkLayer.TIMING);
                        }
                        if (suggestedTime > curTime) {
                            this.ll.log("Increasing timer offset", LinkLayer.TIMING);
                            this.ll.clock_offset += suggestedTime - curTime;
                        }
                        break;
                    }

                    // TODO rts/cts

                    default: {
                        this.ll.log("Incoming packet has invalid frame type", LinkLayer.DEBUG);
                        break;
                    }
                }
            } else {
                this.ll.log("Incoming packet had invalid CRC", LinkLayer.DEBUG);
            }
        }
    }

    /**
     * Create and transmit an ACK packet to the given
     * 
     * @param pkt
     * @param txEndTime
     */
    private void sendAck(short source, short dest, short seqNum, long txEndTime) {
        if (dest == this.ll.macAddr) {
            // send ACK
            Packet ack = new Packet(Packet.ACK, seqNum, source, this.ll.macAddr, null, 0);
            this.ll.log("Sending ACK: " + ack, LinkLayer.DEBUG);
            try {
                this.ll.waitUntil(txEndTime + RF.aSIFSTime);
                this.ll.rf.transmit(ack.asBytes());
            } catch (InterruptedException e) {
                this.ll.log("Receiver interrupted while waiting SIFS", LinkLayer.ERROR);
            }
        }
    }
}
