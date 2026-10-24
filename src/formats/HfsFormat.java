/**
 * An empty HFS volume as the Disk Initialization Package's DIZero writes it (Inside Macintosh: Files, "Data Organization on
 * Volumes"): boot blocks left alone, the master directory block in sector 2 and its copy in the next-to-last sector, the
 * volume bitmap from sector 3, then the extents overflow and catalog B-trees at the start of the allocation blocks - the
 * extents tree empty, the catalog holding the root folder and its thread. Built from the published format only.
 * <p>
 * {@link #sectors} lists what has to be written (the rest of the disk is not read until files are added).
 */
final class HfsFormat {
	static final int SECTOR=512,NODE=512;

	/** The sectors to write: {@code [sector number, 512 bytes]...} in increasing order. */
	final java.util.List<Object[]> sectors=new java.util.ArrayList<Object[]>();
	final int allocationBlockSize,allocationBlocks,firstAllocationSector,extentsBytes,catalogBytes;

	/**
	 * @param totalSectors the disk's size in 512-byte sectors (at least 800K's 1600 is usual; 64 is the floor here)
	 * @param name the volume name (Mac bytes, at most 27)
	 * @param macDate the creation date in Mac seconds
	 */
	HfsFormat(long totalSectors,byte[] name,int macDate){
		if(totalSectors<64)throw new IllegalArgumentException("disk too small for HFS: "+totalSectors+" sectors");
		if(name.length>27)name=java.util.Arrays.copyOf(name,27);
		long totalBytes=totalSectors*SECTOR;
		// allocation blocks: the smallest multiple of 512 bytes that keeps their number within 16 bits
		int blockSize=SECTOR;while((totalSectors-8)*SECTOR/blockSize>0xffff)blockSize+=SECTOR;
		allocationBlockSize=blockSize;
		int roughBlocks=(int)Math.min(0xffff,(totalSectors-8)*SECTOR/blockSize);
		int bitmapSectors=Math.max(1,(roughBlocks+SECTOR*8-1)/(SECTOR*8));
		firstAllocationSector=3+bitmapSectors;
		// the last two sectors hold the alternate MDB and a spare one, outside the allocation blocks
		allocationBlocks=(int)Math.min(0xffff,(totalSectors-firstAllocationSector-2)*SECTOR/blockSize);
		// the B-trees: about 1/128 of the volume for the catalog and half that for the extents, at least 4 and 8 nodes, at most the
		// 2048 nodes one header node's map record keeps track of (no map nodes needed)
		extentsBytes=roundUp(Math.min(2048L*NODE,Math.max(4L*NODE,totalBytes/256)),blockSize);
		catalogBytes=roundUp(Math.min(2048L*NODE,Math.max(8L*NODE,totalBytes/128)),blockSize);
		int extentsBlocks=extentsBytes/blockSize,catalogBlocks=catalogBytes/blockSize,used=extentsBlocks+catalogBlocks;
		if(used>=allocationBlocks)throw new IllegalArgumentException("disk too small for HFS: "+totalSectors+" sectors");

		byte[] mdb=new byte[SECTOR];
		u2(mdb,0,0x4244);                               // drSigWord 'BD'
		u4(mdb,2,macDate);u4(mdb,6,macDate);            // drCrDate, drLsMod
		u2(mdb,10,0x0100);                              // drAtrb: unmounted cleanly
		u2(mdb,12,0);                                   // drNmFls (root folder files)
		u2(mdb,14,3);                                   // drVBMSt
		u2(mdb,16,used);                                // drAllocPtr: where the next allocation search starts
		u2(mdb,18,allocationBlocks);                    // drNmAlBlks
		u4(mdb,20,blockSize);                           // drAlBlkSiz
		u4(mdb,24,blockSize*4);                         // drClpSiz
		u2(mdb,28,firstAllocationSector);               // drAlBlSt
		u4(mdb,30,16);                                  // drNxtCNID: the first ID after the reserved ones
		u2(mdb,34,allocationBlocks-used);               // drFreeBks
		mdb[36]=(byte)name.length;System.arraycopy(name,0,mdb,37,name.length); // drVN
		u4(mdb,74,extentsBytes);u4(mdb,78,catalogBytes); // drXTClpSiz, drCTClpSiz
		u4(mdb,130,extentsBytes);u2(mdb,134,0);u2(mdb,136,extentsBlocks);           // drXTFlSize, drXTExtRec[0]
		u4(mdb,146,catalogBytes);u2(mdb,150,extentsBlocks);u2(mdb,152,catalogBlocks); // drCTFlSize, drCTExtRec[0]
		add(2,mdb);

		byte[] bitmap=new byte[bitmapSectors*SECTOR];
		for(int b=0;b<used;b++)bitmap[b>>>3]|=(byte)(0x80>>>(b&7));
		for(int s=0;s<bitmapSectors;s++)add(3+s,java.util.Arrays.copyOfRange(bitmap,s*SECTOR,(s+1)*SECTOR));

		long extentsStart=firstAllocationSector,catalogStart=firstAllocationSector+(long)extentsBlocks*blockSize/SECTOR;
		byte[] extents=new byte[extentsBytes];
		headerNode(extents,7,0,0,0,extentsBytes/NODE,1);
		addFile(extentsStart,extents);

		byte[] catalog=new byte[catalogBytes];
		headerNode(catalog,37,1,1,2,catalogBytes/NODE,2);
		// node 1: the leaf with the root folder (key: parent 1, the volume's name) and its thread (key: parent 2, no name)
		int leaf=NODE;
		u4(catalog,leaf,0);u4(catalog,leaf+4,0);catalog[leaf+8]=(byte)0xff;catalog[leaf+9]=1;u2(catalog,leaf+10,2);
		int at=leaf+14;int first=at;
		int keyLength=6+name.length;catalog[at]=(byte)keyLength;u4(catalog,at+2,1);catalog[at+6]=(byte)name.length;System.arraycopy(name,0,catalog,at+7,name.length);
		at+=keyLength+1;if((at&1)!=0)at++;
		catalog[at]=1;                                  // cdrDirRec
		u2(catalog,at+4,0);u4(catalog,at+6,2);          // dirVal, dirDirID
		u4(catalog,at+10,macDate);u4(catalog,at+14,macDate); // dirCrDat, dirMdDat
		at+=70;int second=at;
		catalog[at]=6;u4(catalog,at+2,2);               // key: parent 2, empty name
		at+=8;
		catalog[at]=3;                                  // cdrThdRec
		u4(catalog,at+10,1);catalog[at+14]=(byte)name.length;System.arraycopy(name,0,catalog,at+15,name.length); // thdParID, thdCName
		at+=46;
		u2(catalog,leaf+NODE-2,first-leaf);u2(catalog,leaf+NODE-4,second-leaf);u2(catalog,leaf+NODE-6,at-leaf);
		addFile(catalogStart,catalog);

		add(totalSectors-2,mdb.clone());                 // the alternate MDB
		sectors.sort((a,b)->Long.compare((Long)a[0],(Long)b[0]));
	}

	/** Node 0 of a B-tree file: the header record, the 128 reserved bytes and the map record with the first {@code usedNodes} set. */
	private static void headerNode(byte[] file,int keyLength,int depth,int root,int records,int nodes,int usedNodes){
		file[8]=1;u2(file,10,3);                        // ndType header, ndNRecs 3
		int h=14;
		u2(file,h,depth);u4(file,h+2,root);u4(file,h+6,records);u4(file,h+10,root);u4(file,h+14,root); // bthDepth, bthRoot, bthNRecs, bthFNode, bthLNode
		u2(file,h+18,NODE);u2(file,h+20,keyLength);u4(file,h+22,nodes);u4(file,h+26,nodes-usedNodes);   // bthNodeSize, bthKeyLen, bthNNodes, bthFree
		int map=14+106+128;
		for(int n=0;n<usedNodes;n++)file[map+(n>>>3)]|=(byte)(0x80>>>(n&7));
		u2(file,NODE-2,14);u2(file,NODE-4,14+106);u2(file,NODE-6,map);u2(file,NODE-8,NODE-8);
	}
	private void addFile(long firstSector,byte[] bytes){for(int s=0;s*SECTOR<bytes.length;s++)add(firstSector+s,java.util.Arrays.copyOfRange(bytes,s*SECTOR,(s+1)*SECTOR));}
	private void add(long sector,byte[] bytes){sectors.add(new Object[]{sector,bytes});}
	private static int roundUp(long n,int unit){return (int)((n+unit-1)/unit*unit);}
	private static void u2(byte[] b,int at,int v){b[at]=(byte)(v>>>8);b[at+1]=(byte)v;}
	private static void u4(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}

	/** The whole volume as bytes (tests; the unlisted sectors zero). */
	byte[] image(long totalSectors){
		byte[] disk=new byte[(int)(totalSectors*SECTOR)];
		for(Object[] s:sectors)System.arraycopy((byte[])s[1],0,disk,(int)((Long)s[0]*SECTOR),SECTOR);
		return disk;
	}
}
