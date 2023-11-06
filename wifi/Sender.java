package wifi;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import rf.RF;

public class Sender implements Runnable {
    private static final int DIFS = RF.aSlotTime; // TODO what is difs?

    /* The link layer running this thread */
    private final LinkLayer ll;
    /* The queue of data packets we have to send */
    private final BlockingQueue<Packet> queue; 
    private final Random rand; // for random wait time

    private State state;

    private Packet currentPkt;
    private int retries;
    private int currentCW;
    private int slotWaitCount;
    private boolean cautious;
    private boolean validAck;

    public Sender(LinkLayer ll) {
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(); // TODO q size?
        this.rand = new Random();
        this.currentCW = RF.aCWmin;
        this.slotWaitCount = 0;
        this.cautious = false;
    }

    @Override
    public void run() {
        stateExecution: while (!Thread.interrupted()) {
            try {
                switch (this.state) {
                    case AWAITING_DATA: {
                        // Wait until we need to do stuff
                        this.currentPkt = this.queue.take();
                        if (this.ll.rf.inUse()) {
                            this.cautious = true;
                            this.slotWaitCount = this.rand.nextInt(this.currentCW);
                            this.state = State.AWAITING_IDLE;
                        } else {
                            this.cautious = false;
                            this.slotWaitCount = 0;
                            Thread.sleep(DIFS);
                            this.state = State.AWAITING_SLOT;
                        }
                        continue stateExecution;
                    }
                    case AWAITING_ACK: {
                        synchronized (this) {
                            this.wait(DIFS);
                            if (this.validAck){
                                this.state = State.AWAITING_DATA;
                            } else {
                                this.state = State.AWAITING_IDLE;
                            }
                        }
                        this.validAck = false;
                        continue stateExecution;
                    }
                    case AWAITING_IDLE: {
                        while (this.ll.rf.inUse()) {
                            // TODO take a lil nappy
                            Thread.sleep(0);
                        }
                        Thread.sleep(DIFS);
                        if (!this.ll.rf.inUse()) {
                            this.state = State.AWAITING_SLOT;
                        }
                        continue stateExecution;
                    }
                    case AWAITING_SLOT: {
                        continue stateExecution;
                    }
                    default: {
                        this.ll.output.println("Entered an invalid state. Terminating sending thread.");
                        return;
                    }
                }

            } catch (InterruptedException e) {
                this.ll.output.printf("interrupted while waiting in %s.\n", this.state);
            }
        }
    }

    public boolean enqueue(Packet pkt){
        return this.queue.offer(pkt);
    }

    public void ack(){
        synchronized (this) {
            if (this.state == State.AWAITING_ACK){
                this.validAck = true;
                this.notify();
            }
        }
    }
}
