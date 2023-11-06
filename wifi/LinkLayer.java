package wifi;

import java.io.PrintWriter;

import rf.RF;

/**
 * Use this layer as a starting point for your project code. See
 * {@link Dot11Interface} for more
 * details on these routines.
 * 
 * @author richards
 */
public class LinkLayer implements Dot11Interface {
	public final RF rf = new RF(null, null);
	public final PrintWriter output;
	public final short macAddr; // Our MAC address
	public final Sender sender;
	public final Receiver receiver;

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * 
	 * @param ourMAC MAC address
	 * @param output Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this.macAddr = ourMAC;
		this.output = output;
		this.sender = new Sender(this);
		this.receiver = new Receiver(this);
		output.println("LinkLayer: Constructor ran.");
	}

	@Override
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		int bytesToSend = Math.min(Math.min(data.length, len), Packet.MAX_DATA_SIZE);

		if (bytesToSend != len) {
			this.output.println("Cannot send all "+len+" bytes. Sending "+bytesToSend+" bytes.");
		}

		Packet pkt = new Packet(Packet.DATA, 0, dest, this.macAddr, data, bytesToSend);
		boolean success = this.sender.enqueue(pkt);
		if (!success) {
			this.output.println("Failed to enqueue outgoing data packet.");
			return -1;
		} else {
			return bytesToSend;
		}
	}

	@Override
	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	public int recv(Transmission t) {
		Packet pkt = this.receiver.dequeue();
		t.setBuf(pkt.extractData());
		return -1; //TODO
	}

	@Override
	/**
	 * Returns a current status code. See docs for full description.
	 */
	public int status() {
		output.println("LinkLayer: Faking a status() return value of 0");
		return 0;
	}

	@Override
	/**
	 * Passes command info to your link layer. See docs for full description.
	 */
	public int command(int cmd, int val) {
		output.println("LinkLayer: Sending command " + cmd + " with value " + val);
		return 0;
	}
}
