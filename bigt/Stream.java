package bigt;

import global.MID;
import global.MapOrder;
import heap.Heapfile;
import iterator.FileScan;
import iterator.FldSpec;
import iterator.RelSpec;
import iterator.Sort;

/**
 * This class aggregates the streams from different types in a big table and provides a unified stream on the entire
 * big table
 */
public class Stream {

    private Sort filteredAndSortedData;
    private Heapfile tempHeapFile;
    private int numberOfMapsFound;
    private heap.Scan scan;

    public Stream(BigTable bigTable, int orderType, String rowFilter, String columnFilter, String valueFilter) throws Exception {
        tempHeapFile = new Heapfile("query_temp_heap_file");

        BigT bigT = null;
        for (int i = 1; i < bigTable.getBigTableParts().size(); i++) {
            bigT = bigTable.getBigTableParts().get(i);
            BigTStream bigTStream = bigT.openStream(rowFilter, columnFilter, valueFilter);
            MID mid = new MID();
            Map map = bigTStream.getNext(mid);
            while (map != null) {
                tempHeapFile.insertMap(map.getMapByteArray());
                mid = new MID();
                map = bigTStream.getNext(mid);
            }
            bigTStream.closeStream();
        }

        if (orderType == 0) {
            scan = tempHeapFile.openScan();
            return;
        }
        //Now temp heap file contains all the filtered data which we have to sort based on the order type
        // create an iterator by open a file scan
        FldSpec[] projlist = new FldSpec[4];
        RelSpec rel = new RelSpec(RelSpec.outer);
        projlist[0] = new FldSpec(rel, 1);
        projlist[1] = new FldSpec(rel, 2);
        projlist[2] = new FldSpec(rel, 3);
        projlist[3] = new FldSpec(rel, 4);

        FileScan fscan = null;

        try {
            fscan = new FileScan("query_temp_heap_file", Minibase.getInstance().getAttrTypes(),
                    Minibase.getInstance().getAttrSizes(), (short) 4, 4, projlist, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Minibase.getInstance().setOrderType(orderType);
        int sortField = -1;
        int maxLength = -1;
        switch (orderType) {
            case 1:
            case 3:
                sortField = 1;
                maxLength = Minibase.getInstance().getMaxRowKeyLength();
                break;
            case 2:
            case 4:
                sortField = 2;
                maxLength = Minibase.getInstance().getMaxColumnKeyLength();
                break;
            case 6:
                sortField = 3;
                maxLength = Minibase.getInstance().getMaxTimeStampLength();
                break;
        }
        int memory = Minibase.getInstance().getNumberOfBuffersAvailable();
        try {
            filteredAndSortedData = new Sort(Minibase.getInstance().getAttrTypes(), (short) 4,
                    Minibase.getInstance().getAttrSizes(), fscan, sortField, new MapOrder(MapOrder.Ascending),
                    maxLength, memory / 2);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * This method helps to get the maps in the specified order - which is specified as the order type parameter
     * @return
     * @throws Exception
     */
    public Map getNext() throws Exception {
        if (scan == null) {
            Map m = filteredAndSortedData.get_next();
            if (m != null) {
                ++numberOfMapsFound;
            }
            return m;
        } else {
            MID mid = new MID();
            Map m = scan.getNext(mid);
            if (m != null) {
                m.setHdr((short) 4, Minibase.getInstance().getAttrTypes(), Minibase.getInstance().getAttrSizes());
            }
            return m;
        }
    }

    public int getNumberOfMapsFound() {
        return numberOfMapsFound;
    }

    public void close() throws Exception {
        tempHeapFile.deleteFile();
        if (filteredAndSortedData != null) {
            filteredAndSortedData.close();
        }
        if (scan != null) {
            scan.closescan();
        }
    }
}
