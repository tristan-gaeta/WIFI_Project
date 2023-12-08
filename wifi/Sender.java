package wifi;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import rf.RF;

/**
 * Sending thread
 */
public class Sender implements Runnable {
    private enum State {
        AWAITING_DATA,
        AWAITING_IDLE,
        AWAITING_SLOT,
        AWAITING_ACK
    }

    private static final int DIFS = RF.aSIFSTime + 2 * RF.aSlotTime;
    public static final int BUFFER_CAPACITY = 4;

    /** The link layer running this thread */
    private final LinkLayer ll;
    /* The queue of data packets we have to send */
    private final BlockingQueue<Packet> queue;
    private final Random rand; // ued for random wait time
    private final HashMap<Short, Short> seqNums;

    private State state;
    private Packet curPkt;
    private int collisionWindow;
    private int retries;
    private int slotWaitCount;
    private boolean cautious;
    private boolean acknowledged;
    
    private long prevBeaconTime;
    private long beaconFrequency;

    public Sender(LinkLayer ll) {
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
        this.rand = new Random();
        this.seqNums = new HashMap<>();
        this.state = State.AWAITING_DATA;
        this.collisionWindow = RF.aCWmin;
    }

    @Override
    public void run() {
        transition: while (!Thread.interrupted()) {
            // transition has occurred
            this.ll.log("Entered state: " + this.state, LinkLayer.STATE);
            try {
                switch (this.state) {
                    case AWAITING_DATA: {
                        // block until we need to do stuff
                        this.curPkt = this.queue.poll(beaconFrequency, TimeUnit.MILLISECONDS);
                        long curTime = this.ll.time();
                        if (curTime - this.prevBeaconTime >= this.beaconFrequency){
                            this.curPkt = new Packet(this.ll.macAddr, curTime);
                            this.prevBeaconTime = curTime;
                        } 

                        if (this.ll.rf.inUse()) { // medium busy
                            // transition to idle wait
                            this.cautious = true;
                            this.slotWaitCount = this.rand.nextInt(this.collisionWindow);
                            this.state = State.AWAITING_IDLE;
                        } else { // medium idle
                            // transition to Slot wait
                            this.ll.waitUntil(this.ll.nextBoundary() + DIFS);
                            this.cautious = false;
                            this.slotWaitCount = 0;
                            this.state = State.AWAITING_SLOT;
                        }
                        continue transition;
                    }
                    case AWAITING_ACK: {
                        boolean acknowledged;
                        synchronized (this) {
                            this.wait(50 * DIFS); // TODO how long is timeout? had to be bigger than difs
                            acknowledged = this.acknowledged;
                            this.acknowledged = false;
                        }
                        if (acknowledged || this.retries == RF.dot11RetryLimit) {
                            if (this.retries == RF.dot11RetryLimit) {
                                this.ll.log("Dropping packet after max retries: " + this.curPkt, LinkLayer.ERROR);
                            }
                            // transition to data wait
                            this.collisionWindow = RF.aCWmin;
                            this.retries = 0;
                            this.state = State.AWAITING_DATA;
                        } else {
                            // transition to idle wait
                            if (this.retries++ == 0) {
                                this.curPkt.flagAsResend();
                            }
                            this.collisionWindow = Math.min(2 * this.collisionWindow, RF.aCWmax); // backoff
                            this.slotWaitCount = this.rand.nextInt(this.collisionWindow);
                            this.state = State.AWAITING_IDLE;
                        }
                        continue transition;
                    }
                    case AWAITING_IDLE: {
                        while (this.ll.rf.inUse()) {
                            Thread.onSpinWait(); 
                        }
                        // aligned boundary wait
                        this.ll.waitUntil(this.ll.nextBoundary() + DIFS);
                        this.state = State.AWAITING_SLOT;
                        continue transition;
                    }
                    case AWAITING_SLOT: {
                        if (this.ll.rf.inUse()) {
                            if (!this.cautious) {
                                this.cautious = true;
                                this.slotWaitCount = this.rand.nextInt(this.collisionWindow);
                            }
                            this.state = State.AWAITING_IDLE;
                        } else {
                            if (this.slotWaitCount == 0) { // clear to send
                                this.ll.log("Transmitting packet", LinkLayer.DEBUG);
                                this.ll.rf.transmit(this.curPkt.asBytes());
                                if (this.curPkt.getDest() == -1) { // don't expect ack on broadcast
                                    this.state = State.AWAITING_DATA;
                                } else {
                                    this.state = State.AWAITING_ACK;
                                }
                            } else {
                                this.ll.waitUntil(this.ll.nextBoundary() + RF.aSlotTime);
                                if (!this.ll.rf.inUse()) {
                                    this.slotWaitCount--;
                                }
                            }
                        }

                        continue transition;
                    }
                    default: {
                        this.ll.log("Entered an invalid state. Terminating sending thread.", LinkLayer.ERROR);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                this.ll.log("interrupted while waiting in " + this.state + " state.", LinkLayer.ERROR);
            }
        }
    }




    public boolean enqueue(short dest, byte[] data, int bytesToSend) {
        // get sequence number
        this.seqNums.putIfAbsent(dest, (short) 0);
        short seqNum = this.seqNums.get(dest);
        this.seqNums.put(dest, (short) ((seqNum + 1) & 0xFFF));

        // make packet
        Packet pkt = new Packet(Packet.DATA, seqNum, dest, this.ll.macAddr, data, bytesToSend);
        boolean accepted = this.queue.offer(pkt);
        if (accepted) {
            this.ll.log("Queueing packet: " + pkt, LinkLayer.DEBUG);
        } else {
            this.ll.log("Outgoing packet was rejected because queue is full.", LinkLayer.ERROR);
        }
        return accepted;
    }

    /**
     * Attempt to
     */
    public void acknowledgePacket(int seqNum, short src) {
        if (this.state == State.AWAITING_ACK) {
            if (seqNum != this.curPkt.getSeqNum()) {
                this.ll.log("Ignoring ACK with wrong sequence number", LinkLayer.DEBUG);
            } else if (src != this.curPkt.getDest()) {
                this.ll.log("Ignoring ACK with wrong source address", LinkLayer.DEBUG);
            } else {
                synchronized (this) {
                    this.acknowledged = true;
                    this.notify();
                }
                this.ll.log("Acknowledged", LinkLayer.DEBUG);
            }
        } else {
            this.ll.log("ACK arrived in state: " + this.state, LinkLayer.DEBUG);
        }
    }
}
