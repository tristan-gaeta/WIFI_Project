package wifi;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import rf.RF;

/**
 * Sending thread
 */
public class Sender implements Runnable {
    private static final int DIFS = RF.aSIFSTime + 2 * RF.aSlotTime; // TODO is this right?

    /* The link layer running this thread */
    private final LinkLayer ll;
    /* The queue of data packets we have to send */
    private final BlockingQueue<Packet> queue;
    private final Random rand; // for random wait time

    private State state;
    private Packet curPkt;
    private int curCW;
    private int retries;
    private int slotWaitCount;
    private boolean cautious;
    private boolean acknowledged;

    public Sender(LinkLayer ll) {
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(); // TODO q size?
        this.rand = new Random();
        this.state = State.AWAITING_DATA;
        this.curCW = RF.aCWmin;
    }

    @Override
    public void run() {
        transition: while (!Thread.interrupted()) {
            // transition has occured
            this.ll.log("Entered state: " + this.state, LinkLayer.STATE);
            try {
                switch (this.state) {
                    case AWAITING_DATA: {
                        // Wait until we need to do stuff
                        this.curPkt = this.queue.take();

                        if (this.ll.rf.inUse()) { // medium busy
                            // transition to idle wait
                            this.cautious = true;
                            this.slotWaitCount = this.rand.nextInt(this.curCW);
                            this.state = State.AWAITING_IDLE;
                        } else { // medium idle
                            // transition to Slot wait
                            this.cautious = false;
                            this.slotWaitCount = 0;
                            Thread.sleep(DIFS);
                            this.state = State.AWAITING_SLOT;
                        }
                        continue transition;
                    }
                    case AWAITING_ACK: {
                        synchronized (this) {
                            this.wait(10 * DIFS); // TODO how long is timeout? had to be bigger than difs for this to
                                                  // work
                            if (this.acknowledged || this.retries == RF.dot11RetryLimit) {
                                if (this.retries == RF.dot11RetryLimit) {
                                    this.ll.log("Dropping packet after max retries: " + this.curPkt.toString(),
                                            LinkLayer.ERROR);
                                }
                                // transition to data wait
                                this.curCW = RF.aCWmin;
                                this.retries = 0;
                                this.state = State.AWAITING_DATA;
                            } else {
                                // transition to idle wait
                                this.curPkt.resend();
                                this.retries++;
                                this.curCW = Math.min(2 * this.curCW, RF.aCWmax); // backoff
                                this.slotWaitCount = this.rand.nextInt(this.curCW);
                                this.state = State.AWAITING_IDLE;
                            }
                        }

                        this.acknowledged = false;
                        continue transition;
                    }
                    case AWAITING_IDLE: {
                        while (this.ll.rf.inUse()) {
                            // take a lil nappy
                            Thread.sleep(0); // TODO how long?
                        }
                        Thread.sleep(DIFS);
                        this.state = State.AWAITING_SLOT;
                        continue transition;
                    }
                    case AWAITING_SLOT: {
                        if (this.ll.rf.inUse()) {
                            if (!this.cautious) {
                                this.cautious = true;
                                this.slotWaitCount = this.rand.nextInt(this.curCW);
                            }
                            this.state = State.AWAITING_IDLE;
                        } else {
                            if (this.slotWaitCount == 0) {
                                this.ll.rf.transmit(this.curPkt.asBytes()); // TODO what if this returns the wrong #?
                                this.state = State.AWAITING_ACK;
                            } else {
                                Thread.sleep(RF.aSlotTime);
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

    public boolean enqueue(Packet pkt) {
        if (this.queue.remainingCapacity() > 0) {
            return this.queue.offer(pkt);
        }
        return false;
    }

    /**
     * Attempt to
     */
    public void ack() {
        synchronized (this) {
            if (this.state == State.AWAITING_ACK) {
                this.acknowledged = true;
                this.notify();
                this.ll.log("Acknowledged", LinkLayer.DEBUG);
            } else {
                this.ll.log("Ack arrived in state: " + this.state, LinkLayer.DEBUG);
            }
        }
    }
}
