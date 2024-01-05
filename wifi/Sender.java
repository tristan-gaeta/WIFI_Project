package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import rf.RF;

/**
 * This class contains methods for the sending thread of the {@link LinkLayer}. 
 * @author Tristan Gaeta
 */
public class Sender implements Runnable {
    /**
     * This is used to keep track of the current state of execution in the 802.11~
     * protocol. See the state diagram provided in the documentation directory.
     */
    private enum State {
        AWAITING_DATA,
        AWAITING_IDLE,
        AWAITING_SLOT,
        AWAITING_ACK
    }

    // Final fields
    public static final int BUFFER_CAPACITY = 4;

    /** Inter-frame space used for data transmissions */
    private static final int DIFS = RF.aSIFSTime + 2 * RF.aSlotTime;
    /** The link layer running this thread */
    private final LinkLayer ll;
    /** The queue of data packets we have to send */
    private final BlockingQueue<Packet> queue;
    private final HashMap<Short, Short> seqNums;

    // Instance variables
    private State state;
    private Packet curPkt;
    private int collisionWindow;
    private int retries;
    private int slotWaitCount;
    private boolean cautious;
    private boolean acknowledged;
    private long prevBeaconTime;

    // timing
    private long beaconTimer;
    private long ackTimer;

    public Sender(LinkLayer ll) {
        this.ll = ll;
        // we are using a linked blocking queue here because it uses 
        // different locks for insertion and removal.
        this.queue = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
        this.seqNums = new HashMap<>();
        this.state = State.AWAITING_DATA;
        this.collisionWindow = RF.aCWmin;
    }

    /**
     * This method will run indefinitely. It will wait for data to be enqueued (using the 
     * {@code enqueue()} method) then follow the 802.11~ protocol to send the data over the
     * RF layer of the parent {@link LinkLayer}.
     */
    @Override
    public void run() {
        while (!Thread.interrupted()) {
            // transition has occurred
            this.ll.log("Entered state: " + this.state, LinkLayer.STATE);
            try {
                switch (this.state) {
                    case AWAITING_DATA: {
                        // get next packet to send (data or beacon)
                        this.awaitData();

                        /* transition */
                        if (this.ll.rf.inUse()) { // medium busy
                            // transition to idle wait
                            this.cautious = true;
                            this.slotWaitCount = this.pickSlotWait();
                            this.state = State.AWAITING_IDLE;
                        } else { // medium idle
                            // transition to Slot wait
                            this.ll.waitUntil(this.ll.nextBoundary() + DIFS);
                            this.cautious = false;
                            this.slotWaitCount = 0;
                            this.state = State.AWAITING_SLOT;
                        }
                        break;
                    }
                    case AWAITING_ACK: {
                        this.awaitAck();

                        /* transition */
                        if (this.acknowledged || this.retries == RF.dot11RetryLimit) {
                            if (this.retries == RF.dot11RetryLimit) {
                                this.ll.log("Dropping packet after max retries: " + this.curPkt, LinkLayer.ERROR);
                                this.ll.status = LinkLayer.TX_FAILED;
                            } else {
                                this.ll.status = LinkLayer.TX_DELIVERED;
                            }
                            // transition to data wait
                            this.collisionWindow = RF.aCWmin;
                            this.retries = 0;
                            this.state = State.AWAITING_DATA;
                        } else { // Timeout occurred
                            this.ll.status = LinkLayer.TX_FAILED;
                            // transition to idle wait
                            if (this.retries++ == 0) {
                                this.curPkt.flagAsResend();
                            }
                            this.collisionWindow = Math.min(2 * this.collisionWindow, RF.aCWmax); // backoff
                            this.ll.log("Increased collision window to: " + this.collisionWindow, LinkLayer.DEBUG);
                            this.slotWaitCount = this.pickSlotWait();
                            this.state = State.AWAITING_IDLE;
                        }
                        break;
                    }
                    case AWAITING_IDLE: {
                        while (this.ll.rf.inUse()) {
                            Thread.sleep(LinkLayer.BOUNDARY_SIZE);
                        }
                        // aligned boundary wait
                        this.ll.waitUntil(this.ll.nextBoundary() + DIFS);
                        this.state = State.AWAITING_SLOT;
                        break;
                    }
                    case AWAITING_SLOT: {
                        /* transition */
                        if (this.ll.rf.inUse()) {
                            if (!this.cautious) {
                                this.cautious = true;
                                this.slotWaitCount = this.pickSlotWait();
                            }
                            this.state = State.AWAITING_IDLE;
                        } else {
                            if (this.slotWaitCount == 0) {
                                // clear to send
                                this.ll.log("Transmitting packet: " + this.curPkt, LinkLayer.DEBUG);
                                this.ll.rf.transmit(this.curPkt.asBytes());

                                if (this.ll.timing) {
                                    long curTime = System.currentTimeMillis();
                                    this.ackTimer = curTime; // start ack timer
                                    if (this.beaconTimer != -1) {
                                        // time between when we noticed we need to send a beacon and the end of tx
                                        this.ll.log("Beacon time: " + (curTime - this.beaconTimer), LinkLayer.TIMING);
                                    }
                                }

                                /* transition */
                                if (this.curPkt.getDest() == -1) {
                                    // don't expect ack on broadcast
                                    this.state = State.AWAITING_DATA;
                                } else {
                                    this.state = State.AWAITING_ACK;
                                }
                            } else {
                                // perform 1 slot wait
                                this.ll.waitUntil(this.ll.nextBoundary() + RF.aSlotTime);
                                if (!this.ll.rf.inUse()) {
                                    this.slotWaitCount--;
                                }
                            }
                        }
                        break;
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

    /**
     * Wait for the next data or beacon packet to send
     */
    public void awaitData() {
        this.curPkt = null;
        long timeToNextBeacon = this.ll.beaconFrequency - (this.ll.time() - this.prevBeaconTime);

        // block for incoming data on the queue if we have time
        if (timeToNextBeacon > 0) {
            try {
                this.curPkt = this.queue.poll(timeToNextBeacon, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                this.ll.log("Sender interrupted while blocking for incoming data.", LinkLayer.ERROR);
            }
        }
        // record time if we're sending beacon
        if (this.ll.timing) {
            this.beaconTimer = (this.curPkt == null) ? System.currentTimeMillis() : -1;
        }
        if (this.curPkt == null) {
            // time to send beacon
            long localTime = this.ll.time();
            this.prevBeaconTime = localTime;
            this.curPkt = new Packet(this.ll.macAddr, localTime + LinkLayer.BEACON_DELIVERY_TIME);
        }
    }

    /**
     * Wait for a valid ack or timeout
     */
    public void awaitAck() {
        this.acknowledged = false;
        try {
            // receiving thread will wake us if ack arrives
            synchronized (this) {
                this.wait(LinkLayer.ACK_TIMEOUT);
            }
        } catch (InterruptedException e) {
            this.ll.log("Sender interrupted while waiting for ack", LinkLayer.ERROR);
        }

        if (this.acknowledged && this.ll.timing) {
            // Time between end of tx and sender wake
            this.ll.log("Ack time: " + (System.currentTimeMillis() - this.ackTimer), LinkLayer.TIMING);
        }
    }

    /**
     * The number of slots to wait before sending determined from collision window.
     * 
     * @return slots
     */
    public int pickSlotWait() {
        return this.ll.randomWait ? (int) (Math.random() * this.collisionWindow) : this.collisionWindow;
    }

    /**
     * Create a packet and put it on the outgoing queue if there is room
     * 
     * 
     * @param dest  MAC address
     * @param data
     * @param bytesToSend
     * @return true if accepted else false
     */
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
     * Alerts the sender thread that a valid ack arrived
     * 
     * @param seqNum ACK's sequence number
     * @param src    MAC address of source
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
