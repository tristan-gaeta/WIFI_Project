package wifi;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Receiver implements Runnable{
    private final LinkLayer ll;
    private final BlockingQueue<Packet> queue; 

    public Receiver(LinkLayer ll){
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(); 
    }

    public Packet dequeue(){
        try {
            return this.queue.take();
        } catch (InterruptedException e) {
            this.ll.output.println("Interrupted while blocking for incoming data.");
            return null;
        }
    }

    @Override
    public void run() {
        while (!Thread.interrupted()){
            byte[] data = this.ll.rf.receive();
            Packet pkt = new Packet(data);

            if (pkt.isValid()){
                switch (pkt.getFrameType()) {
                    case Packet.DATA: {
                        this.queue.offer(pkt);
                        break;
                    }

                    case Packet.ACK: {
                        this.ll.sender.ack();
                        break;
                    }
                
                    default:{
                        //TODO other cases
                        break;
                    }
                }
                this.queue.offer(pkt);
            }
        }
    }
}
