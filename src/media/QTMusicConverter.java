import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

// QuickTime Music Architecture ('musi') tracks are event streams rather than
// compressed audio. Modern ffmpeg/AVFoundation cannot decode them, so translate
// their note, rest, controller, and instrument events to a Java MIDI Sequence.
public class QTMusicConverter {
	private static class Part {
		int gmNumber;
		int channel;

		Part(int gmNumber, int channel) {
			this.gmNumber = gmNumber;
			this.channel = channel;
		}
	}

	public static Sequence convert(File movieFile) throws IOException, InvalidMidiDataException {
		if(movieFile == null || !movieFile.isFile()) {System.err.println("QTMusicConverter: input file is missing");return null;}
		System.out.println("QTMusicConverter: scanning "+movieFile.getAbsolutePath());
		QuickTimeFile movie;
		try { movie = QuickTimeFile.open(movieFile); }
		catch(IOException e) { System.err.println("QTMusicConverter: "+e.getMessage()); return null; }
		try {
			System.out.println("QTMusicConverter: moov found in "+movie.source);
			for(QuickTimeFile.Track track : movie.tracks) {
				if(!hasMusicDescription(track.media)) continue;
				Sequence sequence=convertMusicTrack(track.media);
				System.out.println("QTMusicConverter: musi track conversion "+(sequence==null?"failed":"succeeded"));
				return sequence;
			}
			System.err.println("QTMusicConverter: no musi sample description found");
			return null;
		} finally { movie.close(); }
	}

	/** The movie header's {timeScale, duration} (from 'mvhd'), or null when the file has no readable movie. */
	static int[] movieHeader(File movieFile) {
		try {
			QuickTimeFile movie = QuickTimeFile.open(movieFile);
			movie.close();
			return new int[] {movie.timeScale, (int)Math.min(Integer.MAX_VALUE, movie.duration)};
		} catch(IOException | RuntimeException e) {
			return null;
		}
	}

	private static Sequence convertMusicTrack(QuickTimeFile.Media media) throws IOException, InvalidMidiDataException {
		int timeScale = media.timeScale;
		if(timeScale <= 0) return null;

		Sequence sequence = new Sequence(Sequence.PPQ, timeScale);
		Track midiTrack = sequence.createTrack();
		// At 60 BPM, one MIDI quarter note lasts one second, so one movie time-scale
		// unit maps exactly to one sequence tick.
		MetaMessage tempo = new MetaMessage();
		tempo.setMessage(0x51, new byte[] {0x0F, 0x42, 0x40}, 3);
		midiTrack.add(new MidiEvent(tempo, 0));

		Map<Integer,Part> parts = readParts(media);
		assignMissingChannels(parts);
		for(Part part : parts.values()) {
			if(part.channel == 9) continue;
			int program = part.gmNumber & 0x3FFF;
			if(program > 0) program--;
			program = Math.max(0, Math.min(127, program));
			addShort(midiTrack, ShortMessage.PROGRAM_CHANGE, part.channel, program, 0, 0);
		}

		long sampleTime = 0;
		int samples = media.samples();
		for(int sample=0; sample<samples; sample++) {
			byte[] data = media.readSample(sample);
			if((data.length & 3) != 0) return null;
			for(MusicEvent e : decodeEvents(data, 0, data.length)) {
				long tick = sampleTime + e.time;
				if(e.kind == MusicEvent.NOTE) {
					Part part = part(parts, e.part);
					if(e.velocity == 0) {
						addShort(midiTrack, ShortMessage.NOTE_OFF, part.channel, e.pitch, 0, tick);
					} else {
						addShort(midiTrack, ShortMessage.NOTE_ON, part.channel, e.pitch, e.velocity, tick);
						addShort(midiTrack, ShortMessage.NOTE_OFF, part.channel, e.pitch, 0, tick + e.duration);
					}
				} else if(e.kind == MusicEvent.CONTROL) {
					int channel = part(parts, e.part).channel;
					for(int[] m : controlMessages(e.controller, e.value))
						addShort(midiTrack, m[0], channel, m[1], m[2], tick);
				}
			}
			sampleTime += media.sampleDuration(sample);
		}
		MetaMessage end = new MetaMessage();
		end.setMessage(0x2F, new byte[0], 0);
		midiTrack.add(new MidiEvent(end, sampleTime));
		return sequence;
	}

	private static Map<Integer,Part> readParts(QuickTimeFile.Media media) {
		Map<Integer,Part> parts = new HashMap<Integer,Part>();
		for(QuickTimeFile.SampleDescription description : media.descriptions) {
			if(!"musi".equals(QuickTimeFile.typeName(description.format))) continue;
			// the tune header's events follow the description header and its flags long
			for(NoteRequest r : noteRequests(description.bytes, 20, description.bytes.length)) parts.put(r.part, new Part(r.gmNumber, -1));
		}
		return parts;
	}

	// ------------------------------------------------------------------ QuickTime music events (QuickTimeMusic.h formats)
	// shared with the native Tune Player of the 68K VM (QuickTimeMusic68k), which plays the same event streams live

	/** One event of a QuickTime music sequence: a note or a controller change at a time (in the sequence's time units). */
	static final class MusicEvent {
		static final int NOTE = 1, CONTROL = 2;
		final int kind, part, offset; // offset: of the event's first long in the decoded bytes
		final long time;
		int pitch, velocity; long duration; // NOTE
		int controller, value;              // CONTROL: QuickTime controller number, signed 8.8 value
		MusicEvent(int kind, int part, long time, int offset) { this.kind = kind; this.part = part; this.time = time; this.offset = offset; }
	}

	/**
	 * The notes and controller changes of a sequence (rest, note, control, marker, extended note/control, knob and general
	 * events), up to its end marker or {@code end}.
	 */
	static java.util.List<MusicEvent> decodeEvents(byte[] data, int offset, int end) { return decodeEvents(data, offset, end, null); }
	/** As {@link #decodeEvents(byte[],int,int)}; {@code endOut[0]} = the time where the sequence ends, {@code endOut[1]} = the offset of its end marker (-1: none). */
	static java.util.List<MusicEvent> decodeEvents(byte[] data, int offset, int end, long[] endOut) {
		java.util.List<MusicEvent> events = new java.util.ArrayList<MusicEvent>();
		long time = 0; int p = offset, marker = -1;
		end = Math.min(end, data.length);
		while(p + 4 <= end) {
			long word = u32(data, p);
			int shortType = (int)(word >>> 29), xType = (int)(word >>> 28);
			if(shortType < 4) {
				if(shortType == 0) time += word & 0x00FFFFFFL; // rest
				else if(shortType == 1) { // note: pitch 6 bits + 32, velocity 7 bits, duration 11 bits
					MusicEvent e = new MusicEvent(MusicEvent.NOTE, (int)((word >>> 24) & 0x1F), time, p);
					e.pitch = (int)((word >>> 18) & 0x3F) + 32; e.velocity = (int)((word >>> 11) & 0x7F); e.duration = word & 0x7FF;
					events.add(e);
				} else if(shortType == 2) { // controller: 8-bit controller number, signed 8.8 value
					MusicEvent e = new MusicEvent(MusicEvent.CONTROL, (int)((word >>> 24) & 0x1F), time, p);
					e.controller = (int)((word >>> 16) & 0xFF); e.value = (short)(word & 0xFFFF);
					events.add(e);
				} else if(((word >>> 16) & 0xFF) == 0 && (word & 0xFFFF) == 0) { marker = p; break; } // end marker (value 0; others are hints)
				p += 4;
				continue;
			}
			int lengthBits = (int)(word >>> 30);
			int longs = lengthBits == 3 ? (int)(word & 0xFFFF) : 2;
			if(lengthBits == 3 && longs < 2) { if(p + 8 > end) break; longs = (int)u32(data, p + 4); }
			if(longs < 2 || p + longs * 4 > end) break;
			long second = u32(data, p + longs * 4 - 4);
			int part = (int)((word >>> 16) & 0xFFF);
			if(xType == 9) { // extended note: 16-bit pitch (from 256 on 8.8 fractional), 7-bit velocity, 22-bit duration
				MusicEvent e = new MusicEvent(MusicEvent.NOTE, part, time, p);
				int pitch = (int)(word & 0xFFFF); e.pitch = pitch >= 256 ? (pitch + 0x80) >> 8 : pitch;
				e.velocity = (int)((second >>> 22) & 0x7F); e.duration = second & 0x3FFFFF;
				events.add(e);
			} else if(xType == 10) { // extended controller: 16-bit controller number, signed 8.8 value
				MusicEvent e = new MusicEvent(MusicEvent.CONTROL, part, time, p);
				e.controller = (int)(second & 0xFFFF); e.value = (short)(word & 0xFFFF);
				events.add(e);
			} // knob (11) and general events (15: part changes, no-ops ...) do not sound by themselves
			p += longs * 4;
		}
		if(endOut != null) { endOut[0] = time; if(endOut.length > 1) endOut[1] = marker; }
		return events;
	}

	/** A part of a tune header (a kGeneralEventNoteRequest): its number, its General MIDI instrument and the tone's name. */
	static final class NoteRequest {
		final int part, gmNumber, instrumentNumber, polyphony; final byte[] tone; // tone: the 76-byte ToneDescription
		NoteRequest(int part, int gmNumber, int instrumentNumber, int polyphony, byte[] tone) { this.part = part; this.gmNumber = gmNumber; this.instrumentNumber = instrumentNumber; this.polyphony = polyphony; this.tone = tone; }
	}
	/** The note requests of a tune header (general events of subtype 1: NoteRequestInfo 8 bytes, then the ToneDescription), up to its end marker. */
	static java.util.List<NoteRequest> noteRequests(byte[] data, int offset, int end) {
		java.util.List<NoteRequest> list = new java.util.ArrayList<NoteRequest>();
		end = Math.min(end, data.length);
		int event = offset;
		while(event + 4 <= end) {
			long head = u32(data, event);
			if(head == 0x60000000L) break;
			if((head >>> 28) != 0xF) { event += (head >>> 30) == 2 ? 8 : 4; continue; }
			int length = (int)(head & 0xFFFF);
			if(length < 2 || event + length * 4 > end) break;
			long tail = u32(data, event + (length - 1) * 4);
			int subtype = (int)((tail >>> 16) & 0x3FFF);
			if(subtype == 1 && length >= 23) {
				byte[] tone = java.util.Arrays.copyOfRange(data, event + 12, event + 12 + 76);
				list.add(new NoteRequest((int)((head >>> 16) & 0xFFF), (int)u32(data, event + 84), (int)u32(data, event + 80), (short)((data[event + 6] & 0xFF) << 8 | (data[event + 7] & 0xFF)), tone));
			}
			event += length * 4;
		}
		return list;
	}

	/**
	 * The MIDI controller messages ({status, data1, data2}, channel 0) for a QuickTime controller change (8.8 values):
	 * MIDI-numbered controllers below 32 and 64-95 go as they are, pitch bend (32, semitones over the GM range of two) as a
	 * bend, part volume (42) as channel volume, pan (10: 1.0 left .. 2.0 right, 0 = default) as MIDI pan.
	 */
	static int[][] controlMessages(int controller, int value) {
		int midi = Math.max(0, Math.min(127, value >> 8));
		switch(controller) {
			case 32: { int bend = Math.max(0, Math.min(16383, 8192 + value * 4096 / 256)); return new int[][] {{ShortMessage.PITCH_BEND, bend & 0x7F, bend >> 7}}; }
			case 33: return new int[][] {{ShortMessage.CHANNEL_PRESSURE, midi, 0}};
			case 10: { int pan = value <= 0 ? 64 : Math.max(0, Math.min(127, (value - 256) * 127 / 256)); return new int[][] {{ShortMessage.CONTROL_CHANGE, 10, pan}}; }
			case 42: return new int[][] {{ShortMessage.CONTROL_CHANGE, 7, midi}};
			case 64: case 65: case 66: case 67: return new int[][] {{ShortMessage.CONTROL_CHANGE, controller, value > 0 ? 127 : 0}};
			default:
				if(controller > 0 && controller < 32 || controller >= 68 && controller < 96) return new int[][] {{ShortMessage.CONTROL_CHANGE, controller, midi}};
				return new int[0][]; // transposition, tune volume, global controllers: no MIDI counterpart
		}
	}

	private static void assignMissingChannels(Map<Integer,Part> parts) {
		boolean[] used = new boolean[16];
		used[9] = true;
		for(Part part : parts.values()) {
			if((part.gmNumber & 0x4000) != 0) part.channel = 9;
		}
		for(Part part : parts.values()) {
			if(part.channel >= 0) continue;
			part.channel = firstFreeChannel(used);
			used[part.channel] = true;
		}
	}

	private static Part part(Map<Integer,Part> parts, int partNumber) {
		Part existing = parts.get(partNumber);
		if(existing != null) return existing;
		boolean[] used = new boolean[16];
		used[9] = true;
		for(Part part : parts.values()) if(part.channel >= 0) used[part.channel] = true;
		Part created = new Part(1, firstFreeChannel(used));
		parts.put(partNumber, created);
		return created;
	}

	private static int firstFreeChannel(boolean[] used) {
		for(int i=0; i<used.length; i++) if(!used[i]) return i;
		return 0;
	}

	private static void addShort(Track track, int command, int channel, int data1, int data2,
			long tick) throws InvalidMidiDataException {
		ShortMessage message = new ShortMessage();
		message.setMessage(command, channel, data1, data2);
		track.add(new MidiEvent(message, Math.max(0, tick)));
	}

	private static boolean hasMusicDescription(QuickTimeFile.Media media) {
		for(QuickTimeFile.SampleDescription description : media.descriptions)
			if("musi".equals(QuickTimeFile.typeName(description.format))) return true;
		return false;
	}

	static long u32(byte[] data, int offset) {
		if(offset < 0 || offset+4 > data.length) throw new IndexOutOfBoundsException();
		return ((long)(data[offset]&0xFF)<<24) | ((long)(data[offset+1]&0xFF)<<16) |
			((long)(data[offset+2]&0xFF)<<8) | (long)(data[offset+3]&0xFF);
	}

}
