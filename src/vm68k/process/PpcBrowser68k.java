import java.util.ArrayList;
import java.util.List;

/**
 * Pack9, the PPC Browser: PPCBrowser(prompt, applListLabel, defaultSpecified, VAR theLocation, VAR thePortInfo, portFilter,
 * theLocNBPType) : OSErr (MOVE.W #$0D00,D0: 13 words of parameters, selector 0). This Mac is alone on its network, so the
 * browser lists the programs of this machine the PPC Toolbox's IPCListPorts lists too - the one asking (HyperCard for an
 * external), the other running programs, the Finder - in a chooser; the chosen one comes back as its default port with this
 * machine as an NBP location, and an Apple event to that TargetID reaches it (AppleEvents68k). It used to answer
 * userCanceledErr at once: ShowPPCBrowser said "Cancel" and the Apple Events demo's buttons all stopped at CheckTarget.
 * The port filter is not called (every program here is a high-level-event program the user may pick).
 */
final class PpcBrowser68k {
	private PpcBrowser68k(){}

	/** Picks one of the programs' names (index), or -1 for Cancel; a test puts its own in. Null: the Swing chooser (none when headless). */
	static volatile java.util.function.BiFunction<String,List<String>,Integer> chooser;

	static boolean browse(Toolbox68k tb,M68kCpu cpu) throws M68kException {
		M68kMemory memory=tb.memory;int sp=cpu.a[7];
		int nbpType=memory.readLong(sp),portInfo=memory.readLong(sp+8),location=memory.readLong(sp+12),label=memory.readLong(sp+18),prompt=memory.readLong(sp+22);
		cpu.a[7]+=26;
		List<Integer> serials=new ArrayList<Integer>();List<String> names=new ArrayList<String>();
		int me=tb.app!=null?tb.app.serialNumber:tb.stack!=null?Toolbox68k.HYPERCARD_SERIAL:0;
		if(me!=0)serials.add(me);
		for(MacApp68k a:MacApp68k.runningApplications())if(a!=tb.app&&a.toolbox!=null&&!a.finished&&!(a.hosted instanceof ControlPanelHost68k))serials.add(a.serialNumber);
		if(Toolbox68k.finderStandInPresent())serials.add(Toolbox68k.FINDER_SERIAL);
		int scratch=memory.allocate(0x48,2);
		try{for(int i=0;i<serials.size();i++){if(tb.writePortFor(serials.get(i),scratch)!=0){serials.remove(i--);continue;}names.add(tb.readPascal(scratch+2));}}
		finally{memory.free(scratch);}
		String question=prompt==0?"":tb.readPascal(prompt),listLabel=label==0?"":tb.readPascal(label);
		int choice=names.isEmpty()?-1:choose(question.isEmpty()?listLabel:question,names);
		if(Mac68kApi.TRACE)System.err.println("[68K] PPCBrowser "+names+" -> "+choice);
		int result;
		if(choice<0||choice>=serials.size())result=-128; // userCanceledErr
		else{
			if(portInfo!=0){memory.writeWord(portInfo,0);tb.writePortFor(serials.get(choice),portInfo+2);} // PortInfoRec: filler, authRequired false, the port
			if(location!=0){ // LocationNameRec: ppcNBPLocation, this machine by its name in this zone
				memory.fill(location,2+3*34,0);memory.writeWord(location,1);
				tb.writePascal(location+2,Toolbox68k.COMPUTER_NAME,32);
				tb.writePascal(location+2+33,nbpType!=0&&memory.readByte(nbpType)>0?tb.readPascal(nbpType):"PPCToolBox",32);
				tb.writePascal(location+2+66,"*",32);
			}
			result=0;
		}
		memory.writeWord(cpu.a[7],result);cpu.d[0]=result;
		return true;
	}

	private static int choose(String prompt,List<String> names){
		java.util.function.BiFunction<String,List<String>,Integer> c=chooser;
		if(c!=null)return c.apply(prompt,names);
		if(java.awt.GraphicsEnvironment.isHeadless())return -1;
		final int[] picked={-1};
		Runnable show=()->{
			Object[] values=names.toArray();
			Object answer=javax.swing.JOptionPane.showInputDialog(null,prompt,"",javax.swing.JOptionPane.QUESTION_MESSAGE,null,values,values[0]);
			picked[0]=answer==null?-1:names.indexOf(answer);
		};
		if(javax.swing.SwingUtilities.isEventDispatchThread())show.run();
		else try{javax.swing.SwingUtilities.invokeAndWait(show);}catch(Exception e){return -1;}
		return picked[0];
	}
}
