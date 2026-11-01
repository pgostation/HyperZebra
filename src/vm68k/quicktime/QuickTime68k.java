import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * Native Movie Toolbox ($AAAA) and movie controller component for hosted applications. Old movie
 * formats are decoded by the same converters HyperZebra uses for QuickTime XCMDs (own sound decoders, MIDI for music,
 * ffmpeg for video), then
 * rendered into the program's window or GWorld by MoviesTask/MCIdle.
 *
 * Selector numbers were confirmed against a classic movie external (argument sizes and the way each
 * result is consumed), not only against the multiversal interface definitions, which stop at the
 * few calls the first version needed.
 */
final class QuickTime68k {
	static final int VERSION=0x02500000;
	static final boolean QT_TRACE=Boolean.getBoolean("hz68k.qttrace"); // logs every Movie Toolbox call with its top stack longs
	static final int DEFAULT_TIME_SCALE=600,CONTROLLER_HEIGHT=16,CONTROLLER_MIN_WIDTH=160;
	/** NewMovieFrom... flag: the movie is active from the start (Movies.h newMovieActive). */
	static final int NEW_MOVIE_ACTIVE=1;
	// Movie controller actions (MCDoAction / action filter)
	static final int MC_IDLE=1,MC_DRAW=2,MC_ACTIVATE=3,MC_DEACTIVATE=4,MC_MOUSE_DOWN=5,MC_KEY=6,MC_PLAY=8,MC_GO_TO_TIME=12,MC_SET_VOLUME=14,MC_GET_VOLUME=15,MC_STEP=18,
		MC_SET_LOOPING=21,MC_GET_LOOPING=22,MC_SET_PALINDROME=23,MC_GET_PALINDROME=24,MC_SET_GROW_BOX=25,MC_SIZE_CHANGED=26,MC_SET_SELECTION_BEGIN=29,MC_SET_SELECTION_DURATION=30,
		MC_SET_KEYS_ENABLED=32,MC_GET_KEYS_ENABLED=33,MC_SET_PLAY_SELECTION=34,MC_GET_PLAY_SELECTION=35,MC_SET_USE_BADGE=36,MC_GET_USE_BADGE=37,MC_SET_FLAGS=38,MC_GET_FLAGS=39,
		MC_SET_PLAY_EVERY_FRAME=40,MC_GET_PLAY_EVERY_FRAME=41,MC_GET_PLAY_RATE=42;
	static final int VIDE=0x76696465,SOUN=0x736f756e,MUSI=0x6d757369,APPL=0x6170706c;
	static final int TRIGGER_TIME_FWD=1,TRIGGER_TIME_BWD=2,TRIGGER_TIME_EITHER=3;

	final class Movie {
		final int handle;File file;TQTMovie.MovieAsset asset;int port,box[]=new int[4];boolean active=true;
		int rate; // Fixed; playing while non-zero
		long startMillis;int pausedAt; // wall clock when the current run started, and the position (ms) it started from
		int lastTime=-1; // movie time seen by the last task(), for callbacks
		int volume=0x0100,preferredVolume=0x0100,preferredRate=0x00010000;boolean previewMode;int posterTime,previewTime,previewDuration; // from the movie header
		/** A paused movie's exact time (units) after a seek, valid while {@link #pausedAt} is still {@link #pausedUnitsAt}: time is kept
		 *  in milliseconds, which do not round-trip through the time scale (a 15 fps StepFwd in UxMovie read 3/7/11 ticks, not 4/8/12). */
		long pausedUnits=-1;int pausedUnitsAt=-1;
		/** The tracks of a movie with nothing HyperZebra decodes (UxMovie's VR panorama: a disabled 'vide' and an 'STpn'): their
		 *  types and sizes as the file has them, so the track list and the movie box are QuickTime's (it shows an empty white box). */
		java.util.List<int[]> fileTracks;int[] fileBox;
		/** The movie times (units, rounded up) where the frames of its first enabled video track start: steps and
		 *  GetMovieNextInterestingTime go from sample to sample (timeScale/fps drifts: a 15.0007 fps estimate stepped by 39 of 40). */
		long[] frameTimes;
		int timeScale=DEFAULT_TIME_SCALE;long durationUnits=-1;
		int timeBase; // Handle, created by GetMovieTimeBase
		int timeBaseFlags; // loopTimeBase 1, palindromeLoopTimeBase 2 (SetTimeBaseFlags)
		/** The movie stopped at its end outside the controller's idle (GetMovieRate, IsMovieDone or MoviesTask saw it first): the
		 *  controller's next idle still tells the program through its action filter (Myst Preview's Movie XCMD 1.2 kept
		 *  answering rate 1 and the intro waited for ever). */
		boolean endUnreported;
		/** The rate the movie ran at when it stopped at its end (0 when it did not): QuickTime keeps the movie's rate there, so
		 *  GoToBeginningOfMovie plays it again - the loop idiom "MoviesTask; if IsMovieDone then GoToBeginningOfMovie" (UxPlayMovie's
		 *  playCount; it stayed on frame 0 and a count of 2 never ended). */
		int endRate;
		Clip clip;StreamingSound stream;Sequencer sequencer;Synthesizer synthesizer;int controller; // stream: an MP3, decoded while it plays
		int selectionTime,selectionDuration,userData; // UserData Handle (empty), created by GetMovieUserData
		final java.util.List<Track> tracks=new ArrayList<Track>(); // one per kind of content, created on demand
		/** A movie the program builds (CreateMovieFile, NewMovie): its tracks, media and samples, written by AddMovieResource or FlattenMovieData. */
		MovieWriter68k.MovieData edit;
		/** A file movie's tracks and samples in the editing form, made once for CopyMovieSelection: copies of one movie share its media. */
		MovieWriter68k.MovieData fileEdit;
		int[] naturalBox(){if(edit!=null){int[] n=edit.naturalSize();return new int[]{0,0,n[1],n[0]};}return hasFrames()?new int[]{0,0,asset.frameHeight(),asset.frameWidth()}:fileBox!=null?fileBox.clone():new int[4];}
		Movie(int handle){this.handle=handle;}
		boolean playing(){return rate!=0;}
		long durationMillis(){
			if(edit!=null)return edit.duration()*1000/edit.timeScale;
			if(asset==null)return 0;
			if(asset.timeScale>0&&asset.duration>0)return asset.duration*1000L/asset.timeScale;
			if(asset.hasVideo())return (long)(asset.frameCount()*1000.0/asset.frameRate());
			if(clip!=null)return clip.getMicrosecondLength()/1000;
			if(stream!=null)return stream.getMicrosecondLength()/1000;
			if(sequencer!=null)return sequencer.getMicrosecondLength()/1000;
			return 0;
		}
		int currentMillis(){
			if(!playing())return pausedAt;
			long elapsed=(long)((System.currentTimeMillis()-startMillis)*(rate/65536.0));
			return (int)Math.max(0,Math.min(durationMillis(),pausedAt+elapsed));
		}
		long duration(){return edit!=null?edit.duration():durationUnits>=0?durationUnits:units(durationMillis());}
		/** The movie time; at the end exactly the duration (the time scale's units do not round-trip through milliseconds:
		 *  Myst Preview's DemoVoice stopped at 45618 of 45619, and the Movie XCMD's closeOnFinish never saw it finish). */
		int time(){if(!playing()&&pausedUnits>=0&&pausedAt==pausedUnitsAt)return (int)pausedUnits;long ms=currentMillis();return ms>=durationMillis()&&durationMillis()>0?(int)duration():(int)units(ms);}
		long units(long millis){return millis*timeScale/1000;}
		int millis(long units){return (int)(units*1000/timeScale);}
		double fps(){return asset!=null?asset.frameRate():10.0;}
		boolean hasFrames(){return asset!=null&&asset.hasVideo();}
	}
	/** A track of a movie and its media: 'vide' for the frames, 'soun' for sampled sound, 'musi' for QuickTime music. */
	final class Track {final int handle;int media,type;final Movie movie;MovieWriter68k.TrackData data;int width,height; /* Fixed: a file track's own size */boolean enabled=true;Track(int handle,int media,int type,Movie movie){this.handle=handle;this.media=media;this.type=type;this.movie=movie;}}
	/** A QTCallBack (callBackAtTime) armed by CallMeWhen. */
	final class CallBack {final int handle,type;int timeBase;int proc,refCon,flags;long trigger;boolean armed;CallBack(int handle,int timeBase,int type){this.handle=handle;this.timeBase=timeBase;this.type=type;}}
	/** A native movie controller instance ('play'/'moov' component). */
	final class Controller {
		final int instance;int movie,port,filter;boolean visible=true,attached=true,useBadge,playEveryFrame,keysEnabled=true,looping,palindrome,playSelection;int flags;
		int[] detachedBar; // where the program put a detached controller (MCSetControllerBoundsRect / MCPositionController)
		int[] lastBar;boolean lastPlaying;int lastThumb=-1;int filterDepth;
		Controller(int instance){this.instance=instance;}
	}
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,Movie> movies=new HashMap<Integer,Movie>();
	private final Map<Integer,File> openFiles=new HashMap<Integer,File>();
	private final Map<Integer,CallBack> callBacks=new HashMap<Integer,CallBack>();
	private final Map<Integer,Controller> controllers=new HashMap<Integer,Controller>();
	private final Map<Integer,Track> tracks=new HashMap<Integer,Track>(),medias=new HashMap<Integer,Track>(); // by Track Handle and by Media Handle
	private int nextRef=1000;
	private int fullScreenWindow,fullScreenState; // BeginFullScreen's window and its restore-state Ptr
	static final int COULD_NOT_RESOLVE_DATA_REF=-2000,INVALID_MEDIA=-2008,INVALID_TRACK=-2009,INVALID_MOVIE=-2010,USER_DATA_ITEM_NOT_FOUND=-2026,WR_PERM_ERR=-61;
	// GetMovieNextInterestingTime flags
	static final int NEXT_TIME_MEDIA_SAMPLE=1,NEXT_TIME_MEDIA_EDIT=2,NEXT_TIME_TRACK_EDIT=4,NEXT_TIME_SYNC_SAMPLE=8,NEXT_TIME_STEP=0x10,NEXT_TIME_EDGE_OK=0x4000; // Movies.h: nextTimeEdgeOK = 1 << 14
	private boolean entered;
	private int stickyError;
	/** The result of the last Movie Toolbox call (GetMoviesError): noErr unless it failed; the sticky error keeps the first failure. */
	private int lastError;
	private void fail(int err){lastError=err;if(stickyError==0)stickyError=err;}
	final QTAtoms68k atoms; // atom containers and QuickTime preferences

	QuickTime68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;atoms=new QTAtoms68k(tb);}

	/**
	 * The QuickTime an external (XCMD/XFCN) sees: HyperCard's Mac has the QuickTime 4.0.3 HyperZebra installs (the last for
	 * 68K Macs), served by this native Movie Toolbox with its graphics importers, preview maker and Photo JPEG compression.
	 * Rinaldi's PictInfos, PictToClip, CreateCustomIcon ... read JPEG/GIF/TIFF files and movies only with QuickTime 3 or later.
	 */
	static final int EXTERNAL_VERSION=0x04038000;
	int version(){return tb.guestQuickTime?0:tb.app==null?EXTERNAL_VERSION:VERSION;}
	/** The native Note Allocator / Tune Player (registered with the other native components; null under the real extension). */
	QuickTimeMusic68k music;
	void shutDown(){for(Movie m:movies.values())closeAudio(m);movies.clear();controllers.clear();callBacks.clear();if(music!=null)music.shutDown();}
	/** A tune of the native Tune Player is still playing (an external that queued one and returned should keep its VM). */
	boolean musicSounding(){return music!=null&&music.sounding();}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;int sp=cpu.a[7];
		if(QT_TRACE)System.err.println(String.format("[qt] $%04X args %08X %08X %08X %08X %08X %08X %08X",selector,memory.readLong(sp),memory.readLong(sp+4),memory.readLong(sp+8),memory.readLong(sp+12),memory.readLong(sp+16),memory.readLong(sp+20),memory.readLong(sp+24)));
		if(atoms.dispatch(cpu,selector))return true;
		if(selector!=0x0003&&selector!=0x0004&&selector!=0x00de)lastError=0;
		switch(selector){
			case 0x0001:entered=true;memory.writeWord(sp,0);return true; // EnterMovies
			case 0x0002:entered=false;return true; // ExitMovies
			case 0x0003:memory.writeWord(sp,lastError);return true; // GetMoviesError
			case 0x0004:memory.writeWord(sp,stickyError);return true; // GetMoviesStickyError : OSErr (Movies.h 7004 AAAA)
			case 0x00de:stickyError=0;return true; // ClearMoviesStickyError (Movies.h 303C 00DE AAAA; Infini-D 3.0 calls it before writing a movie)
			case 0x0005:{int movie=memory.readLong(sp+4);cpu.a[7]+=8;task(movie);if(music!=null)music.task();return true;} // MoviesTask(movie, maxMilliSecToUse) (+ the tunes' callbacks)
			case 0x0006:{cpu.a[7]+=12;memory.writeWord(cpu.a[7],0);return true;} // PrerollMovie(movie, time, rate)
			case 0x0009:{boolean active=memory.readByte(sp)!=0;int movie=memory.readLong(sp+2);cpu.a[7]+=6;Movie m=movies.get(movie);if(m!=null)m.active=active;return true;} // SetMovieActive
			case 0x000a:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);tb.writeBoolean(cpu.a[7],m!=null&&m.active);return true;} // GetMovieActive
			case 0x000b:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)setRate(m,0x00010000);return true;} // StartMovie
			case 0x000c:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)setRate(m,0);return true;} // StopMovie
			case 0x000d:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null){int again=m.rate==0?m.endRate:0;seekMillis(m,0);if(again>0)setRate(m,again);m.endRate=0;draw(m);}return true;} // GoToBeginningOfMovie
			case 0x0175:{int spec=memory.readLong(sp);cpu.a[7]+=4;memory.writeWord(cpu.a[7],deleteMovieFile(spec));return true;} // DeleteMovieFile(fileSpec)
			case 0x000e:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null){seekMillis(m,(int)m.durationMillis());draw(m);}return true;} // GoToEndOfMovie
			case 0x000f:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);tb.writeBoolean(cpu.a[7],m!=null&&m.previewMode);return true;} // GetMoviePreviewMode
			case 0x0010:{boolean preview=memory.readByte(sp)!=0;int movie=memory.readLong(sp+2);cpu.a[7]+=6;Movie m=movies.get(movie);if(m!=null)m.previewMode=preview;return true;} // SetMoviePreviewMode
			case 0x0011:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)drawFrame(m,frameAt(m,m.millis(m.posterTime)));return true;} // ShowMoviePoster
			case 0x0012:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:timeBase(m));return true;} // GetMovieTimeBase
			case 0x0015:{int gdh=memory.readLong(sp),portPtr=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie);if(portPtr!=0)memory.writeLong(portPtr,m==null?0:m.port);if(gdh!=0)memory.writeLong(gdh,tb.quickDraw.mainDevice());return true;} // GetMovieGWorld
			case 0x0016:{int gdh=memory.readLong(sp),port=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie);if(m!=null)m.port=port;return true;} // SetMovieGWorld
			case 0x001d:{int time=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:pictAt(m,time));return true;} // GetMoviePict
			case 0x001f:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)draw(m);memory.writeWord(cpu.a[7],0);return true;} // UpdateMovie
			case 0x0023:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.remove(movie);if(m!=null){closeAudio(m);disposeTracks(m);if(m.userData!=0)tb.api.disposeHandleAndFree(m.userData);if(m.timeBase!=0){forgetCallBacks(m.timeBase);tb.api.disposeHandleAndFree(m.timeBase);}tb.api.disposeHandleAndFree(movie);}return true;} // DisposeMovie
			case 0x0029:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?DEFAULT_TIME_SCALE:m.timeScale);return true;} // GetMovieTimeScale
			case 0x002a:{int scale=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie); // SetMovieTimeScale(movie, scale) (UxPlayMIDI's .mov save: the tune's own scale)
				if(m!=null&&scale>0){boolean edited=false;if(m.edit!=null)for(MovieWriter68k.TrackData t:m.edit.tracks)edited|=!t.edits.isEmpty();
					if(m.edit!=null&&!edited)m.edit.timeScale=scale;if(m.edit==null||!edited)m.timeScale=scale;}return true;}
			case 0x002b:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:(int)m.duration());return true;} // GetMovieDuration
			case 0x002c:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)settle(m);memory.writeLong(cpu.a[7],m==null?0:m.rate);return true;} // GetMovieRate
			case 0x002d:{int rate=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null)setRate(m,rate);return true;} // SetMovieRate
			case 0x002e:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeWord(cpu.a[7],m==null?0:m.volume);return true;} // GetMovieVolume
			case 0x002f:{int volume=(short)memory.readWord(sp),movie=memory.readLong(sp+2);cpu.a[7]+=6;Movie m=movies.get(movie);if(m!=null)setVolume(m,volume);return true;} // SetMovieVolume
			case 0x0033:{int durationPtr=memory.readLong(sp),timePtr=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie); // GetMoviePreviewTime(movie, previewTime*, previewDuration*) (Apple's movieInfo XFCN)
				if(timePtr!=0)memory.writeLong(timePtr,m==null?0:m.previewTime);if(durationPtr!=0)memory.writeLong(durationPtr,m==null?0:m.previewDuration);return true;}
			case 0x0034:{int duration=memory.readLong(sp),time=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie);if(m!=null){m.previewTime=time;m.previewDuration=duration;}return true;} // SetMoviePreviewTime(movie, time, duration)
			case 0x0035:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:m.posterTime);return true;} // GetMoviePosterTime
			case 0x0039:{int record=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);int time=m==null?0:m.time(); // GetMovieTime(movie, TimeRecord*)
				if(record!=0&&m!=null){memory.writeLong(record,0);memory.writeLong(record+4,time);memory.writeLong(record+8,m.timeScale);memory.writeLong(record+12,timeBase(m));}
				memory.writeLong(cpu.a[7],time);return true;}
			case 0x0007:{cpu.a[7]+=16;memory.writeWord(cpu.a[7],0);return true;} // LoadMovieIntoRam: everything is decoded already
			case 0x0031:{int matrix=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null&&matrix!=0)writeMatrix(matrix,m);return true;} // GetMovieMatrix
			case 0x0032:{int matrix=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null&&matrix!=0)readMatrix(matrix,m);return true;} // SetMovieMatrix
			case 0x0036:{int time=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null)m.posterTime=time;return true;} // SetMoviePosterTime
			case 0x0037:{int durationPtr=memory.readLong(sp),timePtr=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie); // GetMovieSelection(movie, time*, duration*)
				if(timePtr!=0)memory.writeLong(timePtr,m==null?0:m.selectionTime);if(durationPtr!=0)memory.writeLong(durationPtr,m==null?0:m.selectionDuration);return true;}
			case 0x0038:{int duration=memory.readLong(sp),time=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie);if(m!=null){m.selectionTime=time;m.selectionDuration=duration;}return true;} // SetMovieSelection
			case 0x003c:{int record=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie); // SetMovieTime(movie, TimeRecord*): the record's 64-bit value in its own time scale (0: the movie's)
				if(m!=null&&record!=0){long value=(long)memory.readLong(record)<<32|Integer.toUnsignedLong(memory.readLong(record+4));int scale=memory.readLong(record+8);
					long units=scale>0&&scale!=m.timeScale?value*m.timeScale/scale:value;seekUnits(m,(int)Math.max(0,Math.min(m.duration(),units)));draw(m);}
				return true;}
			case 0x003d:{int time=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null){seekUnits(m,Math.max(0,Math.min(m.duration(),time)));draw(m);}return true;} // SetMovieTimeValue
			case 0x003e:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null&&m.userData==0)m.userData=tb.api.createHandle(new byte[4]);memory.writeLong(cpu.a[7],m==null?0:m.userData);return true;} // GetMovieUserData
			case 0x003f:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:tracks(m).size());return true;} // GetMovieTrackCount
			case 0x004b:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeLong(cpu.a[7],t==null?0:t.data!=null?(int)t.data.duration():(int)t.movie.duration());return true;} // GetTrackDuration
			case 0x0054:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeWord(cpu.a[7],t==null?0:0x0100);return true;} // GetTrackVolume
			// a track keeps the identity matrix: the movie matrix alone places the frame (SetMovieBox) - Chronicle of Ekan's Movie XCMD
			case 0x0056:{int matrix=memory.readLong(sp);cpu.a[7]+=8;if(matrix!=0){int[] v={0x10000,0,0, 0,0x10000,0, 0,0,0x40000000};for(int i=0;i<9;i++)memory.writeLong(matrix+i*4,v[i]);}return true;} // GetTrackMatrix
			case 0x0057:{cpu.a[7]+=8;return true;} // SetTrackMatrix
			// the other track settings: one enabled track of full use at the movie's start on layer 0, its frame the natural size
			case 0x0045:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeWord(cpu.a[7],t==null||!t.enabled?0:0x0100);return true;} // GetTrackEnabled
			case 0x0046:{boolean on=memory.readByte(sp)!=0;int track=memory.readLong(sp+2);cpu.a[7]+=6;Track t=tracks.get(track);if(t!=null)t.enabled=on;return true;} // SetTrackEnabled(track, isEnabled): kept for the editing routines, which leave a disabled track out (QTAssemble drops the scenes' sound so)
			case 0x0047:{int track=memory.readLong(sp);cpu.a[7]+=4;memory.writeLong(cpu.a[7],tracks.get(track)==null?0:0x0E);return true;} // GetTrackUsage
			case 0x0048:{cpu.a[7]+=8;return true;} // SetTrackUsage
			case 0x004c:{cpu.a[7]+=4;memory.writeLong(cpu.a[7],0);return true;} // GetTrackOffset
			case 0x004d:{cpu.a[7]+=8;return true;} // SetTrackOffset
			case 0x0050:{cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;} // GetTrackLayer
			case 0x0051:case 0x0055:{cpu.a[7]+=6;return true;} // SetTrackLayer, SetTrackVolume
			case 0x005d:{int height=memory.readLong(sp),width=memory.readLong(sp+4),track=memory.readLong(sp+8);cpu.a[7]+=12;Track t=tracks.get(track); // GetTrackDimensions
				if(t!=null&&(t.width|t.height)!=0){if(width!=0)memory.writeLong(width,t.width);if(height!=0)memory.writeLong(height,t.height);return true;}
				int[] n=t!=null&&t.type==VIDE?t.movie.naturalBox():new int[4];if(width!=0)memory.writeLong(width,(n[3]-n[1])<<16);if(height!=0)memory.writeLong(height,(n[2]-n[0])<<16);return true;}
			case 0x005e:{cpu.a[7]+=12;return true;} // SetTrackDimensions
			case 0x00d0:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeLong(cpu.a[7],t==null?0:t.movie.handle);return true;} // GetTrackMovie
			case 0x0052:{int track=memory.readLong(sp);cpu.a[7]+=4;memory.writeLong(cpu.a[7],track);return true;} // GetTrackAlternate: a track is its own only alternate
			case 0x005f:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeLong(cpu.a[7],t==null?0:t.movie.userData!=0?t.movie.userData:(t.movie.userData=tb.api.createHandle(new byte[4])));return true;} // GetTrackUserData: empty, as the movie's
			case 0x006b:{cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;} // GetMediaLanguage: English
			case 0x0071:{int media=memory.readLong(sp);cpu.a[7]+=4;memory.writeLong(cpu.a[7],medias.containsKey(media)?media:0);return true;} // GetMediaHandler: the media itself stands for its handler instance (Sparkle 2.45 asks before it reads the sample descriptions)
			case 0x0077:{int media=memory.readLong(sp);cpu.a[7]+=4;memory.writeLong(cpu.a[7],medias.containsKey(media)?1:0);return true;} // GetMediaSampleDescriptionCount
			case 0x0096:{int time=memory.readLong(sp+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],time);return true;} // TrackTimeToMediaTime(time, track): one edit at the start
			case 0x009c:{cpu.a[7]+=8;return true;} // SetMovieLanguage
			case 0x00fb:case 0x00fe:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);int[] b=m==null?new int[4]:m.box; // GetMovieDisplayBoundsRgn, GetMovieBoundsRgn: the movie box (a new region)
				memory.writeLong(cpu.a[7],tb.api.createHandle(HostQuickDraw68k.regionBytes(b[0],b[1],b[2],b[3])));return true;}
			case 0x0126:{cpu.a[7]+=20;memory.writeWord(cpu.a[7],USER_DATA_ITEM_NOT_FOUND);return true;} // GetUserDataItem: movies carry no user data
			case 0x014d:{cpu.a[7]+=18;memory.writeWord(cpu.a[7],USER_DATA_ITEM_NOT_FOUND);return true;} // GetUserDataText
			case 0x0127:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeLong(cpu.a[7],t==null?0:tracks(t.movie).indexOf(t)+1);return true;} // GetTrackID: the tracks are numbered in order
			case 0x0062:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.get(track);memory.writeLong(cpu.a[7],t==null?0:t.media);return true;} // GetTrackMedia
			case 0x0068:{int media=memory.readLong(sp);cpu.a[7]+=4;Track t=medias.get(media);memory.writeLong(cpu.a[7],t==null?0:t.data!=null&&t.data.media!=null?t.data.media.timeScale:t.movie.timeScale);return true;} // GetMediaTimeScale
			case 0x006a:{int media=memory.readLong(sp);cpu.a[7]+=4;Track t=medias.get(media);memory.writeLong(cpu.a[7],t==null?0:t.data!=null&&t.data.media!=null?(int)t.data.media.duration():(int)t.movie.duration());return true;} // GetMediaDuration
			case 0x006f:{int manufacturer=memory.readLong(sp),name=memory.readLong(sp+4),typePtr=memory.readLong(sp+8),media=memory.readLong(sp+12);cpu.a[7]+=16;Track t=medias.get(media); // GetMediaHandlerDescription(media, type*, Str255 name, manufacturer*)
				if(typePtr!=0)memory.writeLong(typePtr,t==null?0:t.type);if(manufacturer!=0)memory.writeLong(manufacturer,t==null?0:APPL);
				if(name!=0)tb.writePascal(name,t==null?"":t.type==VIDE?"Apple Video Media Handler":t.type==SOUN?"Apple Sound Media Handler":t.type==MUSI?"Apple Music Media Handler":"",255);return true;}
			case 0x0072:case 0x0073:{int media=memory.readLong(sp);cpu.a[7]+=4;Track t=medias.get(media);memory.writeWord(cpu.a[7],t==null&&media!=0?INVALID_MEDIA:0);return true;} // BeginMediaEdits / EndMediaEdits
			case 0x0078:{int desc=memory.readLong(sp),index=memory.readLong(sp+4),media=memory.readLong(sp+8);cpu.a[7]+=12;Track t=medias.get(media);
				if(t!=null&&t.data!=null&&t.data.media!=null){if(desc!=0&&index>=1&&index<=t.data.media.descriptions.size())tb.api.loadHandle(desc,t.data.media.descriptions.get(index-1));return true;}
				if(t!=null&&desc!=0&&index==1)writeSampleDescription(desc,t);return true;} // GetMediaSampleDescription(media, index, descH)
			case 0x007c:{int timePtr=memory.readLong(sp),flags=memory.readWord(sp+4),count=memory.readLong(sp+6),desc=memory.readLong(sp+10),duration=memory.readLong(sp+14),size=memory.readLong(sp+18),offset=memory.readLong(sp+22),data=memory.readLong(sp+26),media=memory.readLong(sp+30);cpu.a[7]+=34; // AddMediaSample(media, dataIn, inOffset, size, durationPerSample, description, numberOfSamples, sampleFlags, sampleTime*)
				memory.writeWord(cpu.a[7],addMediaSample(media,data,offset,size,duration,desc,count,flags,timePtr));return true;}
			case 0x0085:{cpu.a[7]+=20;memory.writeWord(cpu.a[7],INVALID_TRACK);return true;} // InsertTrackSegment(src, dst, srcIn, srcDuration, dstIn): no editing
			// the high-level editing routines: QTAssemble copies scenes of one movie and pastes them into a NewMovie, then adds another
			// movie's sound track (it stopped at CopyMovieSelection, "not supported")
			case 0x008d:case 0x008e:{int movie=memory.readLong(sp);cpu.a[7]+=4;memory.writeLong(cpu.a[7],copyMovieSelection(movie,selector==0x008d));return true;} // CutMovieSelection / CopyMovieSelection(movie) : Movie
			case 0x008f:case 0x0152:{int src=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;pasteMovieSelection(movie,src,selector==0x0152);return true;} // PasteMovieSelection / AddMovieSelection(movie, src)
			case 0x00e1:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m==null||m.edit==null)fail(INVALID_MOVIE);else clearSelection(m);return true;} // ClearMovieSelection(movie)
			case 0x0098:{int duration=memory.readLong(sp),start=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie); // GetMovieDataSize(movie, startTime, duration)
				long size=m==null||m.file==null?0:m.file.length();long total=m==null?0:m.duration();
				if(m!=null&&duration>=0&&total>0)size=size*Math.min(duration,total)/total;memory.writeLong(cpu.a[7],(int)size);return true;}
			case 0x0099:{cpu.a[7]+=12;memory.writeLong(cpu.a[7],0);return true;} // GetMediaDataSize: no Media objects
			case 0x009a:{int pt=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie); // PtInMovie(movie, pt)
				int v=(short)(pt>>16),h=(short)pt;tb.writeBoolean(cpu.a[7],m!=null&&v>=m.box[0]&&v<m.box[2]&&h>=m.box[1]&&h<m.box[3]);return true;}
			case 0x009e:{cpu.a[7]+=16;memory.writeWord(cpu.a[7],USER_DATA_ITEM_NOT_FOUND);return true;} // GetUserData: movies carry no user data
			case 0x009f:{cpu.a[7]+=12;memory.writeWord(cpu.a[7],0);return true;} // AddUserData
			case 0x00d7:{int resName=memory.readLong(sp),resIdPtr=memory.readLong(sp+4),ref=(short)memory.readWord(sp+8),movie=memory.readLong(sp+10);cpu.a[7]+=14; // AddMovieResource(movie, resRefNum, resId*, resName)
				int id=resIdPtr==0?0:(short)memory.readWord(resIdPtr);int[] used={0};int err=putMovieResource(movie,ref,id,resName,used);if(err==0&&resIdPtr!=0)memory.writeWord(resIdPtr,used[0]);if(err!=0)fail(err);memory.writeWord(cpu.a[7],err);return true;}
			case 0x00d8:{int resName=memory.readLong(sp),id=(short)memory.readWord(sp+4),ref=(short)memory.readWord(sp+6),movie=memory.readLong(sp+8);cpu.a[7]+=12; // UpdateMovieResource(movie, resRefNum, resId, resName)
				int err=putMovieResource(movie,ref,id,resName,new int[1]);if(err!=0)fail(err);memory.writeWord(cpu.a[7],err);return true;}
			case 0x00d9:{cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],false);return true;} // HasMovieChanged: never, there is no editing
			case 0x0113:{cpu.a[7]+=4;return true;} // ClearMovieChanged
			case 0x00b7:{int scale=memory.readLong(sp),record=memory.readLong(sp+4);cpu.a[7]+=8;convertTimeScale(record,scale);return true;} // ConvertTimeScale(TimeRecord*, newScale)
			case 0x00b8:{int param3=memory.readLong(sp),param2=memory.readLong(sp+4),param1=memory.readLong(sp+8),refCon=memory.readLong(sp+12),proc=memory.readLong(sp+16),cb=memory.readLong(sp+20);cpu.a[7]+=24; // CallMeWhen
				memory.writeWord(cpu.a[7],callMeWhen(cb,proc,refCon,param1,param2,param3));return true;}
			case 0x00b9:{int cb=memory.readLong(sp);cpu.a[7]+=4;CallBack c=callBacks.get(cb);if(c!=null)c.armed=false;memory.writeWord(cpu.a[7],c==null?INVALID_MOVIE:0);return true;} // CancelCallBack (Airburst at start-up)
			case 0x00b1:{int timeBase=memory.readLong(sp);cpu.a[7]+=4;Movie m=movieForTimeBase(timeBase);memory.writeLong(cpu.a[7],m==null?0:m.timeBaseFlags);return true;} // GetTimeBaseFlags (TNT Basic music)
			case 0x00b2:{int flags=memory.readLong(sp),timeBase=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movieForTimeBase(timeBase);if(m!=null)m.timeBaseFlags=flags&3;return true;} // SetTimeBaseFlags(tb, flags)
			case 0x00ed:{int cb=memory.readLong(sp);cpu.a[7]+=4;CallBack c=callBacks.get(cb);memory.writeLong(cpu.a[7],c==null?0:c.timeBase);return true;} // GetCallBackTimeBase
			case 0x00ee:{int cb=memory.readLong(sp);cpu.a[7]+=4;CallBack c=callBacks.get(cb);memory.writeWord(cpu.a[7],c==null?0:c.type);return true;} // GetCallBackType
			case 0x00d5:{int ref=(short)memory.readWord(sp);cpu.a[7]+=2;memory.writeWord(cpu.a[7],closeMovieFile(ref));return true;} // CloseMovieFile
			case 0x00dd:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);if(m!=null)settle(m);tb.writeBoolean(cpu.a[7],m==null||m.timeBaseFlags==0&&m.currentMillis()>=m.durationMillis());return true;} // IsMovieDone: a looping time base never is
			case 0x00eb:{int type=(short)memory.readWord(sp),timeBase=memory.readLong(sp+2);cpu.a[7]+=6;memory.writeLong(cpu.a[7],newCallBack(timeBase,type));return true;} // NewCallBack(tb, cbType)
			case 0x00ec:{int cb=memory.readLong(sp);cpu.a[7]+=4;if(callBacks.remove(cb)!=null)tb.api.disposeHandleAndFree(cb);return true;} // DisposeCallBack
			case 0x00f0:{int changed=memory.readLong(sp),flags=memory.readWord(sp+4),resName=memory.readLong(sp+6),resId=memory.readLong(sp+10),ref=(short)memory.readWord(sp+14),moviePtr=memory.readLong(sp+16);cpu.a[7]+=20; // NewMovieFromFile
				File file=openFiles.get(ref);int result=-2010;
				// newMovieActive (flag 1) makes it active; without it the movie stays still until SetMovieActive: L-ZONE's Director
				// projector opens its QuickTime casts with flags 0 and reads GetMovieActive - they played at 0,0 of the stage too
				if(file!=null){Movie m=newMovie(file);if(m!=null){m.active=(flags&NEW_MOVIE_ACTIVE)!=0;if(moviePtr!=0)memory.writeLong(moviePtr,m.handle);if(resId!=0)memory.writeWord(resId,128);if(changed!=0)memory.writeByte(changed,0);result=0;}}
				if(result!=0&&moviePtr!=0)memory.writeLong(moviePtr,0);memory.writeWord(cpu.a[7],result);return true;}
			case 0x00f1:{int changed=memory.readLong(sp),flags=memory.readWord(sp+4),handle=memory.readLong(sp+6),moviePtr=memory.readLong(sp+10);cpu.a[7]+=14; // NewMovieFromHandle(movie*, h, flags, dataRefWasChanged*)
				File file=handleMovieFile(handle==0?null:tb.api.handleContents(handle));Movie m=file!=null?newMovie(file):null;if(m!=null)m.active=(flags&NEW_MOVIE_ACTIVE)!=0;
				if(moviePtr!=0)memory.writeLong(moviePtr,m==null?0:m.handle);if(changed!=0)memory.writeByte(changed,0);memory.writeWord(cpu.a[7],m==null?COULD_NOT_RESOLVE_DATA_REF:0);return true;}
			case 0x00f3:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0x00010000:m.preferredRate);return true;} // GetMoviePreferredRate
			case 0x00f4:{int rate=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null)m.preferredRate=rate;return true;} // SetMoviePreferredRate
			case 0x00f6:{int volume=(short)memory.readWord(sp),movie=memory.readLong(sp+2);cpu.a[7]+=6;Movie m=movies.get(movie);if(m!=null)m.preferredVolume=volume;return true;} // SetMoviePreferredVolume
			case 0x00f7:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeLong(cpu.a[7],m==null?0:pictAt(m,m.posterTime));return true;} // GetMoviePosterPict
			case 0x0101:{cpu.a[7]+=8;return true;} // SetMovieClipRgn: movies draw unclipped into their port
			case 0x010e:{int durationPtr=memory.readLong(sp),timePtr=memory.readLong(sp+4),rate=memory.readLong(sp+8),time=memory.readLong(sp+12),flags=(short)memory.readWord(sp+22),movie=memory.readLong(sp+24);cpu.a[7]+=28; // GetMovieNextInterestingTime(movie, flags, numMediaTypes, whichMediaTypes*, time, rate, interestingTime*, interestingDuration*)
				long[] next=nextInterestingTime(movies.get(movie),flags,time,rate);if(timePtr!=0)memory.writeLong(timePtr,(int)next[0]);if(durationPtr!=0)memory.writeLong(durationPtr,(int)next[1]);return true;}
			case 0x0117:{int index=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);java.util.List<Track> list=m==null?null:tracks(m);memory.writeLong(cpu.a[7],list==null||index<1||index>list.size()?0:list.get(index-1).handle);return true;} // GetMovieIndTrack(movie, index)
			case 0x012e:{cpu.a[7]+=20;memory.writeWord(cpu.a[7],0);return true;} // SetUserDataItem
			case 0x014b:{cpu.a[7]+=8;memory.writeWord(cpu.a[7],0);return true;} // CountUserDataType
			case 0x0154:{int dst=memory.readLong(sp),src=memory.readLong(sp+4);cpu.a[7]+=8;Movie from=movies.get(src),to=movies.get(dst); // CopyMovieSettings(src, dst)
				if(from!=null&&to!=null){to.box=from.box.clone();to.preferredRate=from.preferredRate;to.preferredVolume=from.preferredVolume;setVolume(to,from.volume);}memory.writeWord(cpu.a[7],from==null||to==null?INVALID_MOVIE:0);return true;}
			case 0x0183:{int rate=memory.readLong(sp),duration=memory.readLong(sp+4),mediaTime=memory.readLong(sp+8),start=memory.readLong(sp+12),track=memory.readLong(sp+16);cpu.a[7]+=20; // InsertMediaIntoTrack(track, trackStart, mediaTime, mediaDuration, mediaRate)
				memory.writeWord(cpu.a[7],insertMediaIntoTrack(track,start,mediaTime,duration,rate));return true;}
			case 0x0187:{cpu.a[7]+=4;int handle=tb.api.createHandle(new byte[4]);Movie m=new Movie(handle);m.port=tb.api.currentQuickDrawPort();m.durationUnits=0;m.edit=new MovieWriter68k.MovieData(null);movies.put(handle,m);memory.writeLong(cpu.a[7],handle);return true;} // NewMovie(flags): an empty movie, to build
			case 0x0188:{int volume=(short)memory.readWord(sp),height=memory.readLong(sp+2),width=memory.readLong(sp+6),movie=memory.readLong(sp+10);cpu.a[7]+=14;memory.writeLong(cpu.a[7],newMovieTrack(movie,width,height,volume));return true;} // NewMovieTrack(movie, width, height, volume)
			case 0x018c:{cpu.a[7]+=8;memory.writeWord(cpu.a[7],INVALID_MOVIE);return true;} // PutMovieOnScrap
			case 0x018d:{cpu.a[7]+=4;memory.writeLong(cpu.a[7],0);return true;} // NewMovieFromScrap
			case 0x018e:{int refType=memory.readLong(sp),dataRef=memory.readLong(sp+4),scale=memory.readLong(sp+8),type=memory.readLong(sp+12),track=memory.readLong(sp+16);cpu.a[7]+=20;memory.writeLong(cpu.a[7],newTrackMedia(track,type,scale,dataRef,refType));return true;} // NewTrackMedia(track, mediaType, timeScale, dataRef, dataRefType)
			case 0x0061:{int media=memory.readLong(sp);cpu.a[7]+=4;Track t=medias.remove(media);if(t!=null){t.media=0;if(t.data!=null)t.data.media=null;tb.api.disposeHandleAndFree(media);}return true;} // DisposeTrackMedia
			case 0x0042:{int track=memory.readLong(sp);cpu.a[7]+=4;Track t=tracks.remove(track);if(t!=null){t.movie.tracks.remove(t);if(t.movie.edit!=null&&t.data!=null)t.movie.edit.tracks.remove(t.data);if(t.media!=0){medias.remove(t.media);tb.api.disposeHandleAndFree(t.media);}tb.api.disposeHandleAndFree(track);}return true;} // DisposeMovieTrack
			case 0x0191:{int moviePtr=memory.readLong(sp),refPtr=memory.readLong(sp+4),flags=memory.readLong(sp+8),creator=memory.readLong(sp+14),spec=memory.readLong(sp+18);cpu.a[7]+=22; // CreateMovieFile(spec, creator, scriptTag, flags, resRefNum*, newMovie*)
				memory.writeWord(cpu.a[7],createMovieFile(spec,creator,flags,refPtr,moviePtr));return true;}
			case 0x019a:{cpu.a[7]+=12;return true;} // SetMovieProgressProc: nothing here takes long enough to report
			case 0x01a1:case 0x01a2:{cpu.a[7]+=12;return true;} // SetMoviePlayHints / SetMediaPlayHints(movie or media, flags, mask): hints only (Otto Matic's music)
			case 0x019b:{int resName=memory.readLong(sp),resIdPtr=memory.readLong(sp+4),createFlags=memory.readLong(sp+8),creator=memory.readLong(sp+14),spec=memory.readLong(sp+18),flags=memory.readLong(sp+22),movie=memory.readLong(sp+26);cpu.a[7]+=30; // FlattenMovie(movie, flags, spec, creator, scriptTag, createFlags, resId*, resName)
				int err=flattenMovie(movie,flags,spec,creator,createFlags,resIdPtr,resName,false);if(err!=0)fail(err);return true;}
			case 0x019c:{int createFlags=memory.readLong(sp),creator=memory.readLong(sp+6),spec=memory.readLong(sp+10),flags=memory.readLong(sp+14),movie=memory.readLong(sp+18);cpu.a[7]+=22; // FlattenMovieData(movie, flags, spec, creator, scriptTag, createFlags) : Movie
				flattenedMovie=0;int err=flattenMovie(movie,flags,spec,creator,createFlags,0,0,true);if(err!=0)fail(err);memory.writeLong(cpu.a[7],err==0?flattenedMovie:0);return true;}
			case 0x01cb:{int resIdPtr=memory.readLong(sp+16),creator=memory.readLong(sp+22),output=memory.readLong(sp+26),input=memory.readLong(sp+30);cpu.a[7]+=34; // ConvertFileToMovieFile(inputFile, outputFile, creator, scriptTag, resID*, flags, userComp, proc, refCon)
				int err=convertFileToMovieFile(input,output,creator,resIdPtr);if(err!=0)fail(err);memory.writeWord(cpu.a[7],err);return true;} // a failure shows in GetMoviesError (UxMovie: "Can't translate to movie")
			case 0x01cc:{int creator=memory.readLong(sp+14),type=memory.readLong(sp+18),output=memory.readLong(sp+22),movie=memory.readLong(sp+30);cpu.a[7]+=34; // ConvertMovieToFile(movie, onlyTrack, outputFile, fileType, creator, scriptTag, resID*, flags, userComp)
				int err=convertMovieToFile(movies.get(movie),output,type,creator);if(err!=0)fail(err);memory.writeWord(cpu.a[7],err);return true;}
			case 0x0220:{int refType=memory.readLong(sp),dataRef=memory.readLong(sp+4),idPtr=memory.readLong(sp+8),flags=memory.readWord(sp+12),moviePtr=memory.readLong(sp+14);cpu.a[7]+=18; // NewMovieFromDataRef(Movie*, flags, id*, dataRef, dataRefType)
				File file=refType==0x616c6973&&dataRef!=0?tb.files.resolveAlias(tb.api.handleContents(dataRef)):null;Movie m=file!=null&&file.isFile()?newMovie(file):null;if(m!=null)m.active=(flags&NEW_MOVIE_ACTIVE)!=0;
				if(moviePtr!=0)memory.writeLong(moviePtr,m==null?0:m.handle);if(idPtr!=0)memory.writeWord(idPtr,m==null?0:128);memory.writeWord(cpu.a[7],m==null?INVALID_MOVIE:0);return true;}
			case 0x0233:{int flags=memory.readLong(sp),eraseColor=memory.readLong(sp+4),windowPtr=memory.readLong(sp+8),heightPtr=memory.readLong(sp+12),widthPtr=memory.readLong(sp+16),statePtr=memory.readLong(sp+24);cpu.a[7]+=28; // BeginFullScreen(restoreState*, gd, width*, height*, window*, eraseColor*, flags)
				memory.writeWord(cpu.a[7],beginFullScreen(statePtr,widthPtr,heightPtr,windowPtr,eraseColor,flags));return true;}
			case 0x0234:{int state=memory.readLong(sp+4);cpu.a[7]+=8;endFullScreen(state);memory.writeWord(cpu.a[7],0);return true;} // EndFullScreen(fullState, flags)
			case 0x00f5:{int movie=memory.readLong(sp);cpu.a[7]+=4;Movie m=movies.get(movie);memory.writeWord(cpu.a[7],m==null?0x0100:m.preferredVolume);return true;} // GetMoviePreferredVolume
			case 0x00f9:{int box=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null&&box!=0)writeRect(box,m.box);return true;} // GetMovieBox
			case 0x00fa:{int box=memory.readLong(sp),movie=memory.readLong(sp+4);cpu.a[7]+=8;Movie m=movies.get(movie);if(m!=null&&box!=0)m.box=readRect(box);return true;} // SetMovieBox
			case 0x018a:{int flags=memory.readLong(sp),rect=memory.readLong(sp+4),movie=memory.readLong(sp+8);cpu.a[7]+=12;Movie m=movies.get(movie);int ci=0; // NewMovieController(movie, movieRect, flags)
				if(m!=null){ci=tb.components.openMovieController();Controller c=controller(ci);if(c!=null){attach(c,m,m.port);if(rect!=0)position(c,m,readRect(rect),null,flags);}}
				memory.writeLong(cpu.a[7],ci);return true;}
			case 0x018b:{int ci=memory.readLong(sp);cpu.a[7]+=4;tb.components.close(ci);return true;} // DisposeMovieController
			case 0x0192:{int perm=memory.readWord(sp),refPtr=memory.readLong(sp+2),spec=memory.readLong(sp+6);cpu.a[7]+=10; // OpenMovieFile(spec, refNum*, permission)
				File file;try{file=tb.files.fileForSpec(spec);}catch(IOException e){file=null;}
				if(file==null||!file.isFile()){memory.writeWord(cpu.a[7],-43);return true;}
				int ref=nextRef++;openFiles.put(ref,file);if(refPtr!=0)memory.writeWord(refPtr,ref);memory.writeWord(cpu.a[7],0);return true;}
			default:if(Mac68kApi.TRACE)System.err.println("[qt] unsupported selector $"+Integer.toHexString(selector)+"\n"+cpu.dumpState(cpu.pc));throw new M68kMacApiException(cpu.pc,0xaaaa,String.format("QuickTime Movie Toolbox selector $%04X",selector));
		}
	}

	/** DeleteMovieFile: a movie file is deleted as FSpDelete would (UxMovie plays a 'myMV' resource from a temporary file and
	 *  deletes it when its window closes: the window could never be closed). */
	private int deleteMovieFile(int spec) throws M68kCpuException {
		File file;try{file=tb.files.fileForSpec(spec);}catch(IOException e){return -43;}
		if(file==null||!file.exists())return -43;
		if(file.isDirectory())return -1302; // notAFileErr
		try{return tb.files.fileManager.deleteObject(file,false);}catch(IOException e){return -36;}catch(SecurityException e){return -54;}
	}

	// ------------------------------------------------------------------ building and writing movies (MovieWriter68k)

	static final int CREATE_DELETE_CUR_FILE=0x80000000,CREATE_DONT_CREATE_MOVIE=0x40000000,CREATE_DONT_OPEN_FILE=0x20000000,CREATE_DONT_CREATE_RES_FILE=0x10000000;
	static final int FLATTEN_ADD_MOVIE_TO_DATA_FORK=1;
	static final int MOOV=0x6d6f6f76,MOOV_FILE=0x4d6f6f56;
	/** Resource files CreateMovieFile opened, with the movie file of each (CloseMovieFile closes them and seals the data fork). */
	private final Map<Integer,File> createdFiles=new HashMap<Integer,File>();
	/** The movie FlattenMovieData made last (its result). */
	private int flattenedMovie;

	/** The movie file an FSSpec names, made empty (createMovieFileDeleteCurFile replaces one there) with the type MooV. */
	private int newMovieFile(File file,int creator,int flags) throws M68kCpuException {
		if(file.isDirectory())return -1302;
		if(!Sandbox.allowFile(file,Sandbox.Access.WRITE,tb.name))return -54; // permErr
		if(file.exists()&&(flags&CREATE_DELETE_CUR_FILE)==0)return -48; // dupFNErr
		try{
			if(file.exists()){int err=tb.files.fileManager.deleteObject(file,false);if(err!=0)return err;}
			MovieWriter68k.startData(file);
			byte[] info=new byte[16];MovieWriter68k.put4(info,0,MOOV_FILE);MovieWriter68k.put4(info,4,creator);FinderInfo68k.write(file,info);
			return 0;
		}catch(IOException e){return -36;}
	}
	/** ConvertFileToMovieFile: a file the movie import reads (a MIDI file, sound, a movie) becomes a movie file. The import is
	 *  done when the movie is opened, so the output keeps the input's data under the movie file type (BladeStory -BlackHeart-'s
	 *  MIDI XFCN converts a .mid this way, opens the result and starts it). */
	private int convertFileToMovieFile(int inputSpec,int outputSpec,int creator,int resIdPtr) throws M68kCpuException {
		File input,output;
		try{input=inputSpec==0?null:tb.files.fileForSpec(inputSpec);output=outputSpec==0?null:tb.files.fileForSpec(outputSpec);}catch(IOException e){return -43;}
		if(input==null||!input.isFile()||output==null)return -43;
		TQTMovie.MovieAsset asset=TQTMovie.assetFor(input);
		if(asset==null||!asset.hasVideo()&&!asset.hasAudio()&&asset.musicSequence==null)return -2003; // cantFindHandler
		if(output.isDirectory())return -1302;
		if(!Sandbox.allowFile(output,Sandbox.Access.WRITE,tb.name))return -54;
		try{
			if(!output.getCanonicalFile().equals(input.getCanonicalFile()))java.nio.file.Files.copy(input.toPath(),output.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			byte[] info=new byte[16];MovieWriter68k.put4(info,0,MOOV_FILE);MovieWriter68k.put4(info,4,creator);FinderInfo68k.write(output,info);
		}catch(IOException e){return -36;}
		if(resIdPtr!=0)memory.writeWord(resIdPtr,128);
		return 0;
	}
	static final int MIDI_FILE=0x4d696469; // 'Midi'
	/** ConvertMovieToFile: a music movie to a Standard MIDI File ('Midi': UxPlayMIDI's smfSave builds a 'musi' movie in a temporary
	 *  file and exports it); other exports are not available (wrPermErr, as before). */
	private int convertMovieToFile(Movie m,int outputSpec,int type,int creator) throws M68kCpuException {
		if(m==null)return INVALID_MOVIE;
		if(type!=MIDI_FILE)return WR_PERM_ERR;
		File output;try{output=outputSpec==0?null:tb.files.fileForSpec(outputSpec);}catch(IOException e){return -43;}
		if(output==null)return -43;if(output.isDirectory())return -1302;
		if(!Sandbox.allowFile(output,Sandbox.Access.WRITE,tb.name))return -54;
		javax.sound.midi.Sequence sequence=null;
		try{sequence=m.asset!=null&&m.asset.musicSequence!=null?m.asset.musicSequence:m.file!=null?QTMusicConverter.convert(m.file):null;}
		catch(IOException|javax.sound.midi.InvalidMidiDataException|RuntimeException e){sequence=null;}
		if(sequence==null)return -2003; // cantFindHandler: no music in the movie
		try{
			int[] types=MidiSystem.getMidiFileTypes(sequence);int fileType=types.length==0?0:types[0];for(int t:types)if(t==1)fileType=1;
			MidiSystem.write(sequence,fileType,output);
			byte[] info=new byte[16];MovieWriter68k.put4(info,0,MIDI_FILE);MovieWriter68k.put4(info,4,creator);FinderInfo68k.write(output,info);
		}catch(IOException|IllegalArgumentException e){return -36;}
		return 0;
	}
	/** CreateMovieFile: the file (its data fork takes the samples), its resource fork open (resRefNum) and a new movie to build. */
	private int createMovieFile(int spec,int creator,int flags,int refPtr,int moviePtr) throws M68kCpuException {
		if(refPtr!=0)memory.writeWord(refPtr,0);if(moviePtr!=0)memory.writeLong(moviePtr,0);
		File file;try{file=spec==0?null:tb.files.fileForSpec(spec);}catch(IOException e){file=null;}
		if(file==null){fail(-43);return -43;}
		int err=newMovieFile(file,creator,flags);if(err!=0){fail(err);return err;}
		if((flags&CREATE_DONT_CREATE_RES_FILE)==0&&(flags&CREATE_DONT_OPEN_FILE)==0){
			tb.resources.createFile(file);int saved=tb.resources.currentRefNum();int ref=tb.resources.openFile(file);
			if(saved!=0)tb.resources.useResFile(saved); // CreateMovieFile leaves the current resource file as it was
			if(ref>0){createdFiles.put(ref,file);if(refPtr!=0)memory.writeWord(refPtr,ref);}
		}
		if((flags&CREATE_DONT_CREATE_MOVIE)==0){
			int handle=tb.api.createHandle(new byte[4]);Movie m=new Movie(handle);m.port=tb.api.currentQuickDrawPort();m.edit=new MovieWriter68k.MovieData(file);m.file=file;
			movies.put(handle,m);if(moviePtr!=0)memory.writeLong(moviePtr,handle);
		}
		if(QT_TRACE)System.err.println("[qt] CreateMovieFile "+file+" flags $"+Integer.toHexString(flags));
		return 0;
	}
	/** NewMovieTrack: a track of the movie being built, of that size (Fixed) and volume; its media comes with NewTrackMedia. */
	private int newMovieTrack(int movie,int width,int height,int volume) throws M68kCpuException {
		Movie m=movies.get(movie);if(m==null||m.edit==null){fail(INVALID_MOVIE);return 0;}
		Track t=new Track(tb.api.createHandle(new byte[4]),0,0,m);t.data=new MovieWriter68k.TrackData(m.edit.nextTrackId++,width,height,volume);
		m.edit.tracks.add(t.data);m.tracks.add(t);tracks.put(t.handle,t);
		if(width>0&&height>0&&m.box[2]==m.box[0])m.box=new int[]{0,0,height>>16,width>>16};
		return t.handle;
	}
	/** Seals the 'mdat' of a media's data file the program wrote; never the movie file a copied selection's samples are in (its first atom is no header of ours). */
	private void sealData(MovieWriter68k.MediaData md) throws IOException {if(!borrowedMedia.contains(md))MovieWriter68k.closeData(md.dataFile);}
	/** Media of file movies taken into the editing form (their samples stay in the movie's own file). */
	private final java.util.Set<MovieWriter68k.MediaData> borrowedMedia=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MovieWriter68k.MediaData,Boolean>());

	// ------------------------------------------------------------------ editing: Cut/Copy/Paste/Add/ClearMovieSelection
	// A movie in the editing form is its tracks' edit lists over their media's samples (MovieWriter68k). A copy shares the media
	// and has the edits of the selection; pasting inserts those edits at the destination's selection (the other tracks get an
	// empty edit there, as QuickTime moves everything after the insertion point); adding lays them over the movie from there.

	/** The movie in the editing form: what the program built, or a file movie's tracks (video and sound only) with all their samples. */
	private MovieWriter68k.MovieData editForm(Movie m){
		if(m.edit!=null)return m.edit;
		if(m.fileEdit!=null||m.file==null)return m.fileEdit;
		MovieWriter68k.MovieData d=new MovieWriter68k.MovieData(null);
		try{QuickTimeFile q=QuickTimeFile.open(m.file);
			try{d.timeScale=q.timeScale>0?q.timeScale:m.timeScale;
				for(QuickTimeFile.Track t:q.tracks){
					QuickTimeFile.Media md=t.media;if(md==null||md.descriptions.isEmpty())continue;
					int type=md.handlerType;if(type!=VIDE&&type!=SOUN)continue; // the writer knows video and sound media only
					File data=md.dataFile(md.description(1));if(data==null)continue;
					boolean oneFile=true;for(QuickTimeFile.SampleDescription sd:md.descriptions)if(!data.equals(md.dataFile(sd)))oneFile=false;
					if(!oneFile)continue;
					MovieWriter68k.MediaData media=new MovieWriter68k.MediaData(type,md.timeScale,data);
					for(QuickTimeFile.SampleDescription sd:md.descriptions)media.descriptions.add(sd.bytes.clone());
					if(type==VIDE){int n=md.samples();for(int i=0;i<n;i++)media.chunks.add(new MovieWriter68k.Chunk(data,md.sampleOffset(i),md.storedSize(i),1,(int)md.sampleDuration(i),md.chunkDescription(md.chunkOf(i)),md.sync(i)));}
					else for(int c=0;c<md.chunkCount();c++){int k=md.chunkSamples(c);if(k<=0)continue;long first=md.chunkFirstSample(c); // sound: a chunk at a time, the sample size as stsz has it
						media.chunks.add(new MovieWriter68k.Chunk(data,md.chunkOffsets[c],md.storedSize(first)*k,k,(int)Math.max(1,md.sampleDuration(first)),md.chunkDescription(c),true));}
					MovieWriter68k.TrackData td=new MovieWriter68k.TrackData(d.nextTrackId++,type==VIDE?t.width:0,type==VIDE?t.height:0,t.volume);td.media=media;
					for(QuickTimeFile.Edit e:t.edits)td.edits.add(new MovieWriter68k.EditData(e.duration,e.mediaTime,e.rate));
					if(!t.enabled())td.edits.clear(); // a track the file disables shows nothing
					borrowedMedia.add(media);d.tracks.add(td);
				}
			}finally{q.close();}
		}catch(IOException|RuntimeException e){if(Mac68kApi.TRACE)System.err.println("[qt] no editing form of "+m.file+": "+e);return null;}
		return m.fileEdit=d;
	}
	/** Whether the program left the track of that editing-form track enabled (SetTrackEnabled; a file movie's are one per media type). */
	private boolean trackEnabled(Movie m,MovieWriter68k.TrackData td) throws M68kCpuException {
		if(m.edit!=null){for(Track t:m.tracks)if(t.data==td)return t.enabled;return true;}
		for(Track t:tracks(m))if(t.type==td.media.type)return t.enabled;
		return true;
	}
	/** The edits of [from, to) movie time (units of {@code movieScale}); a cut-through edit starts later in its media. */
	private static java.util.List<MovieWriter68k.EditData> sliceEdits(java.util.List<MovieWriter68k.EditData> edits,long from,long to,int movieScale,int mediaScale){
		java.util.List<MovieWriter68k.EditData> out=new ArrayList<MovieWriter68k.EditData>();long t=0;
		for(MovieWriter68k.EditData e:edits){
			long a=Math.max(from,t),b=Math.min(to,t+e.duration);
			if(b>a){long media=e.mediaTime<0?-1:e.mediaTime+Math.round((a-t)*(double)mediaScale/movieScale*(e.rate/65536.0));out.add(new MovieWriter68k.EditData(b-a,media,e.rate));}
			t+=e.duration;if(t>=to)break;
		}
		return out;
	}
	private static long editsDuration(java.util.List<MovieWriter68k.EditData> edits){long d=0;for(MovieWriter68k.EditData e:edits)d+=e.duration;return d;}
	/** Edits with a list inserted at {@code at} (an empty edit first when the track ends before it). */
	private static java.util.List<MovieWriter68k.EditData> insertEdits(java.util.List<MovieWriter68k.EditData> edits,long at,java.util.List<MovieWriter68k.EditData> inserted,int movieScale,int mediaScale){
		java.util.List<MovieWriter68k.EditData> out=sliceEdits(edits,0,at,movieScale,mediaScale);long end=editsDuration(out);
		if(at>end&&!inserted.isEmpty())out.add(new MovieWriter68k.EditData(at-end,-1,0x10000));
		out.addAll(inserted);out.addAll(sliceEdits(edits,at,Long.MAX_VALUE,movieScale,mediaScale));
		return out;
	}
	private static int mediaScale(MovieWriter68k.TrackData td){return td.media!=null?td.media.timeScale:MovieWriter68k.MOVIE_TIME_SCALE;}
	private static int editScale(Movie m,MovieWriter68k.MovieData d){return d.timeScale>0?d.timeScale:m.timeScale;}
	/** CopyMovieSelection / CutMovieSelection: a new movie of the selection's enabled tracks (a cut takes it out of an edited movie). */
	int copyMovieSelection(int movie,boolean cut) throws M68kCpuException {
		Movie m=movies.get(movie);MovieWriter68k.MovieData d=m==null?null:editForm(m);
		if(d==null){fail(INVALID_MOVIE);return 0;}
		int scale=editScale(m,d);long from=(long)m.selectionTime*scale/m.timeScale,to=from+(long)m.selectionDuration*scale/m.timeScale;
		int handle=tb.api.createHandle(new byte[4]);Movie c=new Movie(handle);c.port=m.port;c.durationUnits=0;
		c.edit=new MovieWriter68k.MovieData(null);c.edit.timeScale=scale;c.timeScale=scale;
		for(MovieWriter68k.TrackData td:d.tracks){
			if(td.media==null||!trackEnabled(m,td))continue;
			java.util.List<MovieWriter68k.EditData> part=sliceEdits(td.edits,from,to,scale,td.media.timeScale);
			if(part.isEmpty())continue;
			addEditTrack(c,td,part);
		}
		int[] n=c.naturalBox();c.box=n;
		movies.put(handle,c);
		if(cut){if(m.edit!=null)clearSelection(m);else fail(INVALID_MOVIE);} // a file movie is not edited here: its copy is still made
		return handle;
	}
	/** A track of the edited movie {@code c} over the media of {@code like}, with those edits. */
	private MovieWriter68k.TrackData addEditTrack(Movie c,MovieWriter68k.TrackData like,java.util.List<MovieWriter68k.EditData> edits) throws M68kCpuException {
		MovieWriter68k.TrackData nt=new MovieWriter68k.TrackData(c.edit.nextTrackId++,like.width,like.height,like.volume);nt.media=like.media;nt.edits.addAll(edits);
		c.edit.tracks.add(nt);
		Track t=new Track(tb.api.createHandle(new byte[4]),tb.api.createHandle(new byte[4]),like.media.type,c);t.data=nt;c.tracks.add(t);tracks.put(t.handle,t);medias.put(t.media,t);
		return nt;
	}
	/** ClearMovieSelection: the selection's time leaves every track; the selection is its start then. */
	void clearSelection(Movie m){
		int scale=editScale(m,m.edit);long from=(long)m.selectionTime*scale/m.timeScale,to=from+(long)m.selectionDuration*scale/m.timeScale;
		if(to<=from)return;
		for(MovieWriter68k.TrackData td:m.edit.tracks){java.util.List<MovieWriter68k.EditData> kept=sliceEdits(td.edits,0,from,scale,mediaScale(td));kept.addAll(sliceEdits(td.edits,to,Long.MAX_VALUE,scale,mediaScale(td)));td.edits.clear();td.edits.addAll(kept);}
		m.selectionDuration=0;
	}
	/**
	 * PasteMovieSelection: {@code src} goes in at the selection (replacing what is selected), and the pasted part is the selection
	 * then. AddMovieSelection lays src's tracks over the movie from the selection's start instead, scaled to a selection that has
	 * a length. A source track goes on in a destination track over the same media (the scenes of one movie make one track),
	 * else into a new track.
	 */
	void pasteMovieSelection(int movie,int source,boolean add) throws M68kCpuException {
		Movie m=movies.get(movie),s=movies.get(source);MovieWriter68k.MovieData sd=s==null?null:editForm(s);
		if(m==null||m.edit==null||sd==null){fail(INVALID_MOVIE);return;}
		int scale=editScale(m,m.edit),srcScale=editScale(s,sd);
		if(!add&&m.selectionDuration>0)clearSelection(m);
		long at=(long)m.selectionTime*scale/m.timeScale,length=(long)m.selectionDuration*scale/m.timeScale;
		long srcDuration=0;for(MovieWriter68k.TrackData td:sd.tracks)if(td.media!=null&&trackEnabled(s,td))srcDuration=Math.max(srcDuration,editsDuration(td.edits));
		long duration=srcDuration*scale/srcScale;
		double stretch=add&&length>0&&duration>0?(double)length/duration:1;
		java.util.Map<MovieWriter68k.TrackData,java.util.List<MovieWriter68k.EditData>> pasted=new java.util.LinkedHashMap<MovieWriter68k.TrackData,java.util.List<MovieWriter68k.EditData>>();
		for(MovieWriter68k.TrackData td:sd.tracks){
			if(td.media==null||!trackEnabled(s,td))continue;
			java.util.List<MovieWriter68k.EditData> e=new ArrayList<MovieWriter68k.EditData>();
			for(MovieWriter68k.EditData x:td.edits)e.add(new MovieWriter68k.EditData(Math.round(x.duration*(double)scale/srcScale*stretch),x.mediaTime,stretch==1?x.rate:(int)Math.round(x.rate/stretch)));
			pasted.put(td,e);
		}
		if(!add){ // the movie makes room: every track gets the pasted time at the insertion point
			for(MovieWriter68k.TrackData td:new ArrayList<MovieWriter68k.TrackData>(m.edit.tracks)){
				java.util.List<MovieWriter68k.EditData> own=null;
				for(java.util.Map.Entry<MovieWriter68k.TrackData,java.util.List<MovieWriter68k.EditData>> e:pasted.entrySet())if(e.getKey().media==td.media){own=e.getValue();pasted.remove(e.getKey());break;}
				if(own==null&&at>=editsDuration(td.edits))continue; // nothing after the insertion point to move
				java.util.List<MovieWriter68k.EditData> insert=own!=null?own:java.util.Collections.singletonList(new MovieWriter68k.EditData(duration,-1,0x10000));
				java.util.List<MovieWriter68k.EditData> edited=insertEdits(td.edits,at,insert,scale,mediaScale(td));td.edits.clear();td.edits.addAll(edited);
			}
		}
		for(java.util.Map.Entry<MovieWriter68k.TrackData,java.util.List<MovieWriter68k.EditData>> e:pasted.entrySet()){
			java.util.List<MovieWriter68k.EditData> edits=new ArrayList<MovieWriter68k.EditData>();
			if(at>0)edits.add(new MovieWriter68k.EditData(at,-1,0x10000));
			edits.addAll(e.getValue());
			addEditTrack(m,e.getKey(),edits);
		}
		if(m.box[2]==m.box[0]||m.box[3]==m.box[1])m.box=m.naturalBox();
		if(!add){m.selectionTime=(int)(at*m.timeScale/scale);m.selectionDuration=(int)(duration*m.timeScale/scale);}
	}

	/** NewTrackMedia: the track's media of that type and time scale; its samples go to the data reference's file ('alis'), else
	 *  the movie's own file, else a temporary file. */
	private int newTrackMedia(int track,int type,int timeScale,int dataRef,int refType) throws M68kCpuException {
		Track t=tracks.get(track);if(t==null||t.data==null){fail(INVALID_TRACK);return 0;}
		File data=null;
		if(dataRef!=0&&refType==MovieWriter68k.ALIS){byte[] alias=tb.api.handleContents(dataRef);if(alias!=null)data=tb.files.resolveAlias(alias);}
		if(data==null)data=t.movie.edit.file;
		if(data==null){try{data=TempFiles.createFile("hzmovie",".mdat");}catch(IOException e){fail(-36);return 0;}}
		if(t.media!=0){medias.remove(t.media);tb.api.disposeHandleAndFree(t.media);}
		t.media=tb.api.createHandle(new byte[4]);t.type=type;t.data.media=new MovieWriter68k.MediaData(type,timeScale,data);medias.put(t.media,t);
		return t.media;
	}
	/** AddMediaSample: the bytes go to the end of the media's data file, the samples to its table; *sampleTime = where they start. */
	private int addMediaSample(int media,int dataIn,int offset,int size,int duration,int desc,int count,int flags,int timePtr) throws M68kCpuException {
		Track t=medias.get(media);if(t==null||t.data==null||t.data.media==null){fail(INVALID_MEDIA);return INVALID_MEDIA;}
		MovieWriter68k.MediaData md=t.data.media;
		int p=dataIn==0?0:memory.readLong(dataIn);byte[] description=desc==0?null:tb.api.handleContents(desc);
		if(p==0||size<0||description==null||description.length<16){fail(-50);return -50;}
		if(!Sandbox.allowFile(md.dataFile,Sandbox.Access.WRITE,tb.name)){fail(-54);return -54;}
		long start=md.duration();
		try{
			long at=MovieWriter68k.append(md.dataFile,memory.readBytes(p+offset,size),0,size);
			md.chunks.add(new MovieWriter68k.Chunk(md.dataFile,at,size,count,duration,md.describe(description),(flags&1)==0));
		}catch(IOException e){fail(-36);return -36;}
		if(timePtr!=0)memory.writeLong(timePtr,(int)start);
		return 0;
	}
	/** InsertMediaIntoTrack: that part of the media shows in the track from trackStart on (-1 or the end: appended; a gap before it is an empty edit). */
	private int insertMediaIntoTrack(int track,int start,int mediaTime,int duration,int rate) throws M68kCpuException {
		Track t=tracks.get(track);if(t==null||t.data==null||t.data.media==null){fail(INVALID_TRACK);return INVALID_TRACK;}
		MovieWriter68k.TrackData td=t.data;long end=td.duration();
		long movieDuration=(long)duration*t.movie.edit.timeScale/td.media.timeScale;
		if(rate!=0&&rate!=0x10000)movieDuration=movieDuration*0x10000/rate;
		if(start>end)td.edits.add(new MovieWriter68k.EditData(start-end,-1,0x10000));
		td.edits.add(new MovieWriter68k.EditData(movieDuration,mediaTime,rate==0?0x10000:rate));
		return 0;
	}
	/** AddMovieResource / UpdateMovieResource: the movie's 'moov' resource in the open resource file {@code ref}. */
	private int putMovieResource(int movie,int ref,int id,int resName,int[] used) throws M68kCpuException {
		Movie m=movies.get(movie);if(m==null)return INVALID_MOVIE;
		File file=tb.resources.fileOfRefNum(ref);if(file==null)return -193; // resFNotFound
		byte[] atom;
		if(m.edit!=null){
			for(MovieWriter68k.TrackData t:m.edit.tracks)if(t.media!=null)try{sealData(t.media);}catch(IOException e){}
			atom=MovieWriter68k.moov(m.edit,null,file,f->tb.files.makeAlias(f));
		}else if(m.file!=null){
			try{QuickTimeFile q=QuickTimeFile.open(m.file);try{atom=q.moov;}finally{q.close();}}catch(IOException e){return COULD_NOT_RESOLVE_DATA_REF;}
		}else return INVALID_MOVIE;
		if(id==0)id=tb.resources.unusedId(ref,MOOV);
		int err=tb.resources.putResource(ref,MOOV,id,atom);
		if(err==0&&resName!=0)tb.resources.setResourceName(ref,MOOV,id,tb.readPascal(resName));
		used[0]=id;return err;
	}
	/** CloseMovieFile: a resource file CreateMovieFile opened is written and closed, its data fork's 'mdat' sealed. */
	private int closeMovieFile(int ref) throws M68kCpuException {
		openFiles.remove(ref);
		File file=createdFiles.remove(ref);
		if(file!=null){try{MovieWriter68k.closeData(file);}catch(IOException e){}tb.resources.closeResFile(ref);}
		return 0;
	}
	/**
	 * FlattenMovie / FlattenMovieData: a self-contained movie file - every sample and the movie atom in its data fork with
	 * flattenAddMovieToDataFork (FlattenMovieData's usual call), else the samples there and the movie a 'moov' resource.
	 * FlattenMovieData opens the new file's movie ({@link #flattenedMovie}). UxCardToMovie writes its card movie so,
	 * MyMovieTool flattens a movie file before keeping it as a 'myMV' resource.
	 */
	private int flattenMovie(int movie,int flags,int spec,int creator,int createFlags,int resIdPtr,int resName,boolean open) throws M68kCpuException {
		Movie m=movies.get(movie);if(m==null)return INVALID_MOVIE;
		File out;try{out=spec==0?null:tb.files.fileForSpec(spec);}catch(IOException e){out=null;}
		if(out==null)return -43;
		if(m.file!=null&&sameFile(m.file,out))return -47; // fBsyErr: the movie's own file
		int err=newMovieFile(out,creator,createFlags); // an existing file only with createMovieFileDeleteCurFile
		if(err!=0){if(QT_TRACE)System.err.println("[qt] Flatten -> "+out+": "+err);return err;}
		try{
			if(m.edit!=null){for(MovieWriter68k.TrackData t:m.edit.tracks)if(t.media!=null)sealData(t.media);MovieWriter68k.flatten(m.edit,out);}
			else if(m.file==null||!MovieWriter68k.flattenFile(m.file,out))return COULD_NOT_RESOLVE_DATA_REF;
		}catch(IOException e){return -36;}
		byte[] info=new byte[16];MovieWriter68k.put4(info,0,MOOV_FILE);MovieWriter68k.put4(info,4,creator);FinderInfo68k.write(out,info);
		if((flags&FLATTEN_ADD_MOVIE_TO_DATA_FORK)==0){ // the movie as a resource too, beside the samples
			tb.resources.createFile(out);int saved=tb.resources.currentRefNum();int ref=tb.resources.openFile(out);
			if(ref>0){try{QuickTimeFile q=QuickTimeFile.open(out,-1);try{int id=resIdPtr==0?0:(short)memory.readWord(resIdPtr);if(id==0)id=128;
					tb.resources.putResource(ref,MOOV,id,q.moov);if(resName!=0)tb.resources.setResourceName(ref,MOOV,id,tb.readPascal(resName));if(resIdPtr!=0)memory.writeWord(resIdPtr,id);}finally{q.close();}}
				catch(IOException e){}
				tb.resources.closeResFile(ref);}
			if(saved!=0)tb.resources.useResFile(saved);
		}
		if(open){Movie made=newMovie(out);flattenedMovie=made==null?0:made.handle;}
		if(QT_TRACE)System.err.println("[qt] Flatten "+(m.edit!=null?"built movie":m.file)+" -> "+out+" ("+out.length()+" bytes)");
		return 0;
	}
	private static boolean sameFile(File a,File b){try{return a.getCanonicalFile().equals(b.getCanonicalFile());}catch(IOException e){return a.equals(b);}}

	/**
	 * The movie file a movie resource kept in a handle plays from (Pictoids 2.0 keeps its 'moov' resources in the stack): the
	 * alias of its first data reference. An alias made on another Mac (volume "Pocket") is looked up by the file name in the
	 * stack's folder and the folders under it, as QuickTime's "Search" button of its "cannot be found" alert would find it.
	 */
	private File handleMovieFile(byte[] moov){
		byte[] alias=moov==null?null:firstAliasDataRef(moov,0,moov.length,0);
		if(alias==null)return null;
		File file=tb.files.resolveAlias(alias);
		if(file!=null&&file.isFile())return file;
		if(alias.length<0x34)return null;
		String name=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,0x33,0x33+Math.min(63,Math.min(alias[0x32]&0xff,alias.length-0x33))));
		File folder=tb.files.fileManager.stackFolder();
		return name.isEmpty()||folder==null?null:findByName(folder,name,3);
	}
	private static byte[] firstAliasDataRef(byte[] b,int at,int end,int depth){
		while(at+8<=end&&depth<8){
			int size=MacApp68k.u4(b,at);String type=new String(b,at+4,4,java.nio.charset.StandardCharsets.ISO_8859_1);
			if(size<8||at+size>end)return null;
			if(type.equals("moov")||type.equals("trak")||type.equals("mdia")||type.equals("minf")||type.equals("dinf")){byte[] r=firstAliasDataRef(b,at+8,at+size,depth+1);if(r!=null)return r;}
			else if(type.equals("dref")&&size>=16){
				int entry=at+16;
				for(int n=MacApp68k.u4(b,at+12);n>0&&entry+12<=at+size;n--){int es=MacApp68k.u4(b,entry);if(es<12||entry+es>at+size)break;
					boolean self=(MacApp68k.u4(b,entry+8)&1)!=0;
					if(!self&&new String(b,entry+4,4,java.nio.charset.StandardCharsets.ISO_8859_1).equals("alis"))return java.util.Arrays.copyOfRange(b,entry+12,entry+es);
					entry+=es;}
			}
			at+=size;
		}
		return null;
	}
	private static File findByName(File folder,String name,int depth){
		File[] list=folder.listFiles();if(list==null)return null;
		for(File f:list)if(f.isFile()&&f.getName().equalsIgnoreCase(name))return f;
		if(depth>0)for(File f:list)if(f.isDirectory()&&!f.getName().startsWith(".")){File r=findByName(f,name,depth-1);if(r!=null)return r;}
		return null;
	}

	/** The movie with that Handle (null: none). */
	Movie movie(int handle){return movies.get(handle);}
	Movie newMovie(File file) throws M68kCpuException {
		int handle=tb.api.createHandle(new byte[4]);
		Movie m=new Movie(handle);m.file=file;m.asset=TQTMovie.assetFor(file);
		if(m.asset.timeScale>0){m.timeScale=m.asset.timeScale;m.durationUnits=m.asset.duration;}
		// A sound-only movie has an empty box, like the real Movie Toolbox reports.
		if(m.hasFrames())m.box=new int[]{0,0,m.asset.frameHeight(),m.asset.frameWidth()};
		readHeader(m,file);
		m.port=tb.api.currentQuickDrawPort();
		openAudio(m);
		if(m.durationUnits<0)m.durationUnits=m.units(m.durationMillis());
		movies.put(handle,m);return m;
	}

	/** The movie header's preview and poster times; for a movie with nothing decodable, its tracks and box as the file lists them. */
	private void readHeader(Movie m,File file){
		QuickTimeFile q;try{q=QuickTimeFile.open(file);q.close();}catch(IOException|RuntimeException e){return;}
		m.previewTime=q.previewTime;m.previewDuration=q.previewDuration;m.posterTime=q.posterTime;
		if(m.hasFrames())m.frameTimes=frameTimes(q,m.timeScale);
		if(m.asset!=null&&(m.asset.hasVideo()||m.asset.hasAudio()||m.asset.musicSequence!=null)||q.tracks.isEmpty())return;
		m.fileTracks=new ArrayList<int[]>();
		for(QuickTimeFile.Track t:q.tracks)m.fileTracks.add(new int[]{t.media==null?0:t.media.handlerType,t.width,t.height});
		int[] box=q.box();if(box[2]>box[0]&&box[3]>box[1]){m.fileBox=new int[]{0,0,box[2]-box[0],box[3]-box[1]};m.box=m.fileBox.clone();}
	}
	/** The start times of the samples of the first enabled video track, mapped through its edits into movie units. */
	private static long[] frameTimes(QuickTimeFile q,int movieScale){
		for(QuickTimeFile.Track t:q.tracks){
			if(!t.enabled()||t.media==null||t.media.handlerType!=VIDE||t.media.timeScale<=0)continue;
			QuickTimeFile.Media md=t.media;int n=md.samples();java.util.TreeSet<Long> times=new java.util.TreeSet<Long>();
			long[] starts=t.editStarts();
			for(int e=0;e<t.edits.size();e++){
				QuickTimeFile.Edit edit=t.edits.get(e);if(edit.mediaTime<0||edit.rate==0)continue;
				double perMedia=movieScale/(double)md.timeScale/(edit.rate/65536.0);
				for(int i=0;i<n;i++){
					long st=md.sampleTime(i),at=starts[e]+(long)Math.ceil((st-edit.mediaTime)*perMedia-1e-9);
					if(st+md.sampleDuration(i)<=edit.mediaTime)continue;
					if(at>=starts[e]+edit.duration)break;
					times.add(Math.max(starts[e],at));
				}
			}
			long[] out=new long[times.size()];int i=0;for(long v:times)out[i++]=v;return out;
		}
		return null;
	}
	private int timeBase(Movie m) throws M68kCpuException {if(m.timeBase==0)m.timeBase=tb.api.createHandle(new byte[4]);return m.timeBase;}
	private Movie movieForTimeBase(int timeBase){if(timeBase==0)return null;for(Movie m:movies.values())if(m.timeBase==timeBase)return m;return null;}

	// ------------------------------------------------------------------ tracks, media, matrix

	private java.util.List<Track> tracks(Movie m) throws M68kCpuException {
		if(m.edit!=null)return m.tracks;
		if(m.tracks.isEmpty()&&m.asset!=null){
			if(m.hasFrames())addTrack(m,VIDE);
			if(m.asset.hasAudio())addTrack(m,SOUN);
			if(m.asset.musicSequence!=null)addTrack(m,MUSI);
			if(m.tracks.isEmpty()&&m.fileTracks!=null)for(int[] f:m.fileTracks){addTrack(m,f[0]);Track t=m.tracks.get(m.tracks.size()-1);t.width=f[1];t.height=f[2];}
		}
		return m.tracks;
	}
	private void addTrack(Movie m,int type) throws M68kCpuException {
		Track t=new Track(tb.api.createHandle(new byte[4]),tb.api.createHandle(new byte[4]),type,m);
		m.tracks.add(t);tracks.put(t.handle,t);medias.put(t.media,t);
	}
	private void disposeTracks(Movie m) throws M68kCpuException {
		for(Track t:m.tracks){tracks.remove(t.handle);tb.api.disposeHandleAndFree(t.handle);if(t.media!=0){medias.remove(t.media);tb.api.disposeHandleAndFree(t.media);}}
		m.tracks.clear();
	}
	/** ImageDescription for the frames, SoundDescription for sound, a bare SampleDescription for music. */
	private void writeSampleDescription(int desc,Track t) throws M68kCpuException {
		Movie m=t.movie;byte[] d;
		if(t.type==VIDE){d=new byte[86];put4(d,0,86);put4(d,4,0x72617720);put2(d,14,1);put4(d,24,0x200);put4(d,28,0x200); // 'raw ', codecNormalQuality
			put2(d,32,m.asset.frameWidth());put2(d,34,m.asset.frameHeight());put4(d,36,0x00480000);put4(d,40,0x00480000);put2(d,48,Math.min(0x7fff,m.asset.frameCount())); // 72 dpi
			byte[] name="Video".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);d[50]=(byte)name.length;System.arraycopy(name,0,d,51,name.length);put2(d,82,32);put2(d,84,-1);} // depth 32, no clut
		else if(t.type==SOUN){d=new byte[36];put4(d,0,36);put4(d,4,0x74776f73);put2(d,14,1);put2(d,24,1);put2(d,26,16);put4(d,32,44100<<16);} // 'twos', mono 16-bit
		else{d=new byte[16];put4(d,0,16);put4(d,4,MUSI);put2(d,14,1);}
		tb.api.resizeHandle(desc,d.length);int p=memory.readLong(desc);for(int i=0;i<d.length;i++)memory.writeByte(p+i,d[i]);
	}
	private static void put2(byte[] d,int at,int v){d[at]=(byte)(v>>8);d[at+1]=(byte)v;}
	private static void put4(byte[] d,int at,int v){put2(d,at,v>>16);put2(d,at+2,v);}

	/** The movie matrix maps the natural frame size onto the movie box: scale plus translation, like SetMovieBox builds it. */
	private void writeMatrix(int matrix,Movie m) throws M68kCpuException {
		int[] natural=m.naturalBox();int nw=natural[3]-natural[1],nh=natural[2]-natural[0],bw=m.box[3]-m.box[1],bh=m.box[2]-m.box[0];
		int a=nw>0?(int)((long)bw*0x10000/nw):0x10000,d=nh>0?(int)((long)bh*0x10000/nh):0x10000;
		int[] v={a,0,0, 0,d,0, m.box[1]<<16,m.box[0]<<16,0x40000000};
		for(int i=0;i<9;i++)memory.writeLong(matrix+i*4,v[i]);
	}
	private void readMatrix(int matrix,Movie m) throws M68kCpuException {
		int a=memory.readLong(matrix),d=memory.readLong(matrix+16),tx=memory.readLong(matrix+24)>>16,ty=memory.readLong(matrix+28)>>16;
		int[] natural=m.naturalBox();int w=(int)(((long)(natural[3]-natural[1])*a)>>16),h=(int)(((long)(natural[2]-natural[0])*d)>>16);
		m.box=new int[]{ty,tx,ty+h,tx+w};
	}

	/**
	 * GetMovieNextInterestingTime: the frames are the samples (sync samples, steps) of a movie with
	 * pictures, the whole movie is its single edit; a sound-only movie has the one edit only.
	 * Returns {time, duration}, time -1 when nothing interesting lies in that direction.
	 */
	private long[] nextInterestingTime(Movie m,int flags,int time,int rate){
		if(m==null)return new long[]{-1,0};
		long duration=m.duration();boolean forward=rate>=0,edgeOk=(flags&NEXT_TIME_EDGE_OK)!=0;
		if((flags&(NEXT_TIME_MEDIA_SAMPLE|NEXT_TIME_SYNC_SAMPLE|NEXT_TIME_STEP))!=0&&m.hasFrames()&&m.frameTimes!=null&&m.frameTimes.length>0){
			long[] ft=m.frameTimes;int i;
			if(forward){i=0;while(i<ft.length&&(edgeOk?ft[i]<time:ft[i]<=time))i++;if(i>=ft.length)return edgeOk&&time<duration?new long[]{duration,0}:new long[]{-1,0};}
			else{i=ft.length-1;while(i>=0&&(edgeOk?ft[i]>time:ft[i]>=time))i--;if(i<0)return new long[]{-1,0};}
			return new long[]{ft[i],Math.max(0,(i+1<ft.length?ft[i+1]:duration)-ft[i])};
		}
		if((flags&(NEXT_TIME_MEDIA_SAMPLE|NEXT_TIME_SYNC_SAMPLE|NEXT_TIME_STEP))!=0&&m.hasFrames()){
			double frame=m.timeScale/m.fps();if(frame<1)frame=1;
			long index=forward?(long)Math.floor(time/frame)+1:(long)Math.ceil(time/frame)-1;
			if(index<0||(index>=m.asset.frameCount()&&!edgeOk)||index>m.asset.frameCount())return new long[]{-1,0};
			long at=Math.min((long)(index*frame),duration);return new long[]{at,Math.min((long)frame,Math.max(0,duration-at))};
		}
		if(forward)return time<0?new long[]{0,duration}:edgeOk&&time<duration?new long[]{duration,0}:new long[]{-1,0};
		return time>0?new long[]{0,duration}:new long[]{-1,0};
	}

	/** BeginFullScreen: a plain black window covering the screen programs see (the menu bar stays; flags are advisory here). */
	/**
	 * BeginFullScreen: the screen keeps its resolution (the size asked for is answered with the screen's); unless
	 * fullScreenDontChangeMenuBar (4) the menu bar is hidden (MBarHeight 0) and the window covers the whole screen, menu-bar
	 * band included - Airburst draws its 800x600 picture from the top of the screen. fullScreenPreflightSize (8) only answers
	 * the size. Flags as Movies.h: fullScreenHideCursor 1, fullScreenAllowEvents 2.
	 */
	private int beginFullScreen(int statePtr,int widthPtr,int heightPtr,int windowPtr,int eraseColor,int flags) throws M68kCpuException {
		boolean hideBar=(flags&4)==0;
		int[] screen=tb.api.quickDraw().screenBounds();int top=screen[0]+(hideBar?0:MacAppHost.MENU_BAR_HEIGHT);
		if(widthPtr!=0)memory.writeWord(widthPtr,screen[3]-screen[1]);if(heightPtr!=0)memory.writeWord(heightPtr,screen[2]-top);
		if((flags&8)!=0){if(statePtr!=0)memory.writeLong(statePtr,0);if(windowPtr!=0)memory.writeLong(windowPtr,0);return 0;} // fullScreenPreflightSize
		if(fullScreenState!=0)endFullScreen(fullScreenState);
		int[] bounds={top,screen[1],screen[2],screen[3]};
		int w=0;
		if(windowPtr!=0){
			w=tb.windows.create(0,WindowManager68k.WINDOW_SIZE,bounds,"",false,2,-1,false,0,true); // plainDBox, in front
			if(w==0)return -108;
			int rgb=0;if(eraseColor!=0)rgb=(memory.readWord(eraseColor)>>8)<<16|(memory.readWord(eraseColor+2)>>8)<<8|memory.readWord(eraseColor+4)>>8;
			tb.windows.setContentColor(w,rgb);tb.windows.show(w,true);
		}
		fullScreenWindow=w;fullScreenState=memory.allocate(12,2);memory.writeLong(fullScreenState,0x46756c6c);memory.writeLong(fullScreenState+4,w); // 'Full', window, MBarHeight to restore
		memory.writeWord(fullScreenState+8,hideBar?memory.readWord(0xbaa):-1);if(hideBar)memory.writeWord(0xbaa,0);
		if(statePtr!=0)memory.writeLong(statePtr,fullScreenState);if(windowPtr!=0)memory.writeLong(windowPtr,w);
		return 0;
	}
	private void endFullScreen(int state) throws M68kCpuException {
		if(state==0||state!=fullScreenState)return;
		int w=memory.readLong(state+4);if(w!=0)tb.windows.disposeWindow(w);
		int bar=(short)memory.readWord(state+8);if(bar>=0)memory.writeWord(0xbaa,bar);
		memory.free(state);fullScreenWindow=0;fullScreenState=0;
	}

	// ------------------------------------------------------------------ playback

	void setRate(Movie m,int rate){
		if(m==null||m.rate==rate)return;
		m.endUnreported=false;m.endRate=0;m.pausedUnits=-1; // the program set the rate itself
		if(rate==0){m.pausedAt=m.currentMillis();m.rate=0;stopAudio(m);return;}
		if(m.rate==0&&m.currentMillis()>=m.durationMillis()&&rate>0)m.pausedAt=0; // playing again from the end restarts
		m.pausedAt=m.currentMillis();m.startMillis=System.currentTimeMillis();m.rate=rate;
		if(rate>0)startAudio(m);else stopAudio(m); // reverse play is silent
	}
	void seekMillis(Movie m,int millis){
		millis=(int)Math.max(0,Math.min(m.durationMillis(),millis));
		boolean playing=m.playing();
		m.pausedAt=millis;m.startMillis=System.currentTimeMillis();
		if(playing){stopAudio(m);if(m.rate>0)startAudio(m);}
		else positionAudio(m);
	}
	/** A seek in the time scale: the frame shown is the one at that time (milliseconds rounded up, so a frame's own start time
	 *  never shows the frame before it), and a paused movie reports exactly that time. */
	void seekUnits(Movie m,long units){
		units=Math.max(0,Math.min(m.duration(),units));
		seekMillis(m,(int)Math.min(m.durationMillis(),(units*1000+m.timeScale-1)/m.timeScale));
		if(!m.playing()){m.pausedUnits=units;m.pausedUnitsAt=m.pausedAt;}
	}
	/** mcActionStep: the movie stops and moves {@code count} frames (samples) on, or back for a negative count, in its time scale. */
	private void step(Movie m,int count){
		setRate(m,0);
		if(!m.hasFrames()){seekMillis(m,m.currentMillis()+(int)(count*1000/m.fps()));return;}
		long time=m.time();
		for(int i=0;i<Math.abs(count);i++){long[] next=nextInterestingTime(m,NEXT_TIME_STEP,(int)time,count>0?0x10000:-0x10000);if(next[0]<0)break;time=next[0];}
		seekUnits(m,time);
	}

	void task(int movie) throws M68kException {
		Movie m=movies.get(movie);if(m==null){for(Movie each:new ArrayList<Movie>(movies.values()))task(each);return;}task(m);
	}
	/** Stops a movie that has run to its end (or back to its start), so rate and time queries never see a run past it. */
	private void settle(Movie m){
		if(!m.playing())return;
		int now=m.currentMillis();
		if(m.timeBaseFlags!=0&&m.durationMillis()>0&&(m.rate>0?now>=m.durationMillis():now<=0)){ // a looping time base goes on from the other end
			if((m.timeBaseFlags&2)!=0){m.pausedAt=m.rate>0?(int)m.durationMillis():0;m.startMillis=System.currentTimeMillis();m.rate=-m.rate;stopAudio(m);if(m.rate>0)startAudio(m);}
			else seekMillis(m,m.rate>0?0:(int)m.durationMillis());
			return;
		}
		if(m.rate>0&&now>=m.durationMillis()){m.endRate=m.rate;m.rate=0;m.pausedAt=(int)m.durationMillis();stopAudio(m);m.endUnreported=true;}
		else if(m.rate<0&&now<=0){m.rate=0;m.pausedAt=0;stopAudio(m);m.endUnreported=true;}
	}
	private void task(Movie m) throws M68kException {
		if(!m.active)return;
		boolean wasPlaying=m.playing();
		if(wasPlaying){settle(m);draw(m);}
		int time=m.time();
		if(time!=m.lastTime){int previous=m.lastTime;m.lastTime=time;if(previous>=0)fireCallBacks(m,previous,time);}
		if(wasPlaying&&!m.playing()){Controller c=controller(m.controller);if(c!=null)drawBar(c,m,false);}
	}

	// ------------------------------------------------------------------ drawing

	void draw(Movie m) throws M68kCpuException {if(m.hasFrames())drawFrame(m,frameAt(m,m.currentMillis()));}
	private BufferedImage frameAt(Movie m,int millis){
		if(!m.hasFrames())return null;
		return m.asset.frameAtMillis(Math.max(0,millis));
	}
	private void drawFrame(Movie m,BufferedImage frame) throws M68kCpuException {
		if(frame==null||m.port==0)return;
		Graphics2D g=tb.quickDraw.surface(m.port,m.box);
		if(g==null)return;
		try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(frame,m.box[1],m.box[0],Math.max(1,m.box[3]-m.box[1]),Math.max(1,m.box[2]-m.box[0]),null);}
		finally{g.dispose();}
	}
	/** GetMoviePict: the frame at the given movie time as a PICT Handle (0 for a movie without video). */
	private int pictAt(Movie m,int time) throws M68kCpuException {
		BufferedImage frame=frameAt(m,m.millis(time));if(frame==null)return 0;
		try{return tb.api.createHandle(HCResourceExport.pict(frame));}catch(IOException e){return 0;}
	}

	// ------------------------------------------------------------------ audio

	private void openAudio(Movie m){
		if(m.asset==null)return;
		if(m.asset.mp3!=null)m.stream=StreamingSound.of(m.asset.mp3);
		else if(m.asset.hasAudio()){
			try{AudioInputStream in=HostSampleRate.forHost(m.asset.audioStream());m.clip=AudioSystem.getClip();m.clip.open(in);} // (an 11127 Hz track broke the host's audio after a minute)
			catch(Exception e){m.clip=null;}
		}
		if(m.clip==null&&m.stream==null&&m.asset.musicSequence!=null){
			Synthesizer synth=null;Sequencer sequencer=null;
			try{
				try{synth=MidiSystem.getSynthesizer();synth.open();sequencer=MidiSystem.getSequencer(false);sequencer.open();sequencer.getTransmitter().setReceiver(synth.getReceiver());}
				catch(Exception direct){if(sequencer!=null)sequencer.close();if(synth!=null)synth.close();synth=null;sequencer=MidiSystem.getSequencer();sequencer.open();} // only a pre-wired default sequencer
				sequencer.setSequence(m.asset.musicSequence);m.sequencer=sequencer;m.synthesizer=synth;
			}catch(Exception e){System.err.println("[qt] MIDI playback unavailable: "+e);m.sequencer=null;if(synth!=null)synth.close();}
		}
		applyVolume(m);
	}
	private void startAudio(Movie m){
		positionAudio(m);
		try{if(m.clip!=null)m.clip.start();if(m.stream!=null)m.stream.start();if(m.sequencer!=null)m.sequencer.start();}catch(Exception e){}
	}
	private void positionAudio(Movie m){
		long micros=m.pausedAt*1000L;
		try{if(m.clip!=null)m.clip.setMicrosecondPosition(Math.min(micros,m.clip.getMicrosecondLength()));
			if(m.stream!=null)m.stream.setMicrosecondPosition(micros);
			if(m.sequencer!=null)m.sequencer.setMicrosecondPosition(Math.min(micros,m.sequencer.getMicrosecondLength()));}catch(Exception e){}
	}
	private void stopAudio(Movie m){try{if(m.clip!=null)m.clip.stop();if(m.stream!=null)m.stream.stop();if(m.sequencer!=null&&m.sequencer.isRunning())m.sequencer.stop();}catch(Exception e){}}
	private void closeAudio(Movie m){
		stopAudio(m);
		try{if(m.clip!=null)m.clip.close();if(m.stream!=null)m.stream.close();if(m.sequencer!=null)m.sequencer.close();if(m.synthesizer!=null)m.synthesizer.close();}catch(Exception e){}
		m.clip=null;m.stream=null;m.sequencer=null;m.synthesizer=null;
	}
	void setVolume(Movie m,int volume){m.volume=volume;applyVolume(m);}
	/** Movie volumes are 8.8 fixed (0x0100 = full); negative values are muted. */
	private void applyVolume(Movie m){
		//scaled by the Mac's sound volume (SetSoundVol / SetDefaultOutputVolume), which every sound of the Mac follows
		int level=Math.round(Math.max(0,Math.min(256,m.volume))*(SoundMixer68k.gainLeft()+SoundMixer68k.gainRight())/2);
		try{
			if(m.stream!=null)m.stream.setLevel(level);
			if(m.clip!=null&&m.clip.isControlSupported(FloatControl.Type.MASTER_GAIN)){FloatControl gain=(FloatControl)m.clip.getControl(FloatControl.Type.MASTER_GAIN);
				float dB=level==0?gain.getMinimum():(float)(20*Math.log10(level/256.0));gain.setValue(Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),dB)));}
			if(m.synthesizer!=null){int value=TQTMovie.midiVolume(level);for(MidiChannel channel:m.synthesizer.getChannels())if(channel!=null)channel.controlChange(7,value);}
		}catch(Exception e){}
	}

	// ------------------------------------------------------------------ time records and callbacks

	/** TimeRecord: value (wide, 8 bytes), scale (long), base (TimeBase). */
	private void convertTimeScale(int record,int newScale) throws M68kCpuException {
		if(record==0||newScale<=0)return;
		long value=((long)memory.readLong(record)<<32)|(memory.readLong(record+4)&0xffffffffL);int scale=memory.readLong(record+8);
		if(scale>0&&scale!=newScale)value=value*newScale/scale;
		memory.writeLong(record,(int)(value>>32));memory.writeLong(record+4,(int)value);memory.writeLong(record+8,newScale);
	}
	/** A TimeRecord's value in the movie's time scale. */
	private long recordTime(Movie m,int record) throws M68kCpuException {
		long value=((long)memory.readLong(record)<<32)|(memory.readLong(record+4)&0xffffffffL);int scale=memory.readLong(record+8);
		return scale>0&&scale!=m.timeScale?value*m.timeScale/scale:value;
	}
	private int newCallBack(int timeBase,int type) throws M68kCpuException {
		if(movieForTimeBase(timeBase)==null)return 0;
		int handle=tb.api.createHandle(new byte[4]);
		callBacks.put(handle,new CallBack(handle,timeBase,type));return handle;
	}
	/** CallMeWhen for callBackAtTime: param1 = trigger flags, param2 = time, param3 = its time scale. */
	private int callMeWhen(int cb,int proc,int refCon,int param1,int param2,int param3){
		CallBack c=callBacks.get(cb);if(c==null)return -2010;
		Movie m=movieForTimeBase(c.timeBase);if(m==null)return -2010;
		c.proc=proc;c.refCon=refCon;c.flags=param1;
		long time=param2&0xffffffffL;if(param3>0&&param3!=m.timeScale)time=time*m.timeScale/param3;
		c.trigger=time;c.armed=proc!=0;
		if(Mac68kApi.TRACE)System.err.println("[qt] CallMeWhen cb $"+Integer.toHexString(cb)+" flags "+param1+" at "+time+" (now "+m.time()+"/"+m.duration()+")");
		return 0;
	}
	/** The time base goes with its movie: its callbacks never fire again. Its Handle is freed and the next movie's time base may
	 *  get the same address - Myst Preview's Movie XCMD arms closeOnFinish callbacks at each movie's end; a dead one, still armed
	 *  under the reused address, closed the red book's Sirrus movie a few seconds in, at the time an earlier movie ended. */
	private void forgetCallBacks(int timeBase){for(CallBack c:callBacks.values())if(c.timeBase==timeBase){c.armed=false;c.timeBase=0;}}
	private void fireCallBacks(Movie m,int previous,int now) throws M68kException {
		if(m.timeBase==0||callBacks.isEmpty())return;
		for(CallBack c:new ArrayList<CallBack>(callBacks.values())){
			if(!c.armed||c.timeBase!=m.timeBase||c.type!=1)continue;
			boolean forward=now>previous&&c.trigger>previous&&c.trigger<=now,backward=now<previous&&c.trigger<previous&&c.trigger>=now;
			int flags=(c.flags&TRIGGER_TIME_EITHER)==0?TRIGGER_TIME_FWD:c.flags;
			if(!((forward&&(flags&TRIGGER_TIME_FWD)!=0)||(backward&&(flags&TRIGGER_TIME_BWD)!=0)))continue;
			c.armed=false; // one shot: the procedure re-arms with CallMeWhen
			if(Mac68kApi.TRACE)System.err.println("[qt] callback $"+Integer.toHexString(c.handle)+" fires at "+now+" (from "+previous+", trigger "+c.trigger+")");
			callProcedure(c.proc,c.handle,c.refCon); // pascal void proc(QTCallBack cb, long refCon)
		}
	}
	/** pascal void proc(long, long) in the program, from inside a trap. */
	private void callProcedure(int proc,int arg1,int arg2) throws M68kException {
		if(proc==0)return;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		cpu.pushLong(arg1);cpu.pushLong(arg2);
		try{cpu.callPascal(proc,8);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	// ------------------------------------------------------------------ movie controller component

	Controller controller(int instance){return controllers.get(instance);}
	void openController(int instance){controllers.put(instance,new Controller(instance));}
	void closeController(int instance){Controller c=controllers.remove(instance);if(c!=null){Movie m=movies.get(c.movie);if(m!=null&&m.controller==instance)m.controller=0;}}

	private void attach(Controller c,Movie m,int port){c.movie=m.handle;m.controller=c.instance;if(port!=0){c.port=port;m.port=port;}}
	/**
	 * MCPositionController / NewMovieController: places the movie at the rectangle's corner (mcTopLeftMovie) or scaled
	 * into it; a detached controller goes where controllerRect says (the Movie XCMD lays its window out this way).
	 */
	private void position(Controller c,Movie m,int[] movieRect,int[] controllerRect,int flags){
		if(movieRect!=null){
			int w=m.box[3]-m.box[1],h=m.box[2]-m.box[0];
			if((flags&2)!=0&&m.hasFrames()){w=Math.max(1,movieRect[3]-movieRect[1]);h=Math.max(1,movieRect[2]-movieRect[0]);} // mcScaleMovieToFit
			m.box=new int[]{movieRect[0],movieRect[1],movieRect[0]+h,movieRect[1]+w};
		}
		if(!c.attached&&controllerRect!=null)c.detachedBar=new int[]{controllerRect[0],controllerRect[1],controllerRect[0]+CONTROLLER_HEIGHT,Math.max(controllerRect[3],controllerRect[1]+CONTROLLER_MIN_WIDTH)};
		if((flags&8)!=0)c.visible=false; // mcNotVisible
	}
	/** The controller bar: under the movie box (or where the box is for a sound-only movie) unless the program placed it. */
	private int[] barRect(Controller c,Movie m){
		if(!c.attached&&c.detachedBar!=null)return c.detachedBar.clone();
		int left=m.box[1],top=m.hasFrames()?m.box[2]:m.box[0],width=Math.max(m.box[3]-m.box[1],CONTROLLER_MIN_WIDTH);
		return new int[]{top,left,top+CONTROLLER_HEIGHT,left+width};
	}
	private boolean barShown(Controller c){return c.visible;}
	/** MCGetControllerBoundsRect: movie and bar together when attached, the bar alone when detached. */
	private int[] bounds(Controller c,Movie m){
		if(!c.attached)return c.visible?barRect(c,m):new int[4];
		int[] b=m.box.clone();
		if(c.visible){int[] bar=barRect(c,m);if(m.hasFrames()){b[2]=bar[2];b[3]=Math.max(b[3],bar[3]);}else b=bar;}
		return b;
	}

	/** Movie controller component calls forwarded by the Component Manager (params points at the last argument). */
	int controllerCall(int instance,int selector,int params) throws M68kException {
		Controller c=controllers.get(instance);if(c==null)return Components68k.BAD_COMPONENT_INSTANCE;
		Movie m=movies.get(c.movie);
		switch(selector){
			case 0x02:case 0x17:{ // MCSetMovie / MCNewAttachedController(mc, movie, window, where)
				int where=memory.readLong(params),window=memory.readLong(params+4),movie=memory.readLong(params+8);
				Movie target=movies.get(movie);if(target==null)return -2010;
				attach(c,target,window);
				int v=(short)(where>>16),h=(short)where,w=target.box[3]-target.box[1],hh=target.box[2]-target.box[0];
				target.box=new int[]{v,h,v+hh,h+w};
				c.lastBar=null;return 0;}
			case 0x05:{int index=(short)memory.readWord(params);return index<=0&&m!=null?m.handle:0;} // MCGetIndMovie
			case 0x07:return m==null?0:isPlayerEvent(c,m,memory.readLong(params))?1:0; // MCIsPlayerEvent(mc, EventRecord*)
			case 0x08:c.filter=memory.readLong(params);return 0; // MCSetActionFilter
			case 0x09:{int action=(short)memory.readWord(params+4),param=memory.readLong(params);return m==null?-2010:doAction(c,m,action,param);} // MCDoAction(mc, action, params)
			case 0x0a:c.attached=memory.readByte(params)!=0;if(m!=null)drawBar(c,m,true);return 0; // MCSetControllerAttached
			case 0x0b:return c.attached?1:0; // MCIsControllerAttached
			case 0x0c:c.port=memory.readLong(params);if(m!=null)m.port=c.port;return 0; // MCSetControllerPort
			case 0x0d:return c.port; // MCGetControllerPort
			case 0x0e:{ // MCSetVisible: hiding/showing the bar changes the controller's size, which QuickTime reports to the action filter
				// (Movie XCMD 1.2 sizes its window there: `set the controllerVisible of window ... to false` left a white 16-pixel strip under Myst's movies)
				boolean visible=memory.readByte(params)!=0,changed=visible!=c.visible;c.visible=visible;
				if(m!=null)drawBar(c,m,true);
				if(changed&&m!=null)filter(c,MC_SIZE_CHANGED,0);
				return 0;}
			case 0x0f:return c.visible?1:0; // MCGetVisible
			case 0x10:{int rect=memory.readLong(params);if(rect!=0)writeRect(rect,m==null?new int[4]:bounds(c,m));return 0;} // MCGetControllerBoundsRect
			case 0x11:{int rect=memory.readLong(params);if(rect!=0&&m!=null){if(c.attached)position(c,m,readRect(rect),null,1);else position(c,m,null,readRect(rect),0);c.lastBar=null;}return 0;} // MCSetControllerBoundsRect
			case 0x12:case 0x13:return 0; // MCGetControllerBoundsRgn / MCGetWindowRgn: no region
			case 0x14:{int movie=memory.readLong(params);Movie target=movies.get(movie);if(target!=null){attach(c,target,0);c.lastBar=null;drawBar(c,target,true);}return 0;} // MCMovieChanged
			case 0x15:return 0; // MCSetDuration
			case 0x16:{int scalePtr=memory.readLong(params);if(scalePtr!=0&&m!=null)memory.writeLong(scalePtr,m.timeScale);return m==null?0:m.time();} // MCGetCurrentTime
			case 0x18:{if(m!=null){filter(c,MC_DRAW,memory.readLong(params));draw(m);drawBar(c,m,true);}return 0;} // MCDraw(mc, window)
			case 0x19:{boolean activate=memory.readByte(params)!=0;filter(c,activate?MC_ACTIVATE:MC_DEACTIVATE,memory.readLong(params+2));return 0;} // MCActivate(mc, window, activate)
			case 0x1a:if(m!=null)idle(c,m);return 0; // MCIdle
			case 0x1b:return 0; // MCKey
			case 0x1c:{if(m==null)return 0;int where=memory.readLong(params+8);click(c,m,(short)where,(short)(where>>16));return 0;} // MCClick(mc, window, where, when, modifiers)
			case 0x1d:case 0x1e:case 0x1f:case 0x20:case 0x21:case 0x22:case 0x23:return 0; // editing
			case 0x24:{int flags=memory.readLong(params),controllerRect=memory.readLong(params+4),movieRect=memory.readLong(params+8); // MCPositionController
				if(m!=null){position(c,m,movieRect==0?null:readRect(movieRect),controllerRect==0?null:readRect(controllerRect),flags);c.lastBar=null;}return 0;}
			// MCGetControllerInfo: mcInfoHasSound $20, mcInfoIsPlaying $40 (Movies.h); $400 is mcInfoMovieIsInteractive (UxGetMovieInfo
			// read every video movie as interactive and none as having sound)
			case 0x25:{int flagsPtr=memory.readLong(params);if(flagsPtr!=0)memory.writeLong(flagsPtr,(m!=null&&m.playing()?0x40:0)|(m!=null&&m.asset!=null&&(m.asset.hasAudio()||m.asset.musicSequence!=null)?0x20:0));return 0;}
			case 0x28:case 0x29:return 0; // MCSetClip / MCGetClip
			case 0x2a:return 0; // MCDrawBadge
			case 0x2e:{int where=memory.readLong(params+4),partPtr=memory.readLong(params);if(partPtr!=0)memory.writeWord(partPtr,m!=null&&barShown(c)&&inside(barRect(c,m),(short)where,(short)(where>>16))?1:0);return 0;} // MCPtInController
			case 0x2f:return 0; // MCInvalidate
			default:return 0;
		}
	}

	private int doAction(Controller c,Movie m,int action,int param) throws M68kException {
		if(filter(c,action,param))return 0;
		switch(action){
			case MC_IDLE:idle(c,m);break;
			case MC_DRAW:draw(m);drawBar(c,m,true);break;
			case MC_PLAY: // params = Fixed rate. Airburst passes 1 (a Boolean "play", 1/65536 as a rate) and sequences its songs with
				// time-base callbacks, which only work when its movies run at normal speed: a rate below 1/256 plays at the preferred rate
				if(param!=0&&Math.abs(param)<0x100)param=param>0?m.preferredRate:-m.preferredRate;
				setRate(m,param);if(param!=0)draw(m);drawBar(c,m,false);break;
			case MC_GO_TO_TIME:if(param!=0){seekUnits(m,recordTime(m,param));draw(m);drawBar(c,m,false);}break; // params = TimeRecord*
			case MC_SET_VOLUME:setVolume(m,(short)param);break;
			case MC_GET_VOLUME:if(param!=0)memory.writeWord(param,m.volume);break;
			case MC_STEP:{step(m,(short)param);draw(m);drawBar(c,m,false);break;}
			case MC_SET_LOOPING:c.looping=param!=0;break;
			case MC_GET_LOOPING:if(param!=0)memory.writeByte(param,c.looping?1:0);break;
			case MC_SET_PALINDROME:c.palindrome=param!=0;break;
			case MC_GET_PALINDROME:if(param!=0)memory.writeByte(param,c.palindrome?1:0);break;
			case MC_SET_KEYS_ENABLED:c.keysEnabled=param!=0;break;
			case MC_GET_KEYS_ENABLED:if(param!=0)memory.writeByte(param,c.keysEnabled?1:0);break;
			case MC_SET_PLAY_SELECTION:c.playSelection=param!=0;break;
			case MC_GET_PLAY_SELECTION:if(param!=0)memory.writeByte(param,c.playSelection?1:0);break;
			case MC_SET_USE_BADGE:c.useBadge=param!=0;break;
			case MC_GET_USE_BADGE:if(param!=0)memory.writeByte(param,c.useBadge?1:0);break;
			case MC_SET_FLAGS:c.flags=param;break;
			case MC_GET_FLAGS:if(param!=0)memory.writeLong(param,c.flags);break;
			case MC_SET_PLAY_EVERY_FRAME:c.playEveryFrame=param!=0;break;
			case MC_GET_PLAY_EVERY_FRAME:if(param!=0)memory.writeByte(param,c.playEveryFrame?1:0);break;
			case MC_GET_PLAY_RATE:settle(m);if(param!=0)memory.writeLong(param,m.rate);break;
			default:break; // selection, grow box, activation: nothing to keep
		}
		return 0;
	}

	private void idle(Controller c,Movie m) throws M68kException {
		if(filter(c,MC_IDLE,0))return;
		boolean wasPlaying=m.playing();
		task(m);
		if(wasPlaying&&!m.playing()||m.endUnreported&&!m.playing()){
			m.endUnreported=false;
			if(c.looping){seekMillis(m,0);setRate(m,0x00010000);}
			else filter(c,MC_PLAY,0); // the controller stopped the movie at its end: the program hears of it as QuickTime tells it
		}
		drawBar(c,m,false);
	}

	/**
	 * The action filter set by MCSetActionFilter: pascal Boolean filter(MovieController mc, short *action, void *params).
	 * It sees every action before the controller performs it and returns true to swallow it.
	 */
	private boolean filter(Controller c,int action,int params) throws M68kException {
		if(c.filter==0||c.filterDepth>0)return false;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		c.filterDepth++;
		try{
			cpu.a[7]-=2;int actionPtr=cpu.a[7];memory.writeWord(actionPtr,action);
			cpu.a[7]-=2; // Boolean result
			cpu.pushLong(c.instance);cpu.pushLong(actionPtr);cpu.pushLong(params);
			cpu.callPascal(c.filter,12);
			return memory.readByte(cpu.a[7])!=0;
		}finally{c.filterDepth--;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	/** EventRecord: what(2) message(4) when(4) where(Point v,h) modifiers(2). Mouse downs in the controller are handled here. */
	private boolean isPlayerEvent(Controller c,Movie m,int event) throws M68kException {
		if(event==0)return false;
		int what=memory.readWord(event);
		if(what==0){idle(c,m);return false;}
		// an update of the controller's window: the movie's frame and the controller are drawn (UxMovie erases the window and
		// leaves the rest to MCIsPlayerEvent: its windows stayed white until the movie played); false, so the program ends the update
		if(what==6){int window=memory.readLong(event+2);if(window!=0&&(window==c.port||window==m.port)){draw(m);drawBar(c,m,true);}return false;}
		if(what==3||what==5)return key(c,m,event);
		if(what!=1)return false;
		int v=(short)memory.readWord(event+10),h=(short)memory.readWord(event+12);
		int[] local=c.port==0?new int[]{h,v}:tb.windows.globalToLocal(c.port,h,v);
		if(!inside(m.box,local[0],local[1])&&!(barShown(c)&&inside(barRect(c,m),local[0],local[1])))return false;
		if(filter(c,MC_MOUSE_DOWN,event))return true;
		return click(c,m,local[0],local[1]);
	}
	/** A keyDown/autoKey with keys enabled (mcActionSetKeysEnabled: UxMovie's passMovieClick): space and Return play/pause, the
	 *  left/right arrows step a frame, up/down change the volume; the action filter sees mcActionKey first. */
	private boolean key(Controller c,Movie m,int event) throws M68kException {
		if(!c.keysEnabled)return false;
		int ch=memory.readLong(event+2)&0xff;
		if(ch!=' '&&ch!=0x0d&&ch!=0x03&&(ch<0x1c||ch>0x1f))return false;
		if(filter(c,MC_KEY,event))return true;
		switch(ch){
			case ' ':case 0x0d:case 0x03:doAction(c,m,MC_PLAY,m.playing()?0:m.preferredRate==0?0x10000:m.preferredRate);break;
			case 0x1c:doAction(c,m,MC_STEP,-1);break;
			case 0x1d:doAction(c,m,MC_STEP,1);break;
			default:doAction(c,m,MC_SET_VOLUME,Math.max(0,Math.min(0x100,m.volume+(ch==0x1e?0x20:-0x20))));break;
		}
		return true;
	}
	/** A click at local (h,v): the play button toggles playback, the slider scrubs; clicks in the movie itself are not consumed. */
	private boolean click(Controller c,Movie m,int h,int v) throws M68kException {
		if(!barShown(c))return false;
		int[] bar=barRect(c,m);
		if(!inside(bar,h,v))return false;
		if(h<bar[1]+CONTROLLER_HEIGHT){doAction(c,m,MC_PLAY,m.playing()?0:0x00010000);return true;}
		int trackLeft=bar[1]+CONTROLLER_HEIGHT+4,trackRight=bar[3]-4;
		if(trackRight>trackLeft&&m.durationMillis()>0){
			int millis=(int)((long)(Math.max(trackLeft,Math.min(trackRight,h))-trackLeft)*m.durationMillis()/(trackRight-trackLeft));
			int record=memory.allocate(16,4);
			try{memory.writeLong(record,0);memory.writeLong(record+4,(int)m.units(millis));memory.writeLong(record+8,m.timeScale);memory.writeLong(record+12,timeBase(m));doAction(c,m,MC_GO_TO_TIME,record);}
			finally{memory.free(record);}
		}
		return true;
	}

	/** Draws the controller bar (play/pause button and a position slider) when it changed, or always when forced. */
	private void drawBar(Controller c,Movie m,boolean force) throws M68kCpuException {
		if(!barShown(c)||c.port==0&&m.port==0)return;
		int[] bar=barRect(c,m);int trackLeft=bar[1]+CONTROLLER_HEIGHT+4,trackRight=bar[3]-4;
		long duration=m.durationMillis();int thumb=duration<=0?trackLeft:trackLeft+(int)((trackRight-trackLeft)*Math.min(1.0,m.currentMillis()/(double)duration));
		boolean playing=m.playing();
		if(!force&&c.lastBar!=null&&java.util.Arrays.equals(bar,c.lastBar)&&playing==c.lastPlaying&&thumb==c.lastThumb)return;
		Graphics2D g=tb.quickDraw.surface(m.port!=0?m.port:c.port,bar);
		if(g==null)return;
		try{
			int x=bar[1],y=bar[0],w=bar[3]-bar[1],hgt=bar[2]-bar[0];
			g.setColor(new Color(0xDDDDDD));g.fillRect(x,y,w,hgt);
			g.setColor(Color.BLACK);g.drawRect(x,y,w-1,hgt-1);g.drawLine(x+CONTROLLER_HEIGHT-1,y,x+CONTROLLER_HEIGHT-1,y+hgt-1);
			if(playing){g.fillRect(x+4,y+4,3,hgt-8);g.fillRect(x+9,y+4,3,hgt-8);}
			else g.fillPolygon(new int[]{x+5,x+5,x+12},new int[]{y+3,y+hgt-3,y+hgt/2},3);
			g.setColor(Color.WHITE);g.fillRect(trackLeft,y+hgt/2-2,Math.max(1,trackRight-trackLeft),4);
			g.setColor(Color.BLACK);g.drawRect(trackLeft,y+hgt/2-2,Math.max(1,trackRight-trackLeft),4);
			g.fillRect(Math.max(trackLeft,Math.min(trackRight-3,thumb-1)),y+3,4,hgt-6);
		}finally{g.dispose();}
		c.lastBar=bar;c.lastPlaying=playing;c.lastThumb=thumb;
	}

	private static boolean inside(int[] r,int h,int v){return h>=r[1]&&h<r[3]&&v>=r[0]&&v<r[2];}
	private int[] readRect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	private void writeRect(int p,int[] r) throws M68kCpuException {memory.writeWord(p,r[0]);memory.writeWord(p+2,r[1]);memory.writeWord(p+4,r[2]);memory.writeWord(p+6,r[3]);}
}
