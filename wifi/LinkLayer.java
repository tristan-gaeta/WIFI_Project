package wifi;

import java.io.PrintWriter;

import rf.RF;

/**
 * Use this layer as a starting point for your project code. See
 * {@link Dot11Interface} for more
 * details on these routines.
 * 
 * @author Tristan Gaeta
 */
public class LinkLayer implements Dot11Interface {
	// modes for writing to log (bitmask)
	public static final int DEBUG = 1;
	public static final int STATE = 2;
	public static final int ERROR = 4;

	public final RF rf;
	public final PrintWriter out;
	public final short macAddr;
	public final Sender sender;
	public final Receiver receiver;

	private int logMode;

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * 
	 * @param ourMAC MAC address
	 * @param output Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this.macAddr = ourMAC;
		this.out = output;
		this.logMode = -1;

		this.rf = new RF(null, null);
		this.sender = new Sender(this);
		this.receiver = new Receiver(this);

		new Thread(this.sender).start();
		new Thread(this.receiver).start();
	}

	/**
	 * Print a message if the current log mode and the given
	 * mask have any common bit set. 
	 * 
	 * @param msg
	 * @param mask
	 */
	public void log(String msg, int mask) {
		if ((this.logMode & mask) != 0) {
			this.out.println(msg);
		}
	}

	@Override
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		int bytesToSend = Math.min(Math.min(data.length, len), Packet.MAX_DATA_SIZE);

		if (bytesToSend != len) {
			this.log("Cannot send all " + len + " bytes of data. Sending first " + bytesToSend + " bytes.", ERROR);
		}

		Packet pkt = new Packet(Packet.DATA, 0, dest, this.macAddr, data, bytesToSend); //TODO seq #
		this.log("Enqueueing packet: " + pkt.toString(), DEBUG);
		boolean success = this.sender.enqueue(pkt);

		if (!success) {
			this.log("Failed to enqueue outgoing data packet.", ERROR);
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
		Packet pkt = this.receiver.next();
		byte[] data = pkt.extractData();
		t.setBuf(data);
		t.setDestAddr(pkt.getDest());
		t.setSourceAddr(pkt.getSource());
		return data.length;
	}

	@Override
	/**
	 * Returns a current status code. See docs for full description.
	 */
	public int status() {
		out.println("LinkLayer: Faking a status() return value of 0"); //TODO
		return 0;
	}

	@Override
	/**
	 * Passes command info to your link layer. See docs for full description.
	 */
	public int command(int cmd, int val) {
		switch (cmd) {
			case 0: {
				String summary = "-------Summary-------\n";
				summary += "Command 0: print summary\n";
				summary += "Command 1: set debug level\n";
				summary += "\tinput is a bitmask where bits 0-3 are for debug, errors, and state respectively.\n";
				summary += "\tCurrent Value: " + this.logMode;
				this.out.println(summary);
				break;
			}

			case 1: {
				this.logMode = val | ERROR; //err messages are always logged
			}

			// TODO other commands

			default:
				break;
		}
		return 0;
	}
}
