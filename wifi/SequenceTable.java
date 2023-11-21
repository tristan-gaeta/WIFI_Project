package wifi;

import java.util.HashMap;
import java.util.Map;

public class SequenceTable {
    private final Map<Short, Short> seqNums;

    public SequenceTable(){
        this.seqNums = new HashMap<>();
    }

    public short currentSeqNum(short addr){
        this.seqNums.putIfAbsent(addr, (short) 0);
        return this.seqNums.get(addr);
    }

    public void increment(short addr){
        int next = (this.seqNums.get(addr) + 1) & 0xFFF;
        this.seqNums.put(addr, (short) next);
    }
}
