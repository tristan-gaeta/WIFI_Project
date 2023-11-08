package wifi;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import rf.RF;

public class Receiver implements Runnable{
    private final LinkLayer ll;
    private final BlockingQueue<Packet> queue; 

    public Receiver(LinkLayer ll){
        this.ll = ll;
        this.queue = new LinkedBlockingQueue<>(); 
    }

    public Packet next(){
        try {
            return this.queue.take();
        } catch (InterruptedException e) {
            this.ll.out.println("Interrupted while blocking for incoming data.");
            return null;
        }
    }

    @Override
    public void run() {
        while (!Thread.interrupted()){
            byte[] data = this.ll.rf.receive();
            Packet pkt = new Packet(data);

            if (pkt.isValid()){

                this.ll.log("Incoming packet has type: "+pkt.getFrameType(),LinkLayer.DEBUG);
                switch (pkt.getFrameType()) {
                    case Packet.DATA: {
                        //send ACK
                        Packet ack = new Packet(Packet.ACK,0,pkt.getSource(),this.ll.macAddr);
                        if (pkt.getDest() == this.ll.macAddr){
                            //TODO wait sifs
                            this.ll.rf.transmit(ack.asBytes());
                            this.ll.log("Sending ACK: "+ack.toString(),LinkLayer.DEBUG);
                        }
                        // queue data
                        this.queue.offer(pkt);
                        break;
                    }

                    case Packet.ACK: {
                        this.ll.log("Got ACK: "+pkt.toString(), LinkLayer.DEBUG);
                        this.ll.sender.ack();
                        break;
                    }
                
                    default:{
                        //TODO other cases
                        break;
                    }
                }
            }
        }
    }
}
