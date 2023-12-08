package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import rf.RF;

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

    @Override
    public void run() {
        while (!Thread.interrupted()) {
            byte[] data = this.ll.rf.receive(); // block until data arrives
            long txEndTime = this.ll.nextBoundary(); // record time transmission ends
            Packet pkt = new Packet(data);

            // Perform checksum
            if (pkt.isValid()) {

                int frameType = pkt.getFrameType();
                this.ll.log("Received packet: " + pkt, LinkLayer.DEBUG);

                switch (frameType) {
                    case Packet.DATA: {
                        if (pkt.getDest() == this.ll.macAddr) {
                            short sourceAddr = pkt.getSource();
                            short seqNum = pkt.getSeqNum();

                            this.sendAck(pkt, txEndTime);

                            // queue data
                            this.seqNums.putIfAbsent(sourceAddr, (short) 0);
                            short expected = this.seqNums.get(sourceAddr);

                            if (seqNum >= expected) {
                                if (seqNum > expected) {
                                    this.ll.log("MAC " + sourceAddr + " used a larger sequence number than expected", LinkLayer.DEBUG);
                                }
                                if (!this.queue.offer(pkt)) {
                                    this.ll.log("Dropping incoming packet because queue is full", LinkLayer.ERROR);
                                }
                                this.seqNums.put(sourceAddr, (short) ((seqNum + 1) & 0xFFF));
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

                    // TODO other cases

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

    private void sendAck(Packet pkt, long txEndTime) {
        short sourceAddr = pkt.getSource();
        short seqNum = pkt.getSeqNum();
        // send ACK
        Packet ack = new Packet(Packet.ACK, seqNum, sourceAddr, this.ll.macAddr, null, 0);
        this.ll.log("Sending ACK: " + ack, LinkLayer.DEBUG);
        try {
            this.ll.waitUntil(txEndTime + RF.aSIFSTime);
        } catch (InterruptedException e) {
            this.ll.log("Receiver interrupted while waiting SIFS", LinkLayer.ERROR);
        }
    }
}
