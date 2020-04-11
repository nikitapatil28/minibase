package heap;

import bigt.Map;
import diskmgr.Page;
import global.GlobalConst;
import global.PageId;
import global.MID;
import global.SystemDefs;

import java.io.IOException;

/**
 * This heapfile implementation is directory-based. We maintain a
 * directory of info about the data pages (which are of type HFPage
 * when loaded into memory).  The directory itself is also composed
 * of HFPages, with each record being of type DataPageInfo
 * as defined below.
 * <p>
 * The first directory page is a header page for the entire database
 * (it is the one to which our filename is mapped by the DB).
 * All directory pages are in a doubly-linked list of pages, each
 * directory entry points to a single data page, which contains
 * the actual records.
 * <p>
 * The heapfile data pages are implemented as slotted pages, with
 * the slots at the front and the records in the back, both growing
 * into the free space in the middle of the page.
 * <p>
 * We can store roughly pagesize/sizeof(DataPageInfo) records per
 * directory page; for any given HeapFile insertion, it is likely
 * that at least one of those referenced data pages will have
 * enough free space to satisfy the request.
 */


/**
 * DataPageInfo class : the type of records stored on a directory page.
 * <p>
 * April 9, 1998
 */


interface Filetype {
    int TEMP = 0;
    int ORDINARY = 1;

} // end of Filetype

public class Heapfile implements Filetype, GlobalConst {


    PageId _firstDirPageId;   // page number of header page
    int _ftype;
    private boolean _file_deleted;
    private String _fileName;
    private static int tempfilecount = 0;


    /* get a new datapage from the buffer manager and initialize dpinfo
       @param dpinfop the information in the new HFPage
    */
    private HFPage _newDatapage(DataPageInfo dpinfop)
            throws HFException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException {
        Page apage = new Page();
        PageId pageId = new PageId();
        pageId = newPage(apage, 1);

        if (pageId == null)
            throw new HFException(null, "can't new pae");

        // initialize internal values of the new page:

        HFPage hfpage = new HFPage();
        hfpage.init(pageId, apage);

        dpinfop.pageId.pid = pageId.pid;
        dpinfop.recct = 0;
        dpinfop.availspace = hfpage.available_space();

        return hfpage;

    } // end of _newDatapage

    /* Internal HeapFile function (used in getRecord and updateRecord):
       returns pinned directory page and pinned data page of the specified
       user record(mid) and true if record is found.
       If the user record cannot be found, return false.
    */
    private boolean _findDataPage(MID mid,
                                  PageId dirPageId, HFPage dirpage,
                                  PageId dataPageId, HFPage datapage,
                                  MID rpDataPageMid)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            Exception {
        PageId currentDirPageId = new PageId(_firstDirPageId.pid);

        HFPage currentDirPage = new HFPage();
        HFPage currentDataPage = new HFPage();
        MID currentDataPageMid = new MID();
        PageId nextDirPageId = new PageId();
        // datapageId is stored in dpinfo.pageId


        pinPage(currentDirPageId, currentDirPage, false/*read disk*/);

        Map aMap = new Map();

        while (currentDirPageId.pid != INVALID_PAGE) {// Start While01
            // ASSERTIONS:
            //  currentDirPage, currentDirPageId valid and pinned and Locked.

            for (currentDataPageMid = currentDirPage.firstMap();
                 currentDataPageMid != null;
                 currentDataPageMid = currentDirPage.nextMap(currentDataPageMid)) {
                try {
                    aMap = currentDirPage.getMap(currentDataPageMid);
                } catch (InvalidSlotNumberException e)// check error! return false(done)
                {
                    return false;
                }

                DataPageInfo dpinfo = new DataPageInfo(aMap);
                try {
                    pinPage(dpinfo.pageId, currentDataPage, false/*Rddisk*/);


                    //check error;need unpin currentDirPage
                } catch (Exception e) {
                    unpinPage(currentDirPageId, false/*undirty*/);
                    dirpage = null;
                    datapage = null;
                    throw e;
                }


                // ASSERTIONS:
                // - currentDataPage, currentDataPageMid, dpinfo valid
                // - currentDataPage pinned

                if (dpinfo.pageId.pid == mid.pageNo.pid) {
                    aMap = currentDataPage.returnMap(mid);
                    // found user's Map on the current datapage which itself
                    // is indexed on the current dirpage.  Return both of these.

                    dirpage.setpage(currentDirPage.getpage());
                    dirPageId.pid = currentDirPageId.pid;

                    datapage.setpage(currentDataPage.getpage());
                    dataPageId.pid = dpinfo.pageId.pid;

                    rpDataPageMid.pageNo.pid = currentDataPageMid.pageNo.pid;
                    rpDataPageMid.slotNo = currentDataPageMid.slotNo;
                    return true;
                } else {
                    // user Map not found on this datapage; unpin it
                    // and try the next one
                    unpinPage(dpinfo.pageId, false /*undirty*/);

                }

            }

            // if we would have found the correct datapage on the current
            // directory page we would have already returned.
            // therefore:
            // read in next directory page:

            nextDirPageId = currentDirPage.getNextPage();
            try {
                unpinPage(currentDirPageId, false /*undirty*/);
            } catch (Exception e) {
                throw new HFException(e, "heapfile,_find,unpinpage failed");
            }

            currentDirPageId.pid = nextDirPageId.pid;
            if (currentDirPageId.pid != INVALID_PAGE) {
                pinPage(currentDirPageId, currentDirPage, false/*Rdisk*/);
                if (currentDirPage == null)
                    throw new HFException(null, "pinPage return null page");
            }


        } // end of While01
        // checked all dir pages and all data pages; user Map not found:(

        dirPageId.pid = dataPageId.pid = INVALID_PAGE;

        return false;


    } // end of _findDatapage		     

    /**
     * Initialize.  A null name produces a temporary heapfile which will be
     * deleted by the destructor.  If the name already denotes a file, the
     * file is opened; otherwise, a new empty file is created.
     *
     * @throws HFException        heapfile exception
     * @throws HFBufMgrException  exception thrown from bufmgr layer
     * @throws HFDiskMgrException exception thrown from diskmgr layer
     * @throws IOException        I/O errors
     */
    public Heapfile(String name)
            throws HFException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException {
        // Give us a prayer of destructing cleanly if construction fails.
        _file_deleted = true;
        _fileName = null;

        if (name == null) {
            // If the name is NULL, allocate a temporary name
            // and no logging is required.
            _fileName = "tempHeapFile";
            String useId = new String("user.name");
            String userAccName;
            userAccName = System.getProperty(useId);
            _fileName = _fileName + userAccName;

            String filenum = Integer.toString(tempfilecount);
            _fileName = _fileName + filenum;
            _ftype = TEMP;
            tempfilecount++;

        } else {
            _fileName = name;
            _ftype = ORDINARY;
        }

        // The constructor gets run in two different cases.
        // In the first case, the file is new and the header page
        // must be initialized.  This case is detected via a failure
        // in the db->get_file_entry() call.  In the second case, the
        // file already exists and all that must be done is to fetch
        // the header page into the buffer pool

        // try to open the file

        Page apage = new Page();
        _firstDirPageId = null;
        if (_ftype == ORDINARY)
            _firstDirPageId = get_file_entry(_fileName);

        if (_firstDirPageId == null) {
            // file doesn't exist. First create it.
            _firstDirPageId = newPage(apage, 1);
            // check error
            if (_firstDirPageId == null)
                throw new HFException(null, "can't new page");

            add_file_entry(_fileName, _firstDirPageId);
            // check error(new exception: Could not add file entry

            HFPage firstDirPage = new HFPage();
            firstDirPage.init(_firstDirPageId, apage);
            PageId pageId = new PageId(INVALID_PAGE);

            firstDirPage.setNextPage(pageId);
            firstDirPage.setPrevPage(pageId);
            unpinPage(_firstDirPageId, true /*dirty*/);


        }
        _file_deleted = false;
        // ASSERTIONS:
        // - ALL private data members of class Heapfile are valid:
        //
        //  - _firstDirPageId valid
        //  - _fileName valid
        //  - no datapage pinned yet

    } // end of constructor 

    /**
     * Return number of Maps in file.
     *
     * @throws InvalidSlotNumberException invalid slot number
     * @throws InvalidTupleSizeException  invalid tuple size
     * @throws HFBufMgrException          exception thrown from bufmgr layer
     * @throws HFDiskMgrException         exception thrown from diskmgr layer
     * @throws IOException                I/O errors
     */
    public int getRecCnt()
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFDiskMgrException,
            HFBufMgrException,
            IOException {
        int answer = 0;
        PageId currentDirPageId = new PageId(_firstDirPageId.pid);

        PageId nextDirPageId = new PageId(0);

        HFPage currentDirPage = new HFPage();
        Page pageinbuffer = new Page();

        while (currentDirPageId.pid != INVALID_PAGE) {
            pinPage(currentDirPageId, currentDirPage, false);

            MID mid = new MID();
            Map aMap;
            for (mid = currentDirPage.firstMap();
                 mid != null;    // mid==NULL means no more Map
                 mid = currentDirPage.nextMap(mid)) {
                aMap = currentDirPage.getMap(mid);
                DataPageInfo dpinfo = new DataPageInfo(aMap);

                answer += dpinfo.recct;
            }

            // ASSERTIONS: no more Map
            // - we have read all datapage Maps on
            //   the current directory page.

            nextDirPageId = currentDirPage.getNextPage();
            unpinPage(currentDirPageId, false /*undirty*/);
            currentDirPageId.pid = nextDirPageId.pid;
        }

        // ASSERTIONS:
        // - if error, exceptions
        // - if end of heapfile reached: currentDirPageId == INVALID_PAGE
        // - if not yet end of heapfile: currentDirPageId valid


        return answer;
    } // end of getRecCnt

    /**
     * Insert Map into file, return its Mid.
     *
     * @param recPtr pointer of the Map
     * @return the mid of the Map
     * @throws InvalidSlotNumberException invalid slot number
     * @throws InvalidTupleSizeException  invalid tuple size
     * @throws SpaceNotAvailableException no space left
     * @throws HFException                heapfile exception
     * @throws HFBufMgrException          exception thrown from bufmgr layer
     * @throws HFDiskMgrException         exception thrown from diskmgr layer
     * @throws IOException                I/O errors
     */
    public MID insertMap(byte[] recPtr)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            SpaceNotAvailableException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException {
        int dpinfoLen = 0;
        int recLen = recPtr.length;
        boolean found;
        MID currentDataPageMid = new MID();
        Page pageinbuffer = new Page();
        HFPage currentDirPage = new HFPage();
        HFPage currentDataPage = new HFPage();

        HFPage nextDirPage = new HFPage();
        PageId currentDirPageId = new PageId(_firstDirPageId.pid);
        PageId nextDirPageId = new PageId();  // OK

        pinPage(currentDirPageId, currentDirPage, false/*Rdisk*/);

        found = false;
        Map aMap;
        DataPageInfo dpinfo = new DataPageInfo();
        while (found == false) { //Start While01
            // look for suitable dpinfo-struct
            for (currentDataPageMid = currentDirPage.firstMap();
                 currentDataPageMid != null;
                 currentDataPageMid =
                         currentDirPage.nextMap(currentDataPageMid)) {
                aMap = currentDirPage.getMap(currentDataPageMid);

                dpinfo = new DataPageInfo(aMap);

                // need check the Map length == DataPageInfo'slength

                if (recLen <= dpinfo.availspace) {
                    found = true;
                    break;
                }
            }

            // two cases:
            // (1) found == true:
            //     currentDirPage has a datapageMap which can accomodate
            //     the Map which we have to insert
            // (2) found == false:
            //     there is no datapageMap on the current directory page
            //     whose corresponding datapage has enough space free
            //     several subcases: see below
            if (found == false) { //Start IF01
                // case (2)

                //System.out.println("no datapageMap on the current directory is OK");
                //System.out.println("dirpage availspace "+currentDirPage.available_space());

                // on the current directory page is no datapageMap which has
                // enough free space
                //
                // two cases:
                //
                // - (2.1) (currentDirPage->available_space() >= sizeof(DataPageInfo):
                //         if there is enough space on the current directory page
                //         to accomodate a new datapageMap (type DataPageInfo),
                //         then insert a new DataPageInfo on the current directory
                //         page
                // - (2.2) (currentDirPage->available_space() <= sizeof(DataPageInfo):
                //         look at the next directory page, if necessary, create it.

                if (currentDirPage.available_space() >= dpinfo.size) {
                    //Start IF02
                    // case (2.1) : add a new data page Map into the
                    //              current directory page
                    currentDataPage = _newDatapage(dpinfo);
                    // currentDataPage is pinned! and dpinfo->pageId is also locked
                    // in the exclusive mode

                    // didn't check if currentDataPage==NULL, auto exception


                    // currentDataPage is pinned: insert its Map
                    // calling a HFPage function


                    aMap = dpinfo.convertToMap();

                    byte[] tmpData = aMap.getMapByteArray();
                    currentDataPageMid = currentDirPage.insertMap(tmpData);

                    MID tmpMid = currentDirPage.firstMap();


                    // need catch error here!
                    if (currentDataPageMid == null)
                        throw new HFException(null, "no space to insert rec.");

                    // end the loop, because a new datapage with its Map
                    // in the current directorypage was created and inserted into
                    // the heapfile; the new datapage has enough space for the
                    // Map which the user wants to insert

                    found = true;

                } //end of IF02
                else {  //Start else 02
                    // case (2.2)
                    nextDirPageId = currentDirPage.getNextPage();
                    // two sub-cases:
                    //
                    // (2.2.1) nextDirPageId != INVALID_PAGE:
                    //         get the next directory page from the buffer manager
                    //         and do another look
                    // (2.2.2) nextDirPageId == INVALID_PAGE:
                    //         append a new directory page at the end of the current
                    //         page and then do another loop

                    if (nextDirPageId.pid != INVALID_PAGE) { //Start IF03
                        // case (2.2.1): there is another directory page:
                        unpinPage(currentDirPageId, false);

                        currentDirPageId.pid = nextDirPageId.pid;

                        pinPage(currentDirPageId,
                                currentDirPage, false);


                        // now go back to the beginning of the outer while-loop and
                        // search on the current directory page for a suitable datapage
                    } //End of IF03
                    else {  //Start Else03
                        // case (2.2): append a new directory page after currentDirPage
                        //             since it is the last directory page
                        nextDirPageId = newPage(pageinbuffer, 1);
                        // need check error!
                        if (nextDirPageId == null)
                            throw new HFException(null, "can't new pae");

                        // initialize new directory page
                        nextDirPage.init(nextDirPageId, pageinbuffer);
                        PageId temppid = new PageId(INVALID_PAGE);
                        nextDirPage.setNextPage(temppid);
                        nextDirPage.setPrevPage(currentDirPageId);

                        // update current directory page and unpin it
                        // currentDirPage is already locked in the Exclusive mode
                        currentDirPage.setNextPage(nextDirPageId);
                        unpinPage(currentDirPageId, true/*dirty*/);

                        currentDirPageId.pid = nextDirPageId.pid;
                        currentDirPage = new HFPage(nextDirPage);

                        // remark that MINIBASE_BM->newPage already
                        // pinned the new directory page!
                        // Now back to the beginning of the while-loop, using the
                        // newly created directory page.

                    } //End of else03
                } // End of else02
                // ASSERTIONS:
                // - if found == true: search will end and see assertions below
                // - if found == false: currentDirPage, currentDirPageId
                //   valid and pinned

            }//end IF01
            else { //Start else01
                // found == true:
                // we have found a datapage with enough space,
                // but we have not yet pinned the datapage:

                // ASSERTIONS:
                // - dpinfo valid

                // System.out.println("find the dirpageMap on current page");

                pinPage(dpinfo.pageId, currentDataPage, false);
                //currentDataPage.openHFpage(pageinbuffer);


            }//End else01
        } //end of While01

        // ASSERTIONS:
        // - currentDirPageId, currentDirPage valid and pinned
        // - dpinfo.pageId, currentDataPageMid valid
        // - currentDataPage is pinned!

        if ((dpinfo.pageId).pid == INVALID_PAGE) // check error!
            throw new HFException(null, "invalid PageId");

        if (!(currentDataPage.available_space() >= recLen))
            throw new SpaceNotAvailableException(null, "no available space");

        if (currentDataPage == null)
            throw new HFException(null, "can't find Data page");


        MID mid;
        mid = currentDataPage.insertMap(recPtr);

        dpinfo.recct++;
        dpinfo.availspace = currentDataPage.available_space();


        unpinPage(dpinfo.pageId, true /* = DIRTY */);

        // DataPage is now released
        aMap = currentDirPage.returnMap(currentDataPageMid);
        DataPageInfo dpinfo_ondirpage = new DataPageInfo(aMap);


        dpinfo_ondirpage.availspace = dpinfo.availspace;
        dpinfo_ondirpage.recct = dpinfo.recct;
        dpinfo_ondirpage.pageId.pid = dpinfo.pageId.pid;
        dpinfo_ondirpage.flushToMap();


        unpinPage(currentDirPageId, true /* = DIRTY */);


        return mid;

    }

    /**
     * Delete Map from file with given mid.
     *
     * @return true Map deleted  false:Map not found
     * @throws InvalidSlotNumberException invalid slot number
     * @throws InvalidTupleSizeException  invalid tuple size
     * @throws HFException                heapfile exception
     * @throws HFBufMgrException          exception thrown from bufmgr layer
     * @throws HFDiskMgrException         exception thrown from diskmgr layer
     * @throws Exception                  other exception
     */
    public boolean deleteMap(MID mid)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            Exception {
        boolean status;
        HFPage currentDirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage currentDataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        MID currentDataPageMid = new MID();

        status = _findDataPage(mid,
                currentDirPageId, currentDirPage,
                currentDataPageId, currentDataPage,
                currentDataPageMid);

        if (status != true) return status;    // Map not found

        // ASSERTIONS:
        // - currentDirPage, currentDirPageId valid and pinned
        // - currentDataPage, currentDataPageid valid and pinned

        // get datapageinfo from the current directory page:
        Map aMap;

        aMap = currentDirPage.returnMap(currentDataPageMid);
        DataPageInfo pdpinfo = new DataPageInfo(aMap);

        // delete the Map on the datapage
        currentDataPage.deleteMap(mid);

        pdpinfo.recct--;
        pdpinfo.flushToMap();    //Write to the buffer pool
        if (pdpinfo.recct >= 1) {
            // more Maps remain on datapage so it still hangs around.
            // we just need to modify its directory entry

            pdpinfo.availspace = currentDataPage.available_space();
            pdpinfo.flushToMap();
            unpinPage(currentDataPageId, true /* = DIRTY*/);

            unpinPage(currentDirPageId, true /* = DIRTY */);


        } else {
            // the Map is already deleted:
            // we're removing the last Map on datapage so free datapage
            // also, free the directory page if
            //   a) it's not the first directory page, and
            //   b) we've removed the last DataPageInfo Map on it.

            // delete empty datapage: (does it get unpinned automatically? -NO, Ranjani)
            unpinPage(currentDataPageId, false /*undirty*/);

            freePage(currentDataPageId);

            // delete corresponding DataPageInfo-entry on the directory page:
            // currentDataPageMid points to datapage (from for loop above)

            currentDirPage.deleteMap(currentDataPageMid);


            // ASSERTIONS:
            // - currentDataPage, currentDataPageId invalid
            // - empty datapage unpinned and deleted

            // now check whether the directory page is empty:

            currentDataPageMid = currentDirPage.firstMap();

            // st == OK: we still found a datapageinfo Map on this directory page
            PageId pageId;
            pageId = currentDirPage.getPrevPage();
            if ((currentDataPageMid == null) && (pageId.pid != INVALID_PAGE)) {
                // the directory-page is not the first directory page and it is empty:
                // delete it

                // point previous page around deleted page:

                HFPage prevDirPage = new HFPage();
                pinPage(pageId, prevDirPage, false);

                pageId = currentDirPage.getNextPage();
                prevDirPage.setNextPage(pageId);
                pageId = currentDirPage.getPrevPage();
                unpinPage(pageId, true /* = DIRTY */);


                // set prevPage-pointer of next Page
                pageId = currentDirPage.getNextPage();
                if (pageId.pid != INVALID_PAGE) {
                    HFPage nextDirPage = new HFPage();
                    pageId = currentDirPage.getNextPage();
                    pinPage(pageId, nextDirPage, false);

                    //nextDirPage.openHFpage(apage);

                    pageId = currentDirPage.getPrevPage();
                    nextDirPage.setPrevPage(pageId);
                    pageId = currentDirPage.getNextPage();
                    unpinPage(pageId, true /* = DIRTY */);

                }

                // delete empty directory page: (automatically unpinned?)
                unpinPage(currentDirPageId, false/*undirty*/);
                freePage(currentDirPageId);


            } else {
                // either (the directory page has at least one more datapageMap
                // entry) or (it is the first directory page):
                // in both cases we do not delete it, but we have to unpin it:

                unpinPage(currentDirPageId, true /* == DIRTY */);


            }
        }
        return true;
    }


    /**
     * Updates the specified Map in the heapfile.
     *
     * @param mid:    the Map which needs update
     * @param newMap: the new content of the Map
     * @return ture:update success   false: can't find the Map
     * @throws InvalidSlotNumberException invalid slot number
     * @throws InvalidUpdateException     invalid update on Map
     * @throws InvalidTupleSizeException  invalid tuple size
     * @throws HFException                heapfile exception
     * @throws HFBufMgrException          exception thrown from bufmgr layer
     * @throws HFDiskMgrException         exception thrown from diskmgr layer
     * @throws Exception                  other exception
     */
    public boolean updateMap(MID mid, Map newMap)
            throws InvalidSlotNumberException,
            InvalidUpdateException,
            InvalidTupleSizeException,
            HFException,
            HFDiskMgrException,
            HFBufMgrException,
            Exception {
        boolean status;
        HFPage dirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage dataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        MID currentDataPageMid = new MID();

        status = _findDataPage(mid,
                currentDirPageId, dirPage,
                currentDataPageId, dataPage,
                currentDataPageMid);

        if (status != true) return status;    // Map not found
        Map aMap = new Map();
        aMap = dataPage.returnMap(mid);

        // Assume update a Map with a Map whose length is equal to
        // the original Map

        if (newMap.getLength() != aMap.getLength()) {
            unpinPage(currentDataPageId, false /*undirty*/);
            unpinPage(currentDirPageId, false /*undirty*/);

            throw new InvalidUpdateException(null, "invalid Map update");

        }

        // new copy of this Map fits in old space;
        aMap.mapCopy(newMap);
        unpinPage(currentDataPageId, true /* = DIRTY */);

        unpinPage(currentDirPageId, false /*undirty*/);


        return true;
    }


    /**
     * Read Map from file, returning pointer and length.
     *
     * @param mid Map ID
     * @return a Tuple. if Tuple==null, no more tuple
     * @throws InvalidSlotNumberException invalid slot number
     * @throws InvalidTupleSizeException  invalid tuple size
     * @throws SpaceNotAvailableException no space left
     * @throws HFException                heapfile exception
     * @throws HFBufMgrException          exception thrown from bufmgr layer
     * @throws HFDiskMgrException         exception thrown from diskmgr layer
     * @throws Exception                  other exception
     */
    public Map getMap(MID mid)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFException,
            HFDiskMgrException,
            HFBufMgrException,
            Exception {
        boolean status;
        HFPage dirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage dataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        MID currentDataPageMid = new MID();

//        status = _findDataPage(mid,
//                currentDirPageId, dirPage,
//                currentDataPageId, dataPage,
//                currentDataPageMid);
//
//        if (status != true) return null; // Map not found

        currentDataPageId = mid.pageNo;
        pinPage(mid.pageNo, dataPage, false/*Rddisk*/);

        Map aMap = new Map();
        aMap = dataPage.getMap(mid);

        /*
         * getMap has copied the contents of mid into recPtr and fixed up
         * recLen also.  We simply have to unpin dirpage and datapage which
         * were originally pinned by _findDataPage.
         */

        unpinPage(currentDataPageId, false /*undirty*/);

//        unpinPage(currentDirPageId, false /*undirty*/);


        return aMap;  //(true?)OK, but the caller need check if atuple==NULL

    }


    /**
     * Initiate a sequential scan.
     *
     * @throws InvalidTupleSizeException Invalid tuple size
     * @throws IOException               I/O errors
     */
    public Scan openScan()
            throws InvalidTupleSizeException,
            IOException {
        Scan newscan = new Scan(this);
        return newscan;
    }


    /**
     * Delete the file from the database.
     *
     * @throws InvalidSlotNumberException  invalid slot number
     * @throws InvalidTupleSizeException   invalid tuple size
     * @throws FileAlreadyDeletedException file is deleted already
     * @throws HFBufMgrException           exception thrown from bufmgr layer
     * @throws HFDiskMgrException          exception thrown from diskmgr layer
     * @throws IOException                 I/O errors
     */
    public void deleteFile()
            throws InvalidSlotNumberException,
            FileAlreadyDeletedException,
            InvalidTupleSizeException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException {
        if (_file_deleted)
            throw new FileAlreadyDeletedException(null, "file alread deleted");


        // Mark the deleted flag (even if it doesn't get all the way done).
        _file_deleted = true;

        // Deallocate all data pages
        PageId currentDirPageId = new PageId();
        currentDirPageId.pid = _firstDirPageId.pid;
        PageId nextDirPageId = new PageId();
        nextDirPageId.pid = 0;
        Page pageinbuffer = new Page();
        HFPage currentDirPage = new HFPage();
        Map aMap;

        pinPage(currentDirPageId, currentDirPage, false);
        //currentDirPage.openHFpage(pageinbuffer);

        MID mid = new MID();
        while (currentDirPageId.pid != INVALID_PAGE) {
            for (mid = currentDirPage.firstMap();
                 mid != null;
                 mid = currentDirPage.nextMap(mid)) {
                aMap = currentDirPage.getMap(mid);
                DataPageInfo dpinfo = new DataPageInfo(aMap);
                //int dpinfoLen = aMap.length;

                freePage(dpinfo.pageId);

            }
            // ASSERTIONS:
            // - we have freePage()'d all data pages referenced by
            // the current directory page.

            nextDirPageId = currentDirPage.getNextPage();
            freePage(currentDirPageId);

            currentDirPageId.pid = nextDirPageId.pid;
            if (nextDirPageId.pid != INVALID_PAGE) {
                pinPage(currentDirPageId, currentDirPage, false);
                //currentDirPage.openHFpage(pageinbuffer);
            }
        }

        delete_file_entry(_fileName);
    }

    /**
     * short cut to access the pinPage function in bufmgr package.
     */
    private void pinPage(PageId pageno, Page page, boolean emptyPage)
            throws HFBufMgrException {

        try {
            SystemDefs.JavabaseBM.pinPage(pageno, page, emptyPage);
        } catch (Exception e) {
            throw new HFBufMgrException(e, "Heapfile.java: pinPage() failed");
        }

    } // end of pinPage

    /**
     * short cut to access the unpinPage function in bufmgr package.
     */
    private void unpinPage(PageId pageno, boolean dirty)
            throws HFBufMgrException {

        try {
            SystemDefs.JavabaseBM.unpinPage(pageno, dirty);
        } catch (Exception e) {
            throw new HFBufMgrException(e, "Heapfile.java: unpinPage() failed");
        }

    } // end of unpinPage

    private void freePage(PageId pageno)
            throws HFBufMgrException {

        try {
            SystemDefs.JavabaseBM.freePage(pageno);
        } catch (Exception e) {
            throw new HFBufMgrException(e, "Heapfile.java: freePage() failed");
        }

    } // end of freePage

    private PageId newPage(Page page, int num)
            throws HFBufMgrException {

        PageId tmpId = new PageId();

        try {
            tmpId = SystemDefs.JavabaseBM.newPage(page, num);
        } catch (Exception e) {
            throw new HFBufMgrException(e, "Heapfile.java: newPage() failed");
        }

        return tmpId;

    } // end of newPage

    private PageId get_file_entry(String filename)
            throws HFDiskMgrException {

        PageId tmpId = new PageId();

        try {
            tmpId = SystemDefs.JavabaseDB.get_file_entry(filename);
        } catch (Exception e) {
            throw new HFDiskMgrException(e, "Heapfile.java: get_file_entry() failed");
        }

        return tmpId;

    } // end of get_file_entry

    private void add_file_entry(String filename, PageId pageno)
            throws HFDiskMgrException {

        try {
            SystemDefs.JavabaseDB.add_file_entry(filename, pageno);
        } catch (Exception e) {
            e.printStackTrace();
            throw new HFDiskMgrException(e, "Heapfile.java: add_file_entry() failed");
        }

    } // end of add_file_entry

    private void delete_file_entry(String filename)
            throws HFDiskMgrException {

        try {
            SystemDefs.JavabaseDB.delete_file_entry(filename);
        } catch (Exception e) {
            throw new HFDiskMgrException(e, "Heapfile.java: delete_file_entry() failed");
        }

    } // end of delete_file_entry


}// End of HeapFile
