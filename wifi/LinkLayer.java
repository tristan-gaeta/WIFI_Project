package wifi;

import java.io.PrintWriter;
import rf.RF;

/**
 * This class provides an implementation of the 802.11~ protocol.
 * See {@link Dot11Interface} for more details on these routines.
 * 
 * @author Tristan Gaeta
 */
public class LinkLayer implements Dot11Interface {
	/** Bitmask for log output mode. */
	public static final int ERROR = 1, STATE = 2, DEBUG = 4, TIMING = 8;

	/**
	 * Timing related constant.
	 * <p>
	 * (These values were observed from tests run on a
	 * MacBook Air 1.6 GHz Dual-Core Intel Core i5 processor.)
	 */
	public static final int ACK_TIMEOUT = 2000, BEACON_DELIVERY_TIME = 2330, BEACON_UNPACK_TIME = 1;

	/** Status code for the {@link Dot11Interface}. */
	public static final int SUCCESS = 1,
			UNSPECIFIED_ERROR = 2,
			RF_INIT_FAILED = 3,
			TX_DELIVERED = 4,
			TX_FAILED = 5,
			BAD_BUF_SIZE = 6,
			BAD_ADDRESS = 7,
			BAD_MAC_ADDRESS = 8,
			ILLEGAL_ARGUMENT = 9,
			INSUFFICIENT_BUFFER_SPACE = 10;

	/** timing is aligned by boundaries of this size. */
	public static final int BOUNDARY_SIZE = 50;

	// Final fields
	public final RF rf;
	public final short macAddr;
	public final Sender sender;
	public final Receiver receiver;

	private final PrintWriter out;

	// Instance variables
	private int debugLevel;

	// Volatile instance variables
	public volatile int clock_offset = 10;
	public volatile long beaconFrequency = 12_000;
	public volatile boolean randomWait = true;
	public volatile int status = 0;
	public volatile boolean timing;


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

		this.status = SUCCESS;
	}

	/**
	 * Print a message if the current log mode and the given
	 * mask have any common bit set.
	 */
	public void log(String msg, int mask) {
		if ((this.debugLevel & mask) != 0) {
			this.out.println(this.time() + ": " + msg);
		}
	}

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	@Override
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

		if (success) {
			this.status = SUCCESS;
			return bytesToSend;
		} else {
			this.status = INSUFFICIENT_BUFFER_SPACE;
			return -1;
		}
	}

	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	@Override
	public int recv(Transmission t) {
		// blocks until next valid data packet
		Packet pkt = this.receiver.nextPacket();

		if (pkt == null) {
			this.status = UNSPECIFIED_ERROR;
			return 0;
		} else {
			byte[] data = pkt.extractData();
			t.setBuf(data);
			t.setDestAddr(pkt.getDest());
			t.setSourceAddr(pkt.getSource());
			this.status = SUCCESS;
			return data.length;
		}
	}

	/**
	 * Returns a current status code. See docs for full description.
	 */
	@Override
	public int status() {
		return this.status;
	}

	@Override
	/**
	 * Passes command info to your link layer. See docs for full description.
	 */
	public int command(int cmd, int val) {
		switch (cmd) {
			case 0: {
				String summary = "-------Summary-------\n";
				summary += "Command 0: Print summary\n";
				summary += "Command 1: Set debug level\n";
				summary += "\tinput is a bitmask where bits 0-4 are for errors, state, debug,\n";
				summary += "\tand timing respectively. Bit 0 is always set.\n";
				summary += "\tCurrent Value: " + this.debugLevel;
				summary += "\nCommand 2: Set fixed slot selection\n";
				summary += "\tinput of 0 will result in random slot selection.\n";
				summary += "\tAny other value will result in fixed slot selection\n";
				summary += "Command 3: Beacon frequency\n";
				summary += "\ta nonnegative input will set the frequency of beacon frames\n";
				summary += "\tto that many seconds. A negative value will disable beacon frames.\n";
				this.out.println(summary);
				return 0;
			}

			case 1: {
				this.debugLevel = val | ERROR; // err messages are always logged
				this.timing = (this.debugLevel & TIMING) != 0;
				this.out.println("Setting debug state to " + this.debugLevel);
				this.status = SUCCESS;
				return this.debugLevel;
			}

			case 2: {
				this.randomWait = val == 0;
				this.out.println("Setting fixed slot wait to " + !this.randomWait);
				this.status = SUCCESS;
				return val;
			}

			case 3: {
				this.beaconFrequency = val < 0 ? Long.MAX_VALUE : 1000 * val;
				this.out.println("Setting beacon frequency to: " + this.beaconFrequency);
				this.status = SUCCESS;
				return val;
			}

			default:
				this.out.println("Unknown command: (" + cmd + ", " + val + ")");
				this.out.println("Enter command (0, 0) for option summary.");
				this.status = ILLEGAL_ARGUMENT;
				return 0;
		}
	}

	/**
	 * The local time is the current {@code RF} time plus some offset
	 * 
	 * @return current local time
	 */
	public long time() {
		return this.rf.clock() + this.clock_offset;
	}

	/**
	 * Returns the current local clock time rounded to the nearest boundary
	 * 
	 * @return rounded time
	 */
	public long nextBoundary() {
		return this.nearestBoundaryTo(this.time());
	}

	/**
	 * Rounds the given time to the nearest {@value #BOUNDARY_SIZE}ms boundary
	 * 
	 * @param time
	 * @return rounded time
	 */
	public long nearestBoundaryTo(long time) {
		long blockTime = time % BOUNDARY_SIZE;
		if (blockTime == 0) {
			return time;
		} else {
			return time + BOUNDARY_SIZE - blockTime;
		}
	}

	/**
	 * Block until the given local time. Performs a combination of sleeping
	 * and spin waiting.
	 * 
	 * @param targetTime local {@code RF} time in milliseconds
	 * @throws InterruptedException
	 */
	public void waitUntil(long targetTime) throws InterruptedException {
		long busyWaitTime = 2; // ms

		// sleep wait
		long sleepTime = targetTime - busyWaitTime - this.time();
		if (sleepTime >= 0) {
			Thread.sleep(sleepTime);
		}

		// busy wait
		while (this.time() < targetTime) {
			Thread.onSpinWait();
		}
	}
}
