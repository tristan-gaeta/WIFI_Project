package wifi;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Receiver implements Runnable {
    private final LinkLayer ll;
    private final BlockingQueue<Packet> queue;
    private final SequenceTable seqNums;

    public Receiver(LinkLayer ll) {
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(4);
        this.seqNums = new SequenceTable();
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
            long txEndTime = this.ll.nextBlockTime(); // record time transmission ends
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

                            // send ACK
                            Packet ack = new Packet(Packet.ACK, seqNum, sourceAddr, this.ll.macAddr);
                            this.ll.log("Sending ACK: " + ack, LinkLayer.DEBUG);
                            try {
                                this.ll.waitUntil(txEndTime + this.ll.rf.aSIFSTime);
                                this.ll.rf.transmit(ack.asBytes());
                            } catch (InterruptedException e) {
                                this.ll.log("Receiver interrupted while waiting SIFS", LinkLayer.ERROR);
                            }

                            // queue data
                            short expected = this.seqNums.currentSeqNum(sourceAddr);

                            if (seqNum >= this.seqNums.currentSeqNum(sourceAddr)) {
                                if (seqNum > expected) {
                                    this.ll.log("MAC " + sourceAddr + " used a larger sequence number than expected",  LinkLayer.ERROR);
                                }
                                if (this.queue.remainingCapacity() > 0){
                                    this.queue.offer(pkt);
                                    this.seqNums.increment(sourceAddr);
                                } else {
                                    this.ll.log("Dropping incoming packet because queue is full", LinkLayer.ERROR);
                                }
                            } else {
                                this.ll.log("Not queueing incoming data with wrong sequence number",  LinkLayer.DEBUG);
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
                        this.ll.log("Incoming packet has invalid frame type", LinkLayer.ERROR);
                        break;
                    }
                }
            } else {
                this.ll.log("Incoming packet had invalid CRC.", LinkLayer.DEBUG);
            }
        }
    }
}
