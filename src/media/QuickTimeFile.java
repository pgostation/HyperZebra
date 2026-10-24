import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A QuickTime movie file read without QuickTime: the movie resource ('moov' atom) and everything the Movie Toolbox
 * derives from it - tracks, edit lists, media, data references, sample descriptions and the sample tables - so that
 * HyperZebra's own decoders can find every sample's bytes and time.
 *
 * Where the movie lives (QuickTime File Format, "Movie Resource" / "Movie Atoms"):
 * <ul>
 * <li>classic single-fork-data movies keep the 'moov' atom as a 'moov' resource in the resource fork (the first one
 *     unless an id is asked for) and the samples in the data fork (the data fork may or may not start with an 'mdat'
 *     atom: very old movies are raw sample data);</li>
 * <li>flattened movies keep 'moov' as a top-level atom of the data fork (any order with 'mdat', 'wide', 'free',
 *     'skip', 'pnot', 'PICT'...); several movies can share one data fork, picked by offset;</li>
 * <li>a compressed movie resource ('cmov': 'dcom' zlib + 'cmvd') is expanded with java.util.zip.</li>
 * </ul>
 * Sample offsets are absolute positions in the file a data reference names: the data fork of this file for a
 * self reference, another file for an alias ('alis') reference (resolved by the host path HyperZebra writes into its
 * own aliases, then by name next to this movie).
 *
 * The derived values follow what the Movie Toolbox reports for the same file (GetMovieBox, GetTrackOffset,
 * GetMediaSampleReference...): tools/qt/QtContainerOracle dumps a movie through the installed QuickTime in the format
 * of {@link #describe}, and the two dumps are compared.
 */
final class QuickTimeFile {
	static final int MOOV=fourcc("moov"),MDAT=fourcc("mdat"),CMOV=fourcc("cmov");
	static final int VIDE=fourcc("vide"),SOUN=fourcc("soun"),MUSI=fourcc("musi"),TEXT=fourcc("text"),ALIS=fourcc("alis");
	/** tkhd flags = GetTrackUsage bits + enabled. */
	static final int TRACK_ENABLED=1,TRACK_IN_MOVIE=2,TRACK_IN_PREVIEW=4,TRACK_IN_POSTER=8;
	/** GetMediaSampleReference flags. */
	static final int SAMPLE_NOT_SYNC=1,SAMPLE_SHADOW_SYNC=2;

	final File file;
	/** Where the movie resource came from: "resource 'moov' <id>" or "data fork at <offset>", plus " (cmov)" when compressed. */
	String source;
	int timeScale;long duration;int preferredRate=0x10000;int preferredVolume=0x100;int[] matrix=identity();
	long creationTime,modificationTime;int previewTime,previewDuration,posterTime,selectionTime,selectionDuration,currentTime,nextTrackId;
	final List<Track> tracks=new ArrayList<Track>();
	/** Movie-level user data ('udta' children), raw atoms. */
	final List<Atom> userData=new ArrayList<Atom>();
	byte[] moov; // the movie atom, expanded when it was compressed

	private DataFork dataFork; // this file's data fork (opened lazily)

	/** One entry of a track's edit list: {@code duration} in movie units, {@code mediaTime} in media units (-1: empty edit). */
	static final class Edit {
		final long duration,mediaTime;final int rate;
		Edit(long duration,long mediaTime,int rate){this.duration=duration;this.mediaTime=mediaTime;this.rate=rate;}
	}

	final class Track {
		int id,flags,layer,alternateGroup,volume;long duration;int width,height; // width/height: Fixed
		int[] matrix=identity();long creationTime,modificationTime;
		/** The edit list; a track without 'edts' gets one edit spanning its whole media, as QuickTime builds it. */
		final List<Edit> edits=new ArrayList<Edit>();
		boolean explicitEdits;
		Media media;
		final List<Atom> extra=new ArrayList<Atom>(); // 'clip', 'matt', 'load', 'tref', 'imap', 'udta' ... kept raw
		/** The movie-time start of each edit (movie units). */
		long[] editStarts(){long[] s=new long[edits.size()];long t=0;for(int i=0;i<s.length;i++){s[i]=t;t+=edits.get(i).duration;}return s;}
		/** GetTrackOffset: the length of the leading empty edits. */
		long offset(){long t=0;for(Edit e:edits){if(e.mediaTime!=-1)break;t+=e.duration;}return t;}
		/** Edit-list duration in movie units (GetTrackDuration). */
		long editDuration(){long t=0;for(Edit e:edits)t+=e.duration;return t;}
		boolean enabled(){return (flags&TRACK_ENABLED)!=0;}
		boolean visual(){return width!=0&&height!=0;}
		/** TrackTimeToMediaTime: the media time at movie time {@code t}, or -1 in an empty edit / outside the track. */
		long mediaTimeAt(long t){
			long start=0;
			for(Edit e:edits){
				if(t<start+e.duration){
					if(e.mediaTime==-1)return -1;
					double scale=(double)media.timeScale/timeScale*(e.rate/65536.0);
					return e.mediaTime+(long)Math.floor((t-start)*scale);
				}
				start+=e.duration;
			}
			return -1;
		}
		QuickTimeFile movie(){return QuickTimeFile.this;}
	}

	/** A data reference ('dref' entry). */
	static final class DataRef {
		final int type,flags;final byte[] data;File file;
		DataRef(int type,int flags,byte[] data){this.type=type;this.flags=flags;this.data=data;}
		boolean self(){return (flags&1)!=0;}
	}

	/** A sample description ('stsd' entry): the raw bytes (as GetMediaSampleDescription hands them out) and its header. */
	static class SampleDescription {
		final byte[] bytes;final int format,dataRefIndex;
		SampleDescription(byte[] bytes){this.bytes=bytes;format=u4(bytes,4);dataRefIndex=u2(bytes,14);}
		/** Extension atoms after the fixed part (and colour table): the 'avcC', 'gama', 'wave' ... */
		List<Atom> extensions(int start){
			List<Atom> list=new ArrayList<Atom>();int p=start;
			while(p+8<=bytes.length){long size=u4(bytes,p)&0xffffffffL;if(size<8||p+size>bytes.length)break;list.add(new Atom(u4(bytes,p+4),p,(int)size,8));p+=(int)size;}
			return list;
		}
	}

	/** ImageDescription (QuickTime File Format, "Video Sample Description"). */
	static final class VideoDescription extends SampleDescription {
		final int version,revision,vendor,temporalQuality,spatialQuality,width,height,hRes,vRes,dataSize,frameCount,depth,clutId;
		final String name;
		/** The colour table stored in the description (clutID 0 with an indexed depth), ARGB; null otherwise. */
		final int[] colorTable;final int extensionsAt;
		VideoDescription(byte[] b){
			super(b);
			version=u2(b,16);revision=u2(b,18);vendor=u4(b,20);temporalQuality=u4(b,24);spatialQuality=u4(b,28);width=u2(b,32);height=u2(b,34);
			hRes=u4(b,36);vRes=u4(b,40);dataSize=u4(b,44);frameCount=u2(b,48);
			int n=Math.min(31,b[50]&0xff);name=new String(b,51,n,StandardCharsets.ISO_8859_1);
			depth=(short)u2(b,82);clutId=(short)u2(b,84);
			int at=86;int[] table=null;
			int bits=depth>32?depth-32:depth;
			if(clutId==0&&bits>=1&&bits<=8&&b.length>=at+8){
				int count=(short)u2(b,at+6)+1;
				if(count>0&&at+8+count*8<=b.length){
					table=new int[count];
					for(int i=0;i<count;i++){int e=at+8+i*8;table[i]=0xff000000|(b[e+2]&0xff)<<16|(b[e+4]&0xff)<<8|(b[e+6]&0xff);}
					at+=8+count*8;
				}
			}
			colorTable=table;extensionsAt=at;
		}
		/** Grey (depth 33..40 = 1..8-bit grey) or colour. */
		boolean grey(){return depth>32;}
	}

	/** SoundDescription v0/v1/v2 (QuickTime File Format, "Sound Sample Description"). */
	static final class SoundDescription extends SampleDescription {
		final int version,revision,vendor,channels,sampleSize,compressionId,packetSize;final double sampleRate;
		/** v1 (and derived for v0): samples per packet, bytes per packet (one channel), bytes per frame (all channels), bytes per sample. */
		final int samplesPerPacket,bytesPerPacket,bytesPerFrame,bytesPerSample;
		final int formatFlags;final int extensionsAt;
		SoundDescription(byte[] b){
			super(b);
			version=u2(b,16);revision=u2(b,18);vendor=u4(b,20);
			int ch=u2(b,24),size=u2(b,26);compressionId=(short)u2(b,28);packetSize=u2(b,30);
			double rate=(u4(b,32)&0xffffffffL)/65536.0;
			int spp,bpp,bpf,bps,flags=0,at=36;
			if(version==2&&b.length>=72){
				rate=Double.longBitsToDouble((long)u4(b,40)<<32|(u4(b,44)&0xffffffffL));ch=u4(b,48);size=u4(b,56);flags=u4(b,60);
				bpf=u4(b,64);spp=u4(b,68);bpp=spp>0&&ch>0?bpf/ch:bpf;bps=(size+7)/8;at=72;
			}else if(version==1&&b.length>=52&&u4(b,36)!=0&&u4(b,44)!=0){
				spp=u4(b,36);bpp=u4(b,40);bpf=u4(b,44);bps=u4(b,48);at=52;
			}else{ // version 0, or a version 1 whose packet fields were left zero (QuickTime then uses the version 0 rules)
				if(version==1&&b.length>=52)at=52;int[] d=derived(format,ch,size);spp=d[0];bpp=d[1];bpf=d[2];bps=d[3];}
			channels=ch;sampleSize=size;sampleRate=rate;samplesPerPacket=spp;bytesPerPacket=bpp;bytesPerFrame=bpf;bytesPerSample=bps;formatFlags=flags;extensionsAt=at;
		}
		/** The packet geometry of a version-0 description, which leaves it implied by the format. */
		static int[] derived(int format,int channels,int sampleSize){
			int ch=Math.max(1,channels);
			switch(typeName(format)){
				case "MAC3":return new int[]{6,2,2*ch,2};
				case "MAC6":return new int[]{6,1,ch,2};
				case "ima4":return new int[]{64,34,34*ch,2};
				case "ulaw":case "alaw":return new int[]{1,1,ch,2};
				case "fl32":return new int[]{1,4,4*ch,4};
				case "fl64":return new int[]{1,8,8*ch,8};
				case "in24":return new int[]{1,3,3*ch,3};
				case "in32":return new int[]{1,4,4*ch,4};
				default:{int bytes=Math.max(1,(sampleSize+7)/8);return new int[]{1,bytes,bytes*ch,bytes};}
			}
		}
		/** Bytes of {@code samples} consecutive samples (audio frames) stored in one chunk. */
		long bytesFor(long samples){
			if(samplesPerPacket<=1)return samples*bytesPerFrame;
			return (samples+samplesPerPacket-1)/samplesPerPacket*bytesPerFrame;
		}
	}

	final class Media {
		final Track track;
		int handlerType,handlerManufacturer;String handlerName="";
		int dataHandlerSubtype;String dataHandlerName="";
		int timeScale;long duration;int language,quality;long creationTime,modificationTime;
		/** The media information header: 'vmhd' / 'smhd' / 'gmhd' ... */
		Atom mediaInfoHeader;
		final List<DataRef> dataRefs=new ArrayList<DataRef>();
		final List<SampleDescription> descriptions=new ArrayList<SampleDescription>();
		// sample tables
		int[] sttsCount=new int[0],sttsDelta=new int[0];
		int[] cttsCount=new int[0],cttsOffset=new int[0];
		int[] syncSamples; // 1-based, ascending; null = every sample is a sync sample
		int[] shadowSync; // pairs (frameDiffSampleNum, syncSampleNum)
		int[] stscFirst=new int[0],stscPerChunk=new int[0],stscDesc=new int[0];
		int constantSize;int[] sizes;int sampleCount;
		long[] chunkOffsets=new long[0];
		// derived
		private long[] chunkFirstSample; // 0-based first sample of each chunk
		private int[] chunkDesc;
		private long[] sttsStartSample,sttsStartTime;
		private long[] sampleOffsetCache;

		Media(Track track){this.track=track;}

		boolean sound(){return handlerType==SOUN;}
		boolean video(){return handlerType==VIDE;}
		int chunkCount(){return chunkOffsets.length;}

		/** The description (1-based index as stored in stsc). */
		SampleDescription description(int index){return index>=1&&index<=descriptions.size()?descriptions.get(index-1):null;}

		void derive(){
			int chunks=chunkOffsets.length;chunkFirstSample=new long[chunks+1];chunkDesc=new int[chunks];
			long sample=0;int map=0;
			for(int c=0;c<chunks;c++){
				while(map+1<stscFirst.length&&stscFirst[map+1]<=c+1)map++;
				chunkFirstSample[c]=sample;
				int per=stscFirst.length==0?0:stscPerChunk[map];chunkDesc[c]=stscFirst.length==0?1:stscDesc[map];
				sample+=per;
			}
			chunkFirstSample[chunks]=sample;
			sttsStartSample=new long[sttsCount.length+1];sttsStartTime=new long[sttsCount.length+1];
			long s=0,t=0;
			for(int i=0;i<sttsCount.length;i++){sttsStartSample[i]=s;sttsStartTime[i]=t;s+=sttsCount[i]&0xffffffffL;t+=(long)(sttsCount[i]&0xffffffffL)*sttsDelta[i];}
			sttsStartSample[sttsCount.length]=s;sttsStartTime[sttsCount.length]=t;
		}

		/** Samples the chunk map covers (it may disagree with stsz in damaged files; the smaller one counts). */
		int samples(){return (int)Math.min(sampleCount,chunkFirstSample[chunkFirstSample.length-1]);}

		/** The chunk (0-based) that holds sample {@code n} (0-based). */
		int chunkOf(long n){
			int lo=0,hi=chunkOffsets.length-1;
			while(lo<hi){int mid=(lo+hi+1)>>>1;if(chunkFirstSample[mid]<=n)lo=mid;else hi=mid-1;}
			while(lo+1<chunkOffsets.length&&chunkFirstSample[lo+1]<=n&&chunkFirstSample[lo+1]==chunkFirstSample[lo])lo++;
			return lo;
		}
		long chunkFirstSample(int chunk){return chunkFirstSample[chunk];}
		int chunkSamples(int chunk){return (int)(chunkFirstSample[chunk+1]-chunkFirstSample[chunk]);}
		int chunkDescription(int chunk){return chunkDesc[chunk];}

		/** The stored size of sample {@code n} (0-based) as stsz gives it. */
		int storedSize(long n){return sizes==null?constantSize:sizes[(int)n];}

		/**
		 * The byte size of one sample: stsz's for video and other media; for sound with a constant stsz size (the usual 1)
		 * the description's bytes per frame, as QuickTime's sound media treats sound samples.
		 */
		int sampleSize(long n,SampleDescription d){
			if(sound()&&sizes==null&&d instanceof SoundDescription){SoundDescription sd=(SoundDescription)d;if(sd.samplesPerPacket<=1)return sd.bytesPerFrame;}
			return storedSize(n);
		}

		/** Bytes a chunk occupies in its data reference. */
		long chunkBytes(int chunk){return chunkBytes(chunk,chunkSamples(chunk));}
		/** Bytes of the first {@code count} samples of a chunk. */
		long chunkBytes(int chunk,int count){
			SampleDescription d=description(chunkDesc[chunk]);
			if(sound()&&sizes==null&&d instanceof SoundDescription)return ((SoundDescription)d).bytesFor(count);
			long total=0;long first=chunkFirstSample[chunk];for(int i=0;i<count;i++)total+=storedSize(first+i);return total;
		}

		/** The data-reference offset of sample {@code n} (0-based). */
		long sampleOffset(long n){
			int chunk=chunkOf(n);long first=chunkFirstSample[chunk];
			SampleDescription d=description(chunkDesc[chunk]);
			if(sound()&&sizes==null&&d instanceof SoundDescription){
				SoundDescription sd=(SoundDescription)d;long k=n-first;
				return chunkOffsets[chunk]+(sd.samplesPerPacket<=1?k*sd.bytesPerFrame:k/sd.samplesPerPacket*sd.bytesPerFrame);
			}
			if(sizes==null)return chunkOffsets[chunk]+(n-first)*constantSize;
			if(sampleOffsetCache==null){
				long[] cache=new long[samples()];
				for(int c=0;c<chunkOffsets.length;c++){long o=chunkOffsets[c];for(long s=chunkFirstSample[c];s<chunkFirstSample[c+1]&&s<cache.length;s++){cache[(int)s]=o;o+=storedSize(s);}}
				sampleOffsetCache=cache;
			}
			return sampleOffsetCache[(int)n];
		}

		/** The media time at which sample {@code n} (0-based) starts (decode time). */
		long sampleTime(long n){
			int i=runOf(n);if(i>=sttsCount.length)return sttsStartTime[sttsCount.length];
			return sttsStartTime[i]+(n-sttsStartSample[i])*sttsDelta[i];
		}
		long sampleDuration(long n){int i=runOf(n);return i<sttsDelta.length?sttsDelta[i]:0;}
		private int runOf(long n){
			int lo=0,hi=sttsCount.length;
			while(lo<hi){int mid=(lo+hi)>>>1;if(sttsStartSample[mid+1]<=n)lo=mid+1;else hi=mid;}
			return lo;
		}
		/** Composition (display) offset of sample {@code n} from ctts, 0 without one. */
		int compositionOffset(long n){
			long s=0;for(int i=0;i<cttsCount.length;i++){s+=cttsCount[i]&0xffffffffL;if(n<s)return cttsOffset[i];}return 0;
		}
		/** The sample (0-based) playing at media time {@code t} (MediaTimeToSampleNum), or -1 past the end. */
		long sampleAt(long t){
			if(t<0)return -1;
			int lo=0,hi=sttsCount.length-1;
			if(hi<0)return -1;
			while(lo<hi){int mid=(lo+hi+1)>>>1;if(sttsStartTime[mid]<=t)lo=mid;else hi=mid-1;}
			while(lo<sttsCount.length&&(sttsDelta[lo]==0||t>=sttsStartTime[lo+1])&&t>=sttsStartTime[lo+1])lo++;
			if(lo>=sttsCount.length)return -1;
			long n=sttsStartSample[lo]+(sttsDelta[lo]==0?0:(t-sttsStartTime[lo])/sttsDelta[lo]);
			return n<samples()?n:-1;
		}
		boolean sync(long n){
			if(syncSamples==null)return true;
			return Arrays.binarySearch(syncSamples,(int)(n+1))>=0;
		}
		/** The nearest sync sample at or before {@code n} (0-based). */
		long syncBefore(long n){
			if(syncSamples==null)return n;
			int i=Arrays.binarySearch(syncSamples,(int)(n+1));if(i<0)i=-i-2;
			return i<0?0:syncSamples[i]-1;
		}

		/** The file the samples of description {@code d} are in (null when the data reference cannot be resolved). */
		File dataFile(SampleDescription d){
			int index=d==null?1:Math.max(1,d.dataRefIndex);
			DataRef ref=index<=dataRefs.size()?dataRefs.get(index-1):null;
			if(ref==null||ref.self())return file;
			return ref.file;
		}

		/** Reads the bytes of sample {@code n} (0-based). */
		byte[] readSample(long n) throws IOException {
			SampleDescription d=description(chunkDesc[chunkOf(n)]);
			return read(dataFile(d),sampleOffset(n),sampleSize(n,d));
		}
		/** Reads a whole chunk (sound media are read chunk by chunk). */
		byte[] readChunk(int chunk) throws IOException {
			SampleDescription d=description(chunkDesc[chunk]);
			return read(dataFile(d),chunkOffsets[chunk],(int)Math.min(Integer.MAX_VALUE,chunkBytes(chunk)));
		}
	}

	/** An atom in a byte array: type, offset of its header, total size, header length (8 or 16). */
	static final class Atom {
		final int type,offset,size,header;
		Atom(int type,int offset,int size,int header){this.type=type;this.offset=offset;this.size=size;this.header=header;}
		int body(){return offset+header;}
		int end(){return offset+size;}
		String name(){return typeName(type);}
		@Override public String toString(){return name()+"@"+offset+"+"+size;}
	}

	private QuickTimeFile(File file){this.file=file;}

	// ------------------------------------------------------------------ opening

	/** Opens the movie of {@code file}: the first 'moov' resource, else the first 'moov' atom of the data fork. */
	static QuickTimeFile open(File file) throws IOException {return open(file,0);}

	/**
	 * Opens a movie: {@code resourceId} 0 = the first 'moov' resource (as NewMovieFromFile with resId 0), another id =
	 * that resource; when the file has no 'moov' resource (or the id is -1) the first 'moov' atom of the data fork.
	 */
	static QuickTimeFile open(File file,int resourceId) throws IOException {
		QuickTimeFile q=new QuickTimeFile(file);
		byte[] atom=null;String source=null;
		if(resourceId!=-1){
			ResourceFile68k rf=ResourceFile68k.open(file);
			ResourceFile68k.Resource chosen=null;
			for(ResourceFile68k.Resource r:rf.ordered)if(r.type==MOOV&&(resourceId==0||r.id==resourceId)){chosen=r;break;} // resId 0: the first in the map
			if(chosen!=null){atom=chosen.data();source="resource 'moov' "+chosen.id;}
		}
		if(atom==null){
			long at=q.data().findTopLevel(MOOV,0);
			if(at<0)throw new IOException("no movie resource in "+file.getName());
			atom=q.data().atomBytes(at);source="data fork at "+at;
		}
		q.source=source;q.parse(atom);
		return q;
	}

	/** Opens the movie atom at {@code offset} of the data fork (NewMovieFromDataFork). */
	static QuickTimeFile openDataFork(File file,long offset) throws IOException {
		QuickTimeFile q=new QuickTimeFile(file);
		q.source="data fork at "+offset;q.parse(q.data().atomBytes(offset));
		return q;
	}

	/** The offsets of every top-level 'moov' atom of the data fork (a file may hold several movies). */
	static List<Long> dataForkMovies(File file) throws IOException {
		QuickTimeFile q=new QuickTimeFile(file);List<Long> list=new ArrayList<Long>();
		long at=0;while((at=q.data().findTopLevel(MOOV,at))>=0){list.add(at);at+=Math.max(8,q.data().atomSize(at));}
		return list;
	}

	/** Whether {@code file} looks like a QuickTime movie (a 'moov' resource or a top-level 'moov' atom). */
	static boolean isMovie(File file){
		try{open(file);return true;}catch(IOException|RuntimeException e){return false;}
	}

	// ------------------------------------------------------------------ parsing the movie atom

	private void parse(byte[] atom) throws IOException {
		if(atom.length<8)throw new IOException("movie resource too short");
		Atom top=atomAt(atom,0,atom.length);
		if(top==null||top.type!=MOOV)throw new IOException("not a movie atom: "+(top==null?"?":top.name()));
		// 'cmov' inside moov: the real movie atom is zlib-compressed ('dcom' names the method, 'cmvd' holds the data)
		Atom cmov=child(atom,top,CMOV);
		if(cmov!=null){atom=decompress(atom,cmov);top=atomAt(atom,0,atom.length);source+=" (cmov)";if(top==null||top.type!=MOOV)throw new IOException("compressed movie atom is not a movie");}
		moov=atom;
		for(Atom a:children(atom,top)){
			switch(a.name()){
				case "mvhd":parseMovieHeader(atom,a);break;
				case "trak":tracks.add(parseTrack(atom,a));break;
				case "udta":userData.addAll(children(atom,a));break;
				default:break;
			}
		}
		// QuickTime 1.0 movies may carry track id 0: the Movie Toolbox gives such tracks (and duplicates) fresh ids from nextTrackID
		java.util.Set<Integer> used=new java.util.HashSet<Integer>();int next=nextTrackId;
		for(Track t:tracks)if(t.id>0)next=Math.max(next,t.id+1);
		for(Track t:tracks){if(t.id<=0||!used.add(t.id)){t.id=Math.max(1,next++);used.add(t.id);}}
	}

	private static byte[] decompress(byte[] atom,Atom cmov) throws IOException {
		Atom dcom=child(atom,cmov,fourcc("dcom")),cmvd=child(atom,cmov,fourcc("cmvd"));
		if(dcom==null||cmvd==null)throw new IOException("incomplete compressed movie atom");
		String method=typeName(u4(atom,dcom.body()));
		if(!method.equals("zlib"))throw new IOException("unsupported movie compression '"+method+"'");
		int size=u4(atom,cmvd.body());
		Inflater inflater=new Inflater();
		try{
			inflater.setInput(atom,cmvd.body()+4,cmvd.end()-cmvd.body()-4);
			byte[] out=new byte[Math.max(0,size)];int n=0;
			while(n<out.length&&!inflater.finished()){int got=inflater.inflate(out,n,out.length-n);if(got==0&&(inflater.needsInput()||inflater.needsDictionary()))break;n+=got;}
			if(n!=out.length)throw new IOException("compressed movie atom expanded to "+n+" of "+size+" bytes");
			return out;
		}catch(DataFormatException e){throw new IOException("bad compressed movie atom: "+e.getMessage());}
		finally{inflater.end();}
	}

	private void parseMovieHeader(byte[] b,Atom a){
		int p=a.body();int version=b[p]&0xff;p+=4;
		if(version==1){creationTime=u8(b,p);modificationTime=u8(b,p+8);timeScale=u4(b,p+16);duration=u8(b,p+20);p+=28;}
		else{creationTime=u4(b,p)&0xffffffffL;modificationTime=u4(b,p+4)&0xffffffffL;timeScale=u4(b,p+8);duration=u4(b,p+12)&0xffffffffL;p+=16;}
		preferredRate=u4(b,p);preferredVolume=(short)u2(b,p+4);p+=16; // + 10 reserved
		matrix=readMatrix(b,p);p+=36;
		if(p+28<=a.end()){previewTime=u4(b,p);previewDuration=u4(b,p+4);posterTime=u4(b,p+8);selectionTime=u4(b,p+12);selectionDuration=u4(b,p+16);currentTime=u4(b,p+20);nextTrackId=u4(b,p+24);}
	}

	private Track parseTrack(byte[] b,Atom trak) throws IOException {
		Track t=new Track();
		for(Atom a:children(b,trak)){
			switch(a.name()){
				case "tkhd":{
					int p=a.body();int version=b[p]&0xff;t.flags=u4(b,p)&0xffffff;p+=4;
					if(version==1){t.creationTime=u8(b,p);t.modificationTime=u8(b,p+8);t.id=u4(b,p+16);t.duration=u8(b,p+24);p+=32;}
					else{t.creationTime=u4(b,p)&0xffffffffL;t.modificationTime=u4(b,p+4)&0xffffffffL;t.id=u4(b,p+8);t.duration=u4(b,p+16)&0xffffffffL;p+=20;}
					p+=8;t.layer=(short)u2(b,p);t.alternateGroup=(short)u2(b,p+2);t.volume=(short)u2(b,p+4);p+=8;
					t.matrix=readMatrix(b,p);p+=36;t.width=u4(b,p);t.height=u4(b,p+4);
					break;
				}
				case "edts":{
					Atom elst=child(b,a,fourcc("elst"));
					if(elst!=null){
						int p=elst.body();int version=b[p]&0xff;int count=u4(b,p+4);p+=8;
						for(int i=0;i<count&&p<elst.end();i++){
							if(version==1){t.edits.add(new Edit(u8(b,p),u8(b,p+8),u4(b,p+16)));p+=20;}
							else{t.edits.add(new Edit(u4(b,p)&0xffffffffL,u4(b,p+4),u4(b,p+8)));p+=12;}
						}
						t.explicitEdits=true;
					}
					break;
				}
				case "mdia":t.media=parseMedia(b,a,t);break;
				default:t.extra.add(a);break;
			}
		}
		if(t.media==null)throw new IOException("track "+t.id+" has no media");
		if(!t.explicitEdits){
			// no edit list: the whole media plays once from movie time 0. QuickTime truncates the duration to movie units
			// and stretches the rate to fit (Minolta-MJPEG: 68799/8000 s -> 5159/600, rate 0x1000B)
			long d=t.duration;int rate=0x10000;
			if(timeScale>0&&t.media.timeScale>0){
				long scaled=t.media.duration*timeScale;d=scaled/t.media.timeScale;
				if(d>0&&scaled%t.media.timeScale!=0)rate=(int)((scaled<<16)/(d*t.media.timeScale));
			}
			t.edits.add(new Edit(d,0,rate));
		}
		return t;
	}

	private Media parseMedia(byte[] b,Atom mdia,Track t) throws IOException {
		Media m=new Media(t);
		for(Atom a:children(b,mdia)){
			switch(a.name()){
				case "mdhd":{
					int p=a.body();int version=b[p]&0xff;p+=4;
					if(version==1){m.creationTime=u8(b,p);m.modificationTime=u8(b,p+8);m.timeScale=u4(b,p+16);m.duration=u8(b,p+20);p+=28;}
					else{m.creationTime=u4(b,p)&0xffffffffL;m.modificationTime=u4(b,p+4)&0xffffffffL;m.timeScale=u4(b,p+8);m.duration=u4(b,p+12)&0xffffffffL;p+=16;}
					m.language=(short)u2(b,p);m.quality=(short)u2(b,p+2);
					break;
				}
				case "hdlr":{int[] h=handler(b,a);m.handlerType=h[1];m.handlerManufacturer=h[2];m.handlerName=handlerName(b,a);break;} // 'mhlr', media type in the subtype
				case "minf":parseMediaInfo(b,a,m);break;
				default:break;
			}
		}
		m.derive();
		return m;
	}

	/** {componentType, componentSubtype, manufacturer} of an 'hdlr' atom. */
	private static int[] handler(byte[] b,Atom a){int p=a.body()+4;return new int[]{u4(b,p),u4(b,p+4),u4(b,p+8)};}
	private static String handlerName(byte[] b,Atom a){
		int p=a.body()+24;if(p>=a.end())return "";
		int n=b[p]&0xff;
		if(p+1+n<=a.end()&&n==a.end()-p-1)return new String(b,p+1,n,StandardCharsets.ISO_8859_1); // Pascal string (QuickTime)
		int e=p;while(e<a.end()&&b[e]!=0)e++;return new String(b,p,e-p,StandardCharsets.ISO_8859_1); // C string (MPEG-4)
	}

	private void parseMediaInfo(byte[] b,Atom minf,Media m) throws IOException {
		for(Atom a:children(b,minf)){
			switch(a.name()){
				case "vmhd":case "smhd":case "gmhd":case "nmhd":case "hmhd":m.mediaInfoHeader=a;break;
				case "hdlr":{int[] h=handler(b,a);m.dataHandlerSubtype=h[1];m.dataHandlerName=handlerName(b,a);break;}
				case "dinf":{
					Atom dref=child(b,a,fourcc("dref"));
					if(dref!=null){
						int count=u4(b,dref.body()+4);int p=dref.body()+8;
						for(int i=0;i<count&&p+12<=dref.end();i++){
							long size=u4(b,p)&0xffffffffL;if(size<12||p+size>dref.end())break;
							int flags=u4(b,p+8)&0xffffff;
							// a self reference is an 'alis' to QuickTime whatever its type ('url ' in newer files)
							DataRef ref=new DataRef((flags&1)!=0?ALIS:u4(b,p+4),flags,Arrays.copyOfRange(b,p+12,p+(int)size));
							if(!ref.self())ref.file=resolveDataRef(ref);
							m.dataRefs.add(ref);p+=(int)size;
						}
					}
					break;
				}
				case "stbl":parseSampleTable(b,a,m);break;
				default:break;
			}
		}
	}

	private void parseSampleTable(byte[] b,Atom stbl,Media m){
		for(Atom a:children(b,stbl)){
			int p=a.body();
			switch(a.name()){
				case "stsd":{
					int count=u4(b,p+4);p+=8;
					for(int i=0;i<count&&p+16<=a.end();i++){
						long size=u4(b,p)&0xffffffffL;if(size<16||p+size>a.end())break;
						byte[] entry=Arrays.copyOfRange(b,p,p+(int)size);
						SampleDescription d;
						if(m.handlerType==VIDE&&size>=86)d=new VideoDescription(entry);
						else if(m.handlerType==SOUN&&size>=36)d=new SoundDescription(entry);
						else d=new SampleDescription(entry);
						m.descriptions.add(d);p+=(int)size;
					}
					break;
				}
				case "stts":{int n=count(b,a,8);m.sttsCount=new int[n];m.sttsDelta=new int[n];for(int i=0;i<n;i++){m.sttsCount[i]=u4(b,p+8+i*8);m.sttsDelta[i]=u4(b,p+12+i*8);}break;}
				case "ctts":{int n=count(b,a,8);m.cttsCount=new int[n];m.cttsOffset=new int[n];for(int i=0;i<n;i++){m.cttsCount[i]=u4(b,p+8+i*8);m.cttsOffset[i]=u4(b,p+12+i*8);}break;}
				case "stss":{int n=count(b,a,4);int[] s=new int[n];for(int i=0;i<n;i++)s[i]=u4(b,p+8+i*4);Arrays.sort(s);m.syncSamples=s;break;}
				case "stsh":{int n=count(b,a,8);int[] s=new int[n*2];for(int i=0;i<n*2;i++)s[i]=u4(b,p+8+i*4);m.shadowSync=s;break;}
				case "stsc":{int n=count(b,a,12);m.stscFirst=new int[n];m.stscPerChunk=new int[n];m.stscDesc=new int[n];for(int i=0;i<n;i++){m.stscFirst[i]=u4(b,p+8+i*12);m.stscPerChunk[i]=u4(b,p+12+i*12);m.stscDesc[i]=u4(b,p+16+i*12);}break;}
				case "stsz":{
					m.constantSize=u4(b,p+4);m.sampleCount=u4(b,p+8);
					if(m.constantSize==0){int n=Math.max(0,Math.min(m.sampleCount,(a.end()-p-12)/4));m.sizes=new int[n];for(int i=0;i<n;i++)m.sizes[i]=u4(b,p+12+i*4);m.sampleCount=n;}
					break;
				}
				case "stz2":{ // compact sample sizes (4, 8 or 16 bits)
					int field=b[p+7]&0xff;int n=u4(b,p+8);int[] s=new int[n];
					for(int i=0;i<n;i++){int q=p+12;s[i]=field==16?u2(b,q+i*2):field==8?b[q+i]&0xff:(b[q+i/2]>>((i&1)==0?4:0))&15;}
					m.sizes=s;m.sampleCount=n;break;
				}
				case "stco":{int n=count(b,a,4);m.chunkOffsets=new long[n];for(int i=0;i<n;i++)m.chunkOffsets[i]=u4(b,p+8+i*4)&0xffffffffL;break;}
				case "co64":{int n=count(b,a,8);m.chunkOffsets=new long[n];for(int i=0;i<n;i++)m.chunkOffsets[i]=u8(b,p+8+i*8);break;}
				default:break;
			}
		}
	}

	/** The entry count of a full-box table, clamped to what fits in the atom. */
	private static int count(byte[] b,Atom a,int entrySize){
		int n=u4(b,a.body()+4);int room=(a.end()-a.body()-8)/entrySize;
		return Math.max(0,Math.min(n,room));
	}

	// ------------------------------------------------------------------ data references

	/**
	 * The file an alias data reference names: HyperZebra's own host-path record ($485A), the absolute Mac path mapped to
	 * the folders around this movie, then the file name next to this movie (movies were usually moved with their data).
	 */
	File resolveDataRef(DataRef ref){
		if(ref.type!=ALIS||ref.data.length<0x96)return null;
		byte[] a=ref.data;File dir=file.getAbsoluteFile().getParentFile();
		List<byte[]> nameCandidates=new ArrayList<byte[]>();
		int n=Math.min(63,a[50]&0xff);nameCandidates.add(Arrays.copyOfRange(a,51,51+n));
		String hostPath=null;byte[] macPath=null;
		for(int p=0x96;p+4<=a.length;){
			int tag=(short)u2(a,p),len=u2(a,p+2);if(tag==-1)break;
			if(p+4+len>a.length)break;
			if(tag==FileSystem68k.ALIAS_HOST_PATH_RECORD)hostPath=new String(a,p+4,len,StandardCharsets.UTF_8);
			else if(tag==2)macPath=Arrays.copyOfRange(a,p+4,p+4+len);
			else if(tag==14){ // Unicode file name (HFS+ aliases): length-prefixed UTF-16
				int chars=u2(a,p+4);if(chars*2+2<=len){String s=new String(a,p+6,chars*2,StandardCharsets.UTF_16BE);File f=new File(dir,s);if(f.isFile())return f;}
			}
			p+=4+len+(len&1);
		}
		if(hostPath!=null){File f=new File(hostPath);if(f.isFile())return f;}
		for(byte[] name:nameCandidates)for(String s:decodings(name)){File f=new File(dir,s.replace('/',':'));if(f.isFile())return f;f=new File(dir,s);if(f.isFile())return f;}
		if(macPath!=null){
			// volume:folder:...:file - try the tail of the path under the movie's folder and its ancestors
			for(String s:decodings(macPath)){
				String[] parts=s.split(":");
				for(File base=dir;base!=null;base=base.getParentFile())
					for(int from=1;from<parts.length;from++){
						File f=base;for(int i=from;i<parts.length;i++)f=new File(f,parts[i].replace('/',':'));
						if(f.isFile())return f;
					}
			}
		}
		return null;
	}
	/** A Mac name read as Japanese and as Roman (the file was named on one of them). */
	private static List<String> decodings(byte[] name){
		List<String> list=new ArrayList<String>();
		try{list.add(MacJapaneseDecoder.decode(name,0,name.length));}catch(RuntimeException e){}
		try{String roman=StackTextEncoding.ROMAN.decode(name);if(!list.contains(roman))list.add(roman);}catch(RuntimeException e){}
		return list;
	}

	// ------------------------------------------------------------------ the movie box

	/** GetMovieBox: the union of the enabled visual tracks' rectangles through their matrices and the movie matrix, {top,left,bottom,right}. */
	int[] box(){
		double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;boolean any=false;
		for(Track t:tracks){
			if(!t.enabled()||!t.visual())continue;
			double w=t.width/65536.0,h=t.height/65536.0;
			double[][] corners={{0,0},{w,0},{0,h},{w,h}};
			for(double[] c:corners){
				double[] p=transform(t.matrix,c[0],c[1]);p=transform(matrix,p[0],p[1]);
				minX=Math.min(minX,p[0]);minY=Math.min(minY,p[1]);maxX=Math.max(maxX,p[0]);maxY=Math.max(maxY,p[1]);any=true;
			}
		}
		if(!any)return new int[4];
		return new int[]{(int)Math.round(minY),(int)Math.round(minX),(int)Math.round(maxY),(int)Math.round(maxX)};
	}
	/** Applies a QuickTime matrix (a,b,u / c,d,v / x,y,w: 16.16 except u,v,w 2.30) to a point. */
	static double[] transform(int[] m,double x,double y){
		double a=m[0]/65536.0,b=m[1]/65536.0,u=m[2]/1073741824.0,c=m[3]/65536.0,d=m[4]/65536.0,v=m[5]/1073741824.0,tx=m[6]/65536.0,ty=m[7]/65536.0,w=m[8]/1073741824.0;
		double X=x*a+y*c+tx,Y=x*b+y*d+ty,W=x*u+y*v+w;
		if(W!=0&&W!=1){X/=W;Y/=W;}
		return new double[]{X,Y};
	}
	static int[] identity(){return new int[]{0x10000,0,0,0,0x10000,0,0,0,0x40000000};}
	private static int[] readMatrix(byte[] b,int p){int[] m=new int[9];for(int i=0;i<9;i++)m[i]=u4(b,p+i*4);return m;}

	// ------------------------------------------------------------------ data access

	/** This file's data fork (MacBinary files hold it after their header). */
	DataFork data() throws IOException {if(dataFork==null)dataFork=new DataFork(file);return dataFork;}

	private final java.util.Map<File,DataFork> otherForks=new java.util.HashMap<File,DataFork>();
	/** Reads {@code length} bytes at {@code offset} of the data fork of {@code f} (a data reference's file). */
	byte[] read(File f,long offset,int length) throws IOException {
		if(f==null)throw new IOException("unresolved data reference");
		DataFork fork;
		if(f.equals(file))fork=data();
		else synchronized(otherForks){fork=otherForks.get(f);if(fork==null){fork=new DataFork(f);otherForks.put(f,fork);}}
		return fork.read(offset,length);
	}

	/** Closes the data forks this movie opened. */
	void close(){
		if(dataFork!=null)dataFork.close();
		synchronized(otherForks){for(DataFork f:otherForks.values())f.close();otherForks.clear();}
	}

	/** Random access to a data fork: a plain file, or the data fork inside a MacBinary file. */
	static final class DataFork {
		private RandomAccessFile raf;private final long base,length;
		DataFork(File f) throws IOException {
			long b=0,len=f.length();
			if(ResourceFile68k.MacBinary.headerLooksLikeMacBinary(f)){long[] lengths=ResourceFile68k.MacBinary.headerForkLengths(f);if(lengths!=null){b=128;len=lengths[0];}}
			base=b;length=len;raf=new RandomAccessFile(f,"r");
		}
		long length(){return length;}
		synchronized byte[] read(long offset,int n) throws IOException {
			if(offset<0||n<0||offset+n>length)throw new IOException("read beyond the data fork: "+offset+"+"+n+" of "+length);
			byte[] out=new byte[n];raf.seek(base+offset);raf.readFully(out);return out;
		}
		/** The size of the top-level atom at {@code at} (size 0 = to the end, 1 = 64-bit size), or -1. */
		long atomSize(long at) throws IOException {
			if(at+8>length)return -1;
			byte[] h=read(at,(int)Math.min(16,length-at));
			long size=u4(h,0)&0xffffffffL;
			if(size==1&&h.length>=16)size=u8(h,8);else if(size==0)size=length-at;
			return size<8||at+size>length?-1:size;
		}
		/** The first top-level atom of {@code type} at or after {@code from}, or -1 (stops at the first malformed header). */
		long findTopLevel(int type,long from) throws IOException {
			long at=from;
			while(at+8<=length){
				long size=atomSize(at);if(size<0)return -1;
				if(u4(read(at+4,4),0)==type)return at;
				at+=size;
			}
			return -1;
		}
		byte[] atomBytes(long at) throws IOException {
			long size=atomSize(at);if(size<0)throw new IOException("bad atom at "+at);
			if(size>Integer.MAX_VALUE-16)throw new IOException("movie atom too large");
			byte[] h=read(at,16>size?(int)size:16);
			byte[] bytes=read(at,(int)size);
			if((u4(h,0)&0xffffffffL)==1){ // 64-bit sized atom: rewrite as a 32-bit one so the in-memory parse sees one header form
				ByteArrayOutputStream out=new ByteArrayOutputStream();long body=size-16;
				putInt(out,(int)(body+8));out.write(bytes,4,4);out.write(bytes,16,(int)body);return out.toByteArray();
			}
			if((u4(h,0)&0xffffffffL)==0)putIntAt(bytes,0,(int)size);
			return bytes;
		}
		void close(){try{if(raf!=null)raf.close();}catch(IOException e){}raf=null;}
	}

	// ------------------------------------------------------------------ atoms in memory

	/** The atom at {@code p} (null when its header is malformed); handles 64-bit sizes and size 0 (to the end of the parent). */
	static Atom atomAt(byte[] b,int p,int limit){
		if(p+8>limit)return null;
		long size=u4(b,p)&0xffffffffL;int header=8;
		if(size==1){if(p+16>limit)return null;size=u8(b,p+8);header=16;}
		else if(size==0)size=limit-p;
		if(size<header||p+size>limit)return null;
		return new Atom(u4(b,p+4),p,(int)size,header);
	}
	static List<Atom> children(byte[] b,Atom parent){
		List<Atom> list=new ArrayList<Atom>();
		int p=parent.body(),end=parent.end();
		while(p+8<=end){
			// QuickTime 'udta' lists may end with a 32-bit zero terminator
			if(u4(b,p)==0&&p+4==end-4)break;
			Atom a=atomAt(b,p,end);if(a==null)break;
			list.add(a);p=a.end();
		}
		return list;
	}
	static Atom child(byte[] b,Atom parent,int type){for(Atom a:children(b,parent))if(a.type==type)return a;return null;}

	// ------------------------------------------------------------------ comparison dump

	/**
	 * The movie as the Movie Toolbox reports it, one fact per line, in the format tools/qt/QtContainerOracle writes for
	 * the real QuickTime: movie header, per track header/edits/media/descriptions/data references, and the sample
	 * references as maximal runs (contiguous bytes, equal size, duration, description and flags) so that QuickTime's
	 * own grouping of GetMediaSampleReference results does not matter.
	 */
	String describe(){
		StringBuilder sb=new StringBuilder();
		int[] box=box();
		sb.append(String.format("movie timeScale=%d duration=%d rate=%08X volume=%d box=%d,%d,%d,%d poster=%d selection=%d,%d tracks=%d\n",
			timeScale,duration,preferredRate,preferredVolume,box[0],box[1],box[2],box[3],posterTime,selectionTime,selectionDuration,tracks.size()));
		sb.append("movie matrix=").append(hexMatrix(matrix)).append('\n');
		for(int i=0;i<tracks.size();i++){
			Track t=tracks.get(i);Media m=t.media;
			sb.append(String.format("track %d id=%d enabled=%d duration=%d offset=%d dims=%08X,%08X layer=%d volume=%d usage=%d\n",
				i+1,t.id,t.enabled()?1:0,t.editDuration(),t.offset(),t.width,t.height,t.layer,t.volume,t.flags&(TRACK_IN_MOVIE|TRACK_IN_PREVIEW|TRACK_IN_POSTER)));
			sb.append(" matrix=").append(hexMatrix(t.matrix)).append('\n');
			for(String line:editLines(t))sb.append(line).append('\n');
			sb.append(String.format(" media type=%s timeScale=%d duration=%d language=%d quality=%d samples=%d descriptions=%d\n",
				typeName(m.handlerType),m.timeScale,m.duration,m.language,m.quality,m.samples(),m.descriptions.size()));
			for(int d=0;d<m.descriptions.size();d++)sb.append(" description ").append(d+1).append(' ').append(hex(m.descriptions.get(d).bytes)).append('\n');
			for(int d=0;d<m.dataRefs.size();d++){DataRef r=m.dataRefs.get(d);sb.append(String.format(" dataref %d type=%s self=%d resolved=%d\n",d+1,typeName(r.type),r.self()?1:0,(r.self()||r.file!=null)?1:0));}
			for(String line:sampleRuns(m))sb.append(line).append('\n');
			if(!(m.sound()&&m.sizes==null))sb.append(dataLine(m)).append('\n');
		}
		return sb.toString();
	}

	/** " data samples=N bytes=B crc=X": every sample's bytes in order, as GetMediaSample reads them. */
	static String dataLine(Media m){
		java.util.zip.CRC32 crc=new java.util.zip.CRC32();long bytes=0;int n=m.samples();
		try{for(long s=0;s<n;s++){byte[] b=m.readSample(s);crc.update(b);bytes+=b.length;}}
		catch(IOException e){return " data error "+e.getMessage();}
		return String.format(" data samples=%d bytes=%d crc=%08X",n,bytes,crc.getValue());
	}

	/** The edits as " edit start=<movie time> duration=<movie units> mediaTime=<media units or -1> rate=<Fixed>". */
	static List<String> editLines(Track t){
		List<String> lines=new ArrayList<String>();long start=0;
		for(Edit e:t.edits){if(e.duration>0)lines.add(String.format(" edit start=%d duration=%d mediaTime=%d rate=%08X",start,e.duration,e.mediaTime,e.mediaTime==-1?0:e.rate));start+=e.duration;}
		return lines;
	}

	/** Sample references in canonical runs; see {@link #describe}. */
	static List<String> sampleRuns(Media m){
		RunWriter w=new RunWriter();ChunkWriter cw=new ChunkWriter();
		int samples=m.samples();
		for(int c=0;c<m.chunkCount();c++){
			SampleDescription d=m.description(m.chunkDescription(c));
			long first=m.chunkFirstSample(c);int count=(int)Math.min(m.chunkSamples(c),Math.max(0,samples-first));
			if(m.sound()&&m.sizes==null){
				// sound: a chunk at a time (GetMediaSampleReference at the chunk's start). Its size (and what GetMediaSample
				// reads) is count x the stsz size, not the bytes, for every version-0 description: the byte count comes from
				// the sound description alone, so it is not part of the comparison
				if(count>0)cw.add(m.sampleTime(first),count,m.sampleDuration(first),m.chunkOffsets[c],m.chunkDescription(c));
				continue;
			}
			for(long s=first;s<first+count;s++){
				int flags=m.sync(s)?0:SAMPLE_NOT_SYNC;
				w.add(m.sampleTime(s),m.sampleDuration(s),m.sampleOffset(s),m.sampleSize(s,d),m.chunkDescription(c),flags);
			}
		}
		List<String> lines=w.finish();lines.addAll(cw.finish());return lines;
	}

	/** Sound chunk references, one line each: n samples of equal duration at an offset. */
	static final class ChunkWriter {
		private final List<String> lines=new ArrayList<String>();
		void add(long t,long n,long d,long off,int description){lines.add(String.format(" chunk time=%d duration=%d count=%d offset=%d description=%d",t,d,n,off,description));}
		List<String> finish(){return lines;}
	}

	/** Accumulates sample references into maximal runs (used by the oracle too). */
	static final class RunWriter {
		private final List<String> lines=new ArrayList<String>();
		private long time,dur,offset,size;int desc,flags,n;long expectTime,expectOffset;
		void add(long t,long d,long off,long sz,int description,int f){
			if(n>0&&t==expectTime&&d==dur&&off==expectOffset&&sz==size&&description==desc&&f==flags){n++;expectTime+=d;expectOffset+=sz;return;}
			flush();time=t;dur=d;offset=off;size=sz;desc=description;flags=f;n=1;expectTime=t+d;expectOffset=off+sz;
		}
		private void flush(){if(n>0)lines.add(String.format(" run time=%d duration=%d count=%d offset=%d size=%d description=%d flags=%d",time,dur,n,offset,size,desc,flags));n=0;}
		List<String> finish(){flush();return lines;}
	}

	static String hexMatrix(int[] m){StringBuilder sb=new StringBuilder();for(int i=0;i<9;i++){if(i>0)sb.append(',');sb.append(String.format("%08X",m[i]));}return sb.toString();}
	static String hex(byte[] b){StringBuilder sb=new StringBuilder(b.length*2);for(byte x:b)sb.append(String.format("%02X",x&0xff));return sb.toString();}

	@Override public String toString(){
		StringBuilder sb=new StringBuilder(file.getName()+" ("+source+"): timeScale "+timeScale+", duration "+duration);
		for(Track t:tracks){
			Media m=t.media;sb.append("\n  track ").append(t.id).append(' ').append(typeName(m.handlerType)).append(": ").append(m.samples()).append(" samples");
			for(SampleDescription d:m.descriptions){
				sb.append(" '").append(typeName(d.format)).append('\'');
				if(d instanceof VideoDescription){VideoDescription v=(VideoDescription)d;sb.append(' ').append(v.width).append('x').append(v.height).append('x').append(v.depth);}
				if(d instanceof SoundDescription){SoundDescription s=(SoundDescription)d;sb.append(' ').append(s.channels).append("ch ").append(s.sampleSize).append("-bit ").append(Math.round(s.sampleRate)).append(" Hz v").append(s.version);}
			}
		}
		return sb.toString();
	}

	/** Command line: {@code java QuickTimeFile [-d] movie...} prints a summary, or with -d the comparison dump. */
	public static void main(String[] args) throws IOException {
		boolean dump=false;
		for(String a:args){
			if(a.equals("-d")){dump=true;continue;}
			File f=new File(a);
			try{QuickTimeFile q=open(f);System.out.println(dump?q.describe():q.toString());q.close();}
			catch(IOException|RuntimeException e){System.out.println(f.getName()+": "+e);}
		}
	}

	// ------------------------------------------------------------------ bytes

	static int fourcc(String s){return (s.charAt(0)&255)<<24|(s.charAt(1)&255)<<16|(s.charAt(2)&255)<<8|(s.charAt(3)&255);}
	static String typeName(int t){return ""+(char)((t>>>24)&255)+(char)((t>>>16)&255)+(char)((t>>>8)&255)+(char)(t&255);}
	static int u2(byte[] b,int p){return (b[p]&0xff)<<8|(b[p+1]&0xff);}
	static int u4(byte[] b,int p){return (b[p]&0xff)<<24|(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|(b[p+3]&0xff);}
	static long u8(byte[] b,int p){return (long)u4(b,p)<<32|(u4(b,p+4)&0xffffffffL);}
	private static void putInt(ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}
	private static void putIntAt(byte[] b,int p,int v){b[p]=(byte)(v>>>24);b[p+1]=(byte)(v>>>16);b[p+2]=(byte)(v>>>8);b[p+3]=(byte)v;}
}
