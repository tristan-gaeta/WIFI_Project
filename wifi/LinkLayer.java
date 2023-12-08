package wifi;

import java.io.PrintWriter;

import rf.RF;

/**
 * 
 * See {@link Dot11Interface} for more details on these routines.
 * 
 * @author Tristan Gaeta
 * @version 10/23
 */
public class LinkLayer implements Dot11Interface {
	/** Bitmask for output log mode. */
	public static final int DEBUG = 1, STATE = 2, ERROR = 4, TIMING = 8;

	/** timing is aligned by {@value #BOUNDARY_SIZE}ms boundaries. */
	public static final int BOUNDARY_SIZE = 50;

	public final RF rf;
	public final short macAddr;
	public final Sender sender;
	public final Receiver receiver;

	public volatile long beaconFrequency = 12_000;

	private final PrintWriter out;
	private int offset;
	private int debugLevel;

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
		this.debugLevel = ERROR;

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
		if ((this.debugLevel & mask) != 0) {
			this.out.println(msg);
		}
	}

	@Override
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		// cannot send data under these conditions
		if (len < 0 || data == null || dest == this.macAddr) {
			return -1;
		}

		// limit size of the packet's data portion
		int bytesToSend = Math.min(Math.min(data.length, len), Packet.MAX_DATA_SIZE);
		if (bytesToSend != len) {
			this.log("Cannot send all " + len + " bytes of data. Sending first " + bytesToSend + " bytes.", ERROR);
		}

		boolean success = this.sender.enqueue(dest, data, bytesToSend);

		return success ? bytesToSend : -1;
	}

	@Override
	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	public int recv(Transmission t) {
		Packet pkt = this.receiver.nextPacket();

		if (pkt == null) {
			return 0;
		} else {
			byte[] data = pkt.extractData();
			t.setBuf(data);
			t.setDestAddr(pkt.getDest());
			t.setSourceAddr(pkt.getSource());
			return data.length;
		}
	}

	@Override
	/**
	 * Returns a current status code. See docs for full description.
	 */
	public int status() {
		out.println("LinkLayer: Faking a status() return value of 0"); // TODO
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
				summary += "\tCurrent Value: " + this.debugLevel;
				this.out.println(summary);
				return 0;
			}

			case 1: {
				this.debugLevel = val | ERROR; // err messages are always logged
				this.log("Setting debug state to " + this.debugLevel, ERROR);
				return 0;
			}

			// TODO other commands

			default:
				return 0;
		}
	}

	public long time() {
		return this.rf.clock() + this.offset;
	}

	public long nextBoundary(){
		return this.nearestBoundaryTo(this.time());
	}

	public long nearestBoundaryTo(long time) {
		long blockTime = time % BOUNDARY_SIZE;
		if (blockTime == 0) {
			return time;
		} else {
			return time + BOUNDARY_SIZE - blockTime;
		}
	}

	public void waitUntil(long targetTime) throws InterruptedException {
		long busyWaitTime = 2; // ms
		// sleep wait
		try {
			Thread.sleep(targetTime - this.time() - busyWaitTime);
		} catch (IllegalArgumentException e) {
		}
		// busy wait
		while (this.time() < targetTime) {
			Thread.onSpinWait();
		}
	}
}
