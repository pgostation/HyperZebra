import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Movies a program makes with the native Movie Toolbox (CreateMovieFile, NewMovieTrack, NewTrackMedia, AddMediaSample,
 * InsertMediaIntoTrack) and the movie files written from them: a 'moov' resource beside the samples in the data fork
 * (AddMovieResource) or a self-contained data fork ('mdat' then 'moov', FlattenMovieData). The atoms are the QuickTime File
 * Format's version 0 ones that QuickTime 2 writes - mvhd, trak (tkhd, edts/elst, mdia: mdhd, hdlr, minf: vmhd/smhd, hdlr,
 * dinf/dref, stbl: stsd, stts, stsc, stsz, stco) - so QuickTimeFile, HyperZebra's own reader, and QuickTime both play them.
 * UxCardToMovie makes a movie of the cards ('smc ' samples) this way; MyMovieTool flattens a movie into a 'myMV' resource.
 */
final class MovieWriter68k {
	static final int MOVIE_TIME_SCALE=600;
	static final int VIDE=0x76696465,SOUN=0x736f756e,ALIS=0x616c6973;

	/** Samples added in one AddMediaSample: {@code count} samples of {@code size / count} bytes each at {@code offset} of {@code file}. */
	static final class Chunk {
		final File file;final long offset;final int size,count,duration,description;final boolean sync;
		Chunk(File file,long offset,int size,int count,int duration,int description,boolean sync){this.file=file;this.offset=offset;this.size=size;this.count=Math.max(1,count);this.duration=duration;this.description=description;this.sync=sync;}
	}
	static final class MediaData {
		final int type,timeScale;File dataFile;
		final List<byte[]> descriptions=new ArrayList<byte[]>();final List<Chunk> chunks=new ArrayList<Chunk>();
		MediaData(int type,int timeScale,File dataFile){this.type=type;this.timeScale=timeScale>0?timeScale:MOVIE_TIME_SCALE;this.dataFile=dataFile;}
		long duration(){long d=0;for(Chunk c:chunks)d+=(long)c.count*c.duration;return d;}
		int sampleCount(){int n=0;for(Chunk c:chunks)n+=c.count;return n;}
		/** The 1-based index of {@code description} among the media's sample descriptions (added when new). */
		int describe(byte[] description){
			for(int i=0;i<descriptions.size();i++)if(Arrays.equals(descriptions.get(i),description))return i+1;
			descriptions.add(description);return descriptions.size();
		}
	}
	/** An edit: {@code duration} in movie time showing the media from {@code mediaTime} (-1: nothing) at {@code rate}. */
	static final class EditData {final long duration,mediaTime;final int rate;EditData(long duration,long mediaTime,int rate){this.duration=duration;this.mediaTime=mediaTime;this.rate=rate;}}
	static final class TrackData {
		final int id,width,height,volume;MediaData media;final List<EditData> edits=new ArrayList<EditData>();
		TrackData(int id,int width,int height,int volume){this.id=id;this.width=width;this.height=height;this.volume=volume;}
		long duration(){long d=0;for(EditData e:edits)d+=e.duration;return d;}
	}
	static final class MovieData {
		int timeScale=MOVIE_TIME_SCALE;final File file; // the movie file (its data fork takes the samples of media without a data reference)
		final List<TrackData> tracks=new ArrayList<TrackData>();int nextTrackId=1;
		MovieData(File file){this.file=file;}
		long duration(){long d=0;for(TrackData t:tracks)d=Math.max(d,t.duration());return d;}
		int[] naturalSize(){int w=0,h=0;for(TrackData t:tracks)if(t.media!=null&&t.media.type==VIDE){w=Math.max(w,t.width>>16);h=Math.max(h,t.height>>16);}return new int[]{w,h};}
	}

	private MovieWriter68k(){}

	// ------------------------------------------------------------------ the samples

	/** A new movie file: an empty data fork that starts with an 'mdat' atom header (its size is set by {@link #closeData}). */
	static void startData(File file) throws IOException {
		try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){raf.setLength(0);raf.write(new byte[]{0,0,0,8,'m','d','a','t'});}
	}
	/** Appends {@code data} to the data fork of {@code file}; its offset there. */
	static long append(File file,byte[] data,int from,int length) throws IOException {
		try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){long at=raf.length();if(at==0){raf.write(new byte[]{0,0,0,8,'m','d','a','t'});at=8;}raf.seek(at);raf.write(data,from,length);return at;}
	}
	/** The 'mdat' atom header at the start of {@code file} covers everything after it. */
	static void closeData(File file) throws IOException {
		if(file==null||!file.isFile()||file.length()<8)return;
		try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){byte[] h=new byte[8];raf.readFully(h);if(h[4]=='m'&&h[5]=='d'&&h[6]=='a'&&h[7]=='t'){long n=raf.length();raf.seek(0);raf.writeInt(n>0xffffffffL?0:(int)n);}}
	}
	private static byte[] read(File file,long offset,int length) throws IOException {
		try(RandomAccessFile raf=new RandomAccessFile(file,"r")){byte[] b=new byte[length];raf.seek(offset);raf.readFully(b);return b;}
	}

	// ------------------------------------------------------------------ writing movies

	/**
	 * The movie atom of {@code m}: samples in {@code m.file}'s data fork are self references (the file the movie resource or atom
	 * is in), others alias references made by {@code alias}; {@code offsets} maps a chunk to where its bytes are (null = as added).
	 */
	static byte[] moov(MovieData m,java.util.Map<Chunk,Long> offsets,File self,java.util.function.Function<File,byte[]> alias){
		int stamp=(int)Toolbox68k.macNow(); // GetDateTime's clock, as QuickTime stamps a movie
		ByteArrayOutputStream body=new ByteArrayOutputStream();
		// mvhd
		Atom mvhd=new Atom("mvhd");mvhd.u4(0).u4(stamp).u4(stamp).u4(m.timeScale).u4((int)m.duration()).u4(0x10000).u2(0x100).zeros(10).matrix().u4(0).u4(0).u4(0).u4(0).u4(0).u4(0).u4(m.nextTrackId);
		mvhd.into(body);
		for(TrackData t:m.tracks){
			if(t.media==null)continue;
			MediaData media=t.media;boolean video=media.type==VIDE;
			Atom trak=new Atom("trak");
			Atom tkhd=new Atom("tkhd");tkhd.u4(0x0f).u4(stamp).u4(stamp).u4(t.id).u4(0).u4((int)t.duration()).zeros(8).u2(0).u2(0).u2(video?0:t.volume).u2(0).matrix().u4(video?t.width:0).u4(video?t.height:0);
			trak.child(tkhd);
			Atom elst=new Atom("elst");elst.u4(0).u4(t.edits.size());for(EditData e:t.edits)elst.u4((int)e.duration).u4((int)e.mediaTime).u4(e.rate);
			trak.child(new Atom("edts").child(elst));
			Atom mdia=new Atom("mdia");
			mdia.child(new Atom("mdhd").u4(0).u4(stamp).u4(stamp).u4(media.timeScale).u4((int)media.duration()).u2(0).u2(0));
			mdia.child(handler(0x6d686c72,media.type,video?"Apple Video Media Handler":"Apple Sound Media Handler"));
			Atom minf=new Atom("minf");
			if(video)minf.child(new Atom("vmhd").u4(1).u2(0x40).u2(0x8000).u2(0x8000).u2(0x8000));
			else minf.child(new Atom("smhd").u4(0).u2(0).u2(0));
			minf.child(handler(0x64686c72,ALIS,"Apple Alias Data Handler"));
			// the data reference: this file's data fork, or an alias to the media's own data file
			File data=media.dataFile;Atom dref=new Atom("dref").u4(0).u4(1);
			boolean selfRef=data==null||self!=null&&sameFile(data,self);
			byte[] aliasBytes=selfRef||alias==null?null:alias.apply(data);
			if(selfRef||aliasBytes==null)dref.u4(12).u4(ALIS).u4(1);
			else{dref.u4(12+aliasBytes.length).u4(ALIS).u4(0).bytes(aliasBytes);}
			minf.child(new Atom("dinf").child(dref));
			Atom stbl=new Atom("stbl");
			Atom stsd=new Atom("stsd").u4(0).u4(media.descriptions.size());
			for(byte[] d:media.descriptions){byte[] e=d.clone();if(e.length>=16){e[14]=0;e[15]=1;}stsd.bytes(e);} // dataRefIndex 1
			stbl.child(stsd);
			// time to sample: runs of samples of one duration
			List<long[]> stts=new ArrayList<long[]>();
			for(Chunk c:media.chunks){long[] last=stts.isEmpty()?null:stts.get(stts.size()-1);if(last!=null&&last[1]==c.duration)last[0]+=c.count;else stts.add(new long[]{c.count,c.duration});}
			Atom a=new Atom("stts").u4(0).u4(stts.size());for(long[] r:stts)a.u4((int)r[0]).u4((int)r[1]);stbl.child(a);
			// sync samples, when some are not
			boolean allSync=true;for(Chunk c:media.chunks)if(!c.sync)allSync=false;
			if(!allSync){List<Integer> sync=new ArrayList<Integer>();int n=1;for(Chunk c:media.chunks){if(c.sync)sync.add(n);n+=c.count;}
				Atom stss=new Atom("stss").u4(0).u4(sync.size());for(int s:sync)stss.u4(s);stbl.child(stss);}
			// sample to chunk: runs of chunks with the same sample count and description
			List<int[]> stsc=new ArrayList<int[]>();
			for(int i=0;i<media.chunks.size();i++){Chunk c=media.chunks.get(i);int[] last=stsc.isEmpty()?null:stsc.get(stsc.size()-1);if(last==null||last[1]!=c.count||last[2]!=c.description)stsc.add(new int[]{i+1,c.count,c.description});}
			a=new Atom("stsc").u4(0).u4(stsc.size());for(int[] r:stsc)a.u4(r[0]).u4(r[1]).u4(r[2]);stbl.child(a);
			// sample sizes: one size for all when they agree
			int common=-1;for(Chunk c:media.chunks){int s=c.size/c.count;if(common==-1)common=s;else if(common!=s){common=-2;break;}}
			a=new Atom("stsz").u4(0);
			if(common>=0)a.u4(common).u4(media.sampleCount());
			else{a.u4(0).u4(media.sampleCount());for(Chunk c:media.chunks)for(int k=0;k<c.count;k++)a.u4(c.size/c.count);}
			stbl.child(a);
			a=new Atom("stco").u4(0).u4(media.chunks.size());for(Chunk c:media.chunks){Long o=offsets==null?null:offsets.get(c);a.u4((int)(o!=null?o:c.offset));}stbl.child(a);
			minf.child(stbl);
			mdia.child(minf);
			trak.child(mdia);
			trak.into(body);
		}
		byte[] content=body.toByteArray();
		return atom("moov",content);
	}
	private static Atom handler(int componentType,int subType,String name){
		byte[] n=name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		return new Atom("hdlr").u4(0).u4(componentType).u4(subType).u4(0x6170706c).u4(0).u4(0).u1(n.length).bytes(n);
	}
	private static boolean sameFile(File a,File b){try{return a.getCanonicalFile().equals(b.getCanonicalFile());}catch(IOException e){return a.equals(b);}}

	/**
	 * FlattenMovieData of a movie made here: {@code out}'s data fork becomes an 'mdat' atom holding every sample of the movie,
	 * then the 'moov' atom pointing into it (self references only).
	 */
	static void flatten(MovieData m,File out) throws IOException {
		ByteArrayOutputStream samples=new ByteArrayOutputStream();java.util.Map<Chunk,Long> offsets=new java.util.IdentityHashMap<Chunk,Long>();
		for(TrackData t:m.tracks)if(t.media!=null)for(Chunk c:t.media.chunks){offsets.put(c,8L+samples.size());byte[] b=read(c.file,c.offset,c.size);samples.write(b,0,b.length);}
		MovieData flat=new MovieData(out);flat.nextTrackId=m.nextTrackId;
		for(TrackData t:m.tracks){TrackData copy=new TrackData(t.id,t.width,t.height,t.volume);copy.edits.addAll(t.edits);
			if(t.media!=null){copy.media=new MediaData(t.media.type,t.media.timeScale,out);copy.media.descriptions.addAll(t.media.descriptions);copy.media.chunks.addAll(t.media.chunks);}
			flat.tracks.add(copy);}
		byte[] moov=moov(flat,offsets,out,null);
		try(java.io.FileOutputStream o=new java.io.FileOutputStream(out)){
			byte[] head=new byte[8];put4(head,0,8+samples.size());head[4]='m';head[5]='d';head[6]='a';head[7]='t';
			o.write(head);samples.writeTo(o);o.write(moov);
		}
	}

	/**
	 * FlattenMovieData of a movie read from {@code source}: a movie whose 'moov' is an atom of the data fork is that data fork
	 * already; one whose 'moov' is a resource gets its data fork wrapped in an 'mdat' atom and the movie atom after it, its chunk
	 * offsets moved by the 8 bytes of the header (MyMovieTool keeps such movies as 'myMV' resources). False when the source is
	 * no QuickTime movie.
	 */
	static boolean flattenFile(File source,File out) throws IOException {
		QuickTimeFile q;try{q=QuickTimeFile.open(source);}catch(IOException|RuntimeException e){return false;}
		try{
			long length=q.data().length();if(length>Integer.MAX_VALUE-16)return false;
			byte[] data=length>0?q.data().read(0,(int)length):new byte[0];
			if(q.source!=null&&q.source.startsWith("data fork")){java.nio.file.Files.write(out.toPath(),data);return true;}
			byte[] moov=q.moov.clone();
			boolean wrapped=data.length>=8&&data[4]=='m'&&data[5]=='d'&&data[6]=='a'&&data[7]=='t';
			int shift=wrapped?0:8;
			if(shift!=0)moveChunks(moov,0,moov.length,shift,0);
			try(java.io.FileOutputStream o=new java.io.FileOutputStream(out)){
				if(!wrapped){byte[] head=new byte[8];put4(head,0,(int)Math.min(0xffffffffL,8L+data.length));head[4]='m';head[5]='d';head[6]='a';head[7]='t';o.write(head);}
				o.write(data);o.write(moov);
			}
			return true;
		}finally{q.close();}
	}
	private static final java.util.Set<String> CONTAINERS=new java.util.HashSet<String>(Arrays.asList("moov","trak","mdia","minf","stbl","dinf","edts","udta"));
	/** Adds {@code shift} to every chunk offset (stco, co64) in the atoms of {@code b} between {@code at} and {@code end}. */
	private static void moveChunks(byte[] b,int at,int end,int shift,int depth){
		while(at+8<=end&&depth<10){
			int size=u4(b,at);String type=new String(b,at+4,4,java.nio.charset.StandardCharsets.ISO_8859_1);
			if(size<8||at+size>end)return;
			if(type.equals("moov")&&depth==0||CONTAINERS.contains(type))moveChunks(b,at+8,at+size,shift,depth+1);
			else if(type.equals("stco")&&size>=16){int n=u4(b,at+12);for(int i=0;i<n&&at+16+i*4+4<=at+size;i++){int o=at+16+i*4;put4(b,o,u4(b,o)+shift);}}
			else if(type.equals("co64")&&size>=16){int n=u4(b,at+12);for(int i=0;i<n&&at+16+i*8+8<=at+size;i++){int o=at+16+i*8;long v=((long)u4(b,o)<<32|(u4(b,o+4)&0xffffffffL))+shift;put4(b,o,(int)(v>>>32));put4(b,o+4,(int)v);}}
			at+=size;
		}
	}
	private static byte[] atom(String type,byte[] content){byte[] b=new byte[8+content.length];put4(b,0,b.length);for(int i=0;i<4;i++)b[4+i]=(byte)type.charAt(i);System.arraycopy(content,0,b,8,content.length);return b;}
	static int u4(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
	static void put4(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}

	/** An atom being built: its fields, then its child atoms. */
	private static final class Atom {
		final String type;final ByteArrayOutputStream b=new ByteArrayOutputStream();
		Atom(String type){this.type=type;}
		Atom u1(int v){b.write(v);return this;}
		Atom u2(int v){b.write(v>>8);b.write(v);return this;}
		Atom u4(int v){b.write(v>>>24);b.write(v>>>16);b.write(v>>>8);b.write(v);return this;}
		Atom zeros(int n){for(int i=0;i<n;i++)b.write(0);return this;}
		Atom bytes(byte[] d){b.write(d,0,d.length);return this;}
		Atom matrix(){return u4(0x10000).u4(0).u4(0).u4(0).u4(0x10000).u4(0).u4(0).u4(0).u4(0x40000000);}
		Atom child(Atom a){a.into(b);return this;}
		void into(ByteArrayOutputStream out){byte[] c=b.toByteArray();byte[] h=new byte[8];put4(h,0,8+c.length);for(int i=0;i<4;i++)h[4+i]=(byte)type.charAt(i);out.write(h,0,8);out.write(c,0,c.length);}
	}
}
