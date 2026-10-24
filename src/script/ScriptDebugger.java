import java.util.ArrayList;

import javax.swing.SwingUtilities;

/**
 * HyperTalkデバッガの状態。
 *
 * 実行モードはTTalk.tracemode(0=実行, 1=トレース, 2=ステップ)で、ステップ実行の再開は
 * TTalk.stepflagで行う。ここではそれに加えて、ハンドラの呼び出しスタック(Step/Step Out用と
 * 変数表示ウィンドウのフレーム切り替え用)と、一時停止中の文の位置を管理する。
 *
 * スクリプトスレッドから呼ばれるのはbeforeStatement/pushFrame/popFrame/pauseAtErrorで、
 * それ以外(step*, go, abort等)はスクリプトエディタのメニューからEDT上で呼ばれる。
 */
class ScriptDebugger {
	static final int MODE_RUN = 0;
	static final int MODE_TRACE = 1;
	static final int MODE_STEP = 2;

	/** 実行中のハンドラ1つ分の情報 */
	static class Frame {
		final OObject object;
		final String message;
		final MemoryData memData;
		final boolean isFunction;
		volatile int line = -1;

		Frame(OObject object, String message, MemoryData memData, boolean isFunction){
			this.object = object;
			this.message = message;
			this.memData = memData;
			this.isFunction = isFunction;
		}

		String title(){
			String name = (isFunction ? "function " : "") + message;
			if(object==null) return name;
			return name + " — " + object.getShortName();
		}
	}

	private static final ArrayList<Frame> frames = new ArrayList<Frame>();

	/** Step(Over)/Step Out用。この深さより深いフレームでは停止しない(-1なら常に停止) */
	private static volatile int stepDepth = -1;

	/** 前回のreset以降にスクリプトが動いたか(アイドル中に毎回EDTへ仕事を投げないため) */
	private static volatile boolean active;

	/** 一時停止中かどうかと、その位置 */
	static volatile boolean paused;
	static volatile boolean pausedOnError;
	static volatile String pausedErrorMessage;
	static volatile OObject pausedObject;
	static volatile int pausedLine = -1;
	static volatile MemoryData pausedMemData;

	//---------------------------------------------------------------- 呼び出しスタック

	static Frame pushFrame(OObject object, String message, MemoryData memData, boolean isFunction){
		Frame frame = new Frame(object, message, memData, isFunction);
		synchronized(frames){
			frames.add(frame);
		}
		active = true;
		return frame;
	}

	/** 実行中のハンドラの深さ(呼び出しスタックの段数) */
	static int frameDepth(){
		synchronized(frames){ return frames.size(); }
	}

	/** 呼び出しスタックの末尾 n 段("object: message" の並び)。再帰エラーの説明用 */
	static String frameTrail(int n){
		StringBuilder sb = new StringBuilder();
		synchronized(frames){
			for(int i=Math.max(0, frames.size()-n); i<frames.size(); i++){
				Frame f = frames.get(i);
				if(sb.length()>0) sb.append(" > ");
				sb.append(f.object==null ? "?" : f.object.getShortName()).append(": ").append(f.message);
			}
		}
		return sb.toString();
	}


	static void popFrame(Frame frame){
		synchronized(frames){
			int i = frames.lastIndexOf(frame);
			if(i>=0) frames.remove(i);
		}
	}

	static int depth(){
		synchronized(frames){
			return frames.size();
		}
	}

	/** 実行中のハンドラを持つオブジェクト(me)。ハンドラ外ならnull */
	static OObject currentObject(){
		synchronized(frames){
			return frames.isEmpty() ? null : frames.get(frames.size()-1).object;
		}
	}

	/** 呼び出しスタックのコピー(0が最も外側) */
	static Frame[] snapshot(){
		synchronized(frames){
			return frames.toArray(new Frame[0]);
		}
	}

	static Frame currentFrame(){
		synchronized(frames){
			if(frames.isEmpty()) return null;
			return frames.get(frames.size()-1);
		}
	}

	/** スクリプトの実行が完全に終わった(アイドルに戻った)とき */
	static void reset(){
		synchronized(frames){
			frames.clear();
		}
		stepDepth = -1;
		paused = false;
		pausedOnError = false;
		pausedErrorMessage = null;
		pausedObject = null;
		pausedLine = -1;
		pausedMemData = null;
		if(!active) return;
		active = false;
		ScriptEditor.clearDebugLine();
		VariableWatcher.refresh();
	}

	/** cmd+option+.やdebug checkpointで次の文から止まるとき、Step Overの残りを消す */
	static void resetStepDepth(){
		stepDepth = -1;
		active = true;
	}

	//---------------------------------------------------------------- 実行制御(EDT)

	/** メニューのStep: 呼び出し先のハンドラには入らずに次の文へ */
	static void stepOver(){
		if(!paused){
			enterStepMode();
			return;
		}
		TTalk.tracemode = MODE_STEP;
		stepDepth = depth();
		TTalk.stepflag = true;
	}

	/** メニューのStep Into: 呼び出し先のハンドラの中でも止まる */
	static void stepInto(){
		if(!paused){
			enterStepMode();
			return;
		}
		TTalk.tracemode = MODE_STEP;
		stepDepth = -1;
		TTalk.stepflag = true;
	}

	/** メニューのStep Out: 現在のハンドラを抜けるまで実行 */
	static void stepOut(){
		if(!paused){
			enterStepMode();
			return;
		}
		TTalk.tracemode = MODE_STEP;
		stepDepth = depth()-1;
		TTalk.stepflag = true;
	}

	/** メニューのTrace: 文ごとにエディタで位置を示しながら(traceDelayを挟んで)実行 */
	static void trace(){
		TTalk.tracemode = MODE_TRACE;
		stepDepth = -1;
		TTalk.stepflag = true;
	}

	/** メニューのGo: 通常実行に戻る(チェックポイントでは止まる) */
	static void go(){
		TTalk.tracemode = MODE_RUN;
		stepDepth = -1;
		TTalk.stepflag = true;
	}

	/** メニューのAbort: cmd+.と同じ */
	static void abort(){
		TTalk.requestUserAbort();
		TTalk.stepflag = true;
	}

	/** 実行中(停止していない)ときにStep系を選んだ場合: 次の文で止まる(cmd+option+.と同じ) */
	private static void enterStepMode(){
		TTalk.requestTrace();
		stepDepth = -1;
	}

	//---------------------------------------------------------------- スクリプトスレッド側

	/**
	 * 文を実行する直前に呼ぶ。チェックポイント・ステップ・トレースの判定と一時停止を行う。
	 * objectのスクリプトそのもの(行番号がエディタと対応する)を実行しているときだけ働き、
	 * do/sendの文字列やメッセージボックスの行では何もしない。
	 */
	static void beforeStatement(OObject object, ArrayList<String> scriptList, int line, MemoryData memData)
	throws xTalkException
	{
		if(TTalk.stop) throw new xTalkException("user abort.");
		if(object==null || scriptList!=object.scriptList) return;
		active = true;

		Frame frame = currentFrame();
		if(frame!=null && frame.memData==memData) frame.line = line;

		boolean checkpoint = object.hasCheckpoint(line);
		int mode = TTalk.tracemode;
		if(checkpoint && mode!=MODE_STEP){
			//チェックポイントに到達: ステップ実行に切り替える。
			//古いstepflagが残っていると最初の停止をすり抜けるので消しておく
			mode = MODE_STEP;
			TTalk.tracemode = MODE_STEP;
			stepDepth = -1;
			TTalk.stepflag = false;
		}
		if(mode==MODE_RUN) return;
		if(mode==MODE_STEP && !checkpoint && stepDepth>=0 && depth()>stepDepth){
			//Step(Over)/Step Out中で、呼び出し先のハンドラを実行中
			return;
		}
		if(mode==MODE_STEP) stepDepth = -1;
		pause(object, line, memData, false, null);
	}

	/**
	 * スクリプトエラーのダイアログで「デバッグ」が選ばれたとき。エラーになった文の位置で
	 * 一時停止し、ローカル変数を確認できるようにする。Abortされたら例外、それ以外(Go/Step/Trace)
	 * なら普通に戻るので、呼び出し側はエラーになった文の次から実行を続ける。
	 */
	static void pauseAtError(OObject object, int line, MemoryData memData, String errorMessage)
	throws xTalkException
	{
		TTalk.tracemode = MODE_STEP;
		stepDepth = -1;
		TTalk.stepflag = false;
		Frame frame = currentFrame();
		if(frame!=null && frame.memData==memData) frame.line = line;
		pause(object, line, memData, true, errorMessage);
	}

	private static void pause(OObject object, int line, MemoryData memData, boolean error, String errorMessage)
	throws xTalkException
	{
		paused = true;
		pausedOnError = error;
		pausedErrorMessage = errorMessage;
		pausedObject = object;
		pausedLine = line;
		pausedMemData = memData;
		try{
			ScriptEditor.showDebugLine(object, line, error);
			VariableWatcher.refresh();

			if(TTalk.tracemode==MODE_TRACE){
				traceSleep();
				return;
			}

			//ステップ実行: メニュー操作(stepflag)か中断(stop)を待つ
			while(!TTalk.stepflag && !TTalk.stop){
				try{
					Thread.sleep(20);
				}catch(InterruptedException e){
					Thread.currentThread().interrupt();
					break;
				}
				if(!scriptEditorVisible()){
					//エディタを閉じたら通常実行に戻る
					TTalk.tracemode = MODE_RUN;
					break;
				}
			}
			TTalk.stepflag = false;
			if(TTalk.stop) throw new xTalkException("user abort.");
		}finally{
			paused = false;
			pausedOnError = false;
			pausedErrorMessage = null;
			pausedMemData = null;
		}
	}

	private static boolean scriptEditorVisible(){
		if(PCARD.pc==null || PCARD.pc.stack==null) return true;
		ScriptEditor editor = PCARD.pc.stack.scriptEditor;
		return editor==null || editor.isVisible();
	}

	/** トレース中の文ごとの待ち時間(the traceDelay、ticks単位) */
	private static void traceSleep(){
		int ticks = 0;
		try{
			ticks = Integer.parseInt(TUtil.compatibilityProperty("tracedelay").trim());
		}catch(NumberFormatException e){
		}
		if(ticks<=0) return;
		long endTime = System.currentTimeMillis() + ticks*1000L/60L;
		while(!TTalk.stop && System.currentTimeMillis()<endTime){
			try{
				Thread.sleep(20);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	//---------------------------------------------------------------- 変数表示ウィンドウ用

	/**
	 * 変数表示ウィンドウでの値の書き換え。一時停止中(スクリプトスレッドが待っている間)か、
	 * スクリプトが動いていないときだけ受け付ける。
	 */
	static boolean setVariableValue(MemoryData memData, String name, String value){
		if(!paused && !TTalk.idle) return false;
		String key = name.toLowerCase();
		if(memData!=null){
			for(int i=0; i<memData.nameList.size(); i++){
				if(key.equals(memData.nameList.get(i))){
					memData.valueList.set(i, value);
					return true;
				}
			}
		}
		MemoryData globalData = TTalk.globalData;
		for(int i=0; i<globalData.nameList.size(); i++){
			if(key.equals(globalData.nameList.get(i))){
				globalData.valueList.set(i, value);
				return true;
			}
		}
		return false;
	}

	static void runOnEdt(Runnable r){
		if(SwingUtilities.isEventDispatchThread()) r.run();
		else SwingUtilities.invokeLater(r);
	}
}
