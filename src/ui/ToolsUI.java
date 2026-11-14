import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.text.*;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import java.awt.Frame;
import javax.swing.JDialog;
import javax.swing.JFrame;

/** Desktop code moved out of the core's TUtil unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class ToolsUI {
	private ToolsUI(){}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction3 (the chain asks this in their place): true when one of them matched. */
	static boolean toolFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("tool")){
			if(Host.get().paintToolActive()){
				if(PCARD.pc.tool.getClass()==SelectTool.class) result.theResult = PCARD.pc.tool==TTalk.selectToolWentLasso ? "lasso tool" : "select tool";
				else if(PCARD.pc.tool.getClass()==LassoTool.class) result.theResult = "lasso tool";
				else if(PCARD.pc.tool.getClass()==SmartSelectTool.class) result.theResult = "magicwand tool";
				else if(PCARD.pc.tool.getClass()==PencilTool.class) result.theResult = "pencil tool";
				else if(PCARD.pc.tool.getClass()==BrushTool.class) result.theResult = "brush tool";
				else if(PCARD.pc.tool.getClass()==PaintBucketTool.class) result.theResult = "bucket tool";
				else if(PCARD.pc.tool.getClass()==LineTool.class) result.theResult = "line tool";
				else if(PCARD.pc.tool.getClass()==RectTool.class) result.theResult = "rectangle tool";
				else if(PCARD.pc.tool.getClass()==RoundRectTool.class) result.theResult = "round rect tool";
				else if(PCARD.pc.tool.getClass()==OvalTool.class) result.theResult = "oval tool";
				else if(PCARD.pc.tool.getClass()==CurveTool.class) result.theResult = "curve tool";
				else if(PCARD.pc.tool.getClass()==TypeTool.class) result.theResult = "text tool";
				else if(PCARD.pc.tool.getClass()==EraserTool.class) result.theResult = "eraser tool";
				else if(PCARD.pc.tool.getClass()==SprayTool.class) result.theResult = "spray tool"; //HyperCard J-2.3 names (probe H Expr ChooseNames)
				else if(PCARD.pc.tool.getClass()==RegPolyTool.class) result.theResult = "regular polygon tool";
				else if(PCARD.pc.tool.getClass()==PolyTool.class) result.theResult = "polygon tool";
				else if(PCARD.pc.tool.getClass()==EyedropperTool.class) result.theResult = "eyedropper tool";
				else if(PCARD.pc.tool.getClass()==SmudgeTool.class) result.theResult = "smudge tool";
				else if(PCARD.pc.tool.getClass()==StampTool.class) result.theResult = "stamp tool";
			}
			else if(Host.get().authToolActive()){
				if(AuthTool.tool.getClass()==ButtonTool.class) result.theResult = "button tool";
				else if(AuthTool.tool.getClass()==FieldTool.class) result.theResult = "field tool";
				else if(AuthTool.tool instanceof ShapeTool) result.theResult = "shape tool";
			}
			else result.theResult = "browse tool";
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction3 (the chain asks this in their place): true when one of them matched. */
	static boolean settingsFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("userlevel")){
			result.theResult = Integer.toString(StackProtection.effectiveUserLevel());
			result.ret = 0;
		}
		else if(0==message.compareTo("scripttextfont")){
			result.theResult = PCARD.scriptFont;
			result.ret = 0;
		}
		else if(0==message.compareTo("scripttextsize")){
			result.theResult = Integer.toString(PCARD.scriptFontSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("systemlang")){
			result.theResult = International.englishName(PCARD.pc.lang); //"Japanese"/"English"(以前の版と同じ英語の名前)
			result.ret = 0;
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction3 (the chain asks this in their place): true when one of them matched. */
	static boolean paintFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("editbkgnd")){
			result.theResult = Boolean.toString(HZ.editBackground);
			result.ret = 0;
		}
		else if(0==message.compareTo("filled")){
			result.theResult = Boolean.toString(PCARD.pc.fill);
			result.ret = 0;
		}
		else if(0==message.compareTo("centered")){
			result.theResult = Boolean.toString(PaintTool.drawCentered);
			result.ret = 0;
		}
		else if(0==message.compareTo("multiple")){
			result.theResult = Boolean.toString(PaintTool.drawMultiple);
			result.ret = 0;
		}
		else if(0==message.compareTo("multispace")){
			result.theResult = Integer.toString(PaintTool.multiSpace);
			result.ret = 0;
		}
		else if(0==message.compareTo("polysides")){
			result.theResult = Integer.toString(PaintTool.regPolySides);
			result.ret = 0;
		}
		else if(0==message.compareTo("gradient")){
			//塗りの種類(GradButton): off / linear / radial
			GradButton g = PCARD.pc.grad;
			result.theResult = g==null ? "off" : g.kind()==0 ? "off" : g.kind()==1 ? "linear" : g.kind()==2 ? "radial" : "texture";
			result.ret = 0;
		}
		else if(0==message.compareTo("filltexture")){
			//テクスチャ塗りに使うテクスチャの名前(内蔵名 / custom / none)
			result.theResult = PaintTexture.currentName;
			result.ret = 0;
		}
		else if(0==message.compareTo("gradientangle")){
			GradButton g = PCARD.pc.grad;
			result.theResult = Integer.toString(g==null ? 0 : GToolOptions.AngleDial.normalize((int)Math.round(Math.toDegrees(g.angle))));
			result.ret = 0;
		}
		else if(0==message.compareTo("filltolerance")){
			result.theResult = Integer.toString(PaintTool.fillTolerancePercent);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushsize")){
			result.theResult = Integer.toString(PaintTool.brushSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushhardness")){
			result.theResult = Integer.toString(PaintTool.brushHardness);
			result.ret = 0;
		}
		else if(0==message.compareTo("smudgestrength")){
			result.theResult = Integer.toString(PaintTool.smudgeStrength);
			result.ret = 0;
		}
		else if(0==message.compareTo("stampsource")){
			//スタンプの写し元(未指定なら空)
			result.theResult = StampTool.source==null ? "" : StampTool.source.x+","+StampTool.source.y;
			result.ret = 0;
		}
		else if(0==message.compareTo("brushopacity")){
			result.theResult = Integer.toString(PaintTool.brushOpacity);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushspacing")){
			result.theResult = Integer.toString(PaintTool.brushSpacing);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushstabilization") || 0==message.compareTo("brushstabilizer")){
			result.theResult = Integer.toString(PaintTool.brushStabilization);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushpressure")){
			//ペンの筆圧を何に効かせるか: size / opacity / size,opacity / none
			result.theResult = PaintTool.pressureSize ? (PaintTool.pressureOpacity ? "size,opacity" : "size") : (PaintTool.pressureOpacity ? "opacity" : "none");
			result.ret = 0;
		}
		else if(0==message.compareTo("penpressure")){
			//ペンタブレットの今の筆圧(0〜100、浮かせていれば0)。最近ペンが使われていなければ空
			TabletInput.start();
			float p = TabletInput.latest();
			result.theResult = Float.isNaN(p) ? "" : Integer.toString(Math.round(p*100));
			result.ret = 0;
		}
		else if(0==message.compareTo("screenfilter")){
			//the image filters the card window is shown through (TalkImage), empty for none
			result.theResult = TalkImage.screenFilterText;
			result.ret = 0;
		}
		else if(0==message.compareTo("screenoverlay")){
			result.theResult = TalkImage.screenOverlayData;
			result.ret = 0;
		}
		else if(0==message.compareTo("zoom")){
			//ペイントの表示倍率(%)。100が実寸、800がFatBits
			result.theResult = Integer.toString(PaintTool.zoomPercent(PCARD.pc));
			result.ret = 0;
		}
		else if(0==message.compareTo("zoomorigin")){
			//拡大表示で左上に見えているカード座標
			result.theResult = PaintTool.viewLeftOf(PCARD.pc)+","+PaintTool.viewTopOf(PCARD.pc);
			result.ret = 0;
		}
		else if(0==message.compareTo("powerkeys")){
			result.theResult = Boolean.toString(PaintTool.powerKeys);
			result.ret = 0;
		}
		else if(0==message.compareTo("gridsize")){
			//描画グリッドの大きさ(Options > Drawing Grid Size)
			result.theResult = Integer.toString(PaintTool.drawingGridSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("paintlayer")){
			//編集中のレイヤー番号(1=Card Paint/Background Paint、2〜=追加レイヤー)
			result.theResult = Integer.toString(PCARD.pc.mainImg==null ? 1 : PCARD.pc.activeLayer()+1);
			result.ret = 0;
		}
		else if(0==message.compareTo("paintlayercount")){
			result.theResult = Integer.toString(PaintLayers.count(PCARD.pc));
			result.ret = 0;
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.setHyperCardProperty (the chain asks this in their place): true when one of them matched. */
	static boolean textSettingsSetters(String inProperty, String value, String prpty) throws xTalkException {
		if(0==prpty.compareTo("scripttextfont")){
				PCARD.scriptFont = value;
			}
			else if(0==prpty.compareTo("scripttextsize")){
				try{
					PCARD.scriptFontSize = Integer.valueOf(value);
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
			}
			else if(0==prpty.compareTo("textfont")){
				PCARD.pc.textFont = value;
				Host.get().runOnEDT(TypeTool::refreshLive);
			}
			else if(0==prpty.compareTo("textstyle")){
				PCARD.pc.textStyle = TUtil.textStyleValue(value);
				Host.get().runOnEDT(TypeTool::refreshLive);
			}
			else if(0==prpty.compareTo("textsize")){
				try{
					PCARD.pc.textSize = (int)Double.parseDouble(value.trim()); //" 9" and "10.0" as in HyperCard J-2.3
					Host.get().runOnEDT(TypeTool::refreshLive);
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.setHyperCardProperty (the chain asks this in their place): true when one of them matched. */
	static boolean cursorSetters(String inProperty, String value, String prpty) throws xTalkException {
		if(0==prpty.compareTo("powerkeys")){
				PaintTool.setPowerKeys(TUtil.propertyBoolean(inProperty, value));
			}
			else if(0==prpty.compareTo("cursor")){
				Cursor cr = null;
						/*new Cursor(Cursor.CROSSHAIR_CURSOR) ,
						new Cursor(Cursor.DEFAULT_CURSOR) ,
						new Cursor(Cursor.HAND_CURSOR) ,
						new Cursor(Cursor.MOVE_CURSOR) ,
						new Cursor(Cursor.WAIT_CURSOR) ,
						new Cursor(Cursor.TEXT_CURSOR) ,
						new Cursor(Cursor.SW_RESIZE_CURSOR) ,
						new Cursor(Cursor.SE_RESIZE_CURSOR) ,
						new Cursor(Cursor.NW_RESIZE_CURSOR) ,
						new Cursor(Cursor.NE_RESIZE_CURSOR) ,
						new Cursor(Cursor.N_RESIZE_CURSOR) ,
						new Cursor(Cursor.S_RESIZE_CURSOR) ,
						new Cursor(Cursor.W_RESIZE_CURSOR) ,
						new Cursor(Cursor.E_RESIZE_CURSOR) ,*/
				//名前の付いたカーソルは HyperCard では CURS の ID(hand 128, ibeam 1, cross 2, plus 3, watch 4)。
				//スタックに同じ ID の CURS があればそれが出る(VM の HyperCard で確認)
				if(value.equalsIgnoreCase("hand") || value.equalsIgnoreCase("browse")){
					cr = TUtil.browseCursor();
				}else if(value.equalsIgnoreCase("watch")){
					cr = TUtil.hyperCardCursor(4, Cursor.WAIT_CURSOR);
				}else if(value.equalsIgnoreCase("busy")){
					cr = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);
				}else if(value.equalsIgnoreCase("arrow")){
					cr = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
				}else if(value.equalsIgnoreCase("cross")){
					cr = TUtil.hyperCardCursor(2, Cursor.CROSSHAIR_CURSOR);
				}else if(value.equalsIgnoreCase("plus")){
					cr = TUtil.hyperCardCursor(3, Cursor.CROSSHAIR_CURSOR);
				}else if(value.equalsIgnoreCase("ibeam")){
					cr = TUtil.hyperCardCursor(1, Cursor.TEXT_CURSOR);
				}else if(value.equalsIgnoreCase("none")){
					synchronized(TUtil.class){
						if(TUtil.noneCursor==null) TUtil.noneCursor = Toolkit.getDefaultToolkit().createCustomCursor(
								new BufferedImage(16,16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "none-cursor");
						cr = TUtil.noneCursor;
					}
				}else{
					//CURSは生データ(.data)のまま保存されていることがあるため、
					//ImageIO.readではなくgetImage()経由でデコードする(Rsrc.loadImage
					//参照)。Inside Macintoshの慣習どおり、同じIDのcrsr(カラー
					//カーソル)があればモノクロのCURSより優先して使う(ホットスポットも
					//同じ優先順位で取得する。Rsrc.getDisplayImage/getDisplayHotSpot参照)。
					//名前で見つけたカーソルはIDで引き直さない(Homeの名前とこのスタックのIDが重なると別のカーソルになる)
					Rsrc rsrc = HZ.stack().rsrc;
					boolean named = rsrc.findByNameAll(value, "CURS")!=null;
					int rsrcid = !named && value.matches("[0-9]+") ? Integer.valueOf(value) : 0;
					BufferedImage bi = named ? rsrc.getDisplayImage(value, "CURS") : rsrc.getDisplayImage(rsrcid, "CURS");
					if(bi!=null){
						Point hotSpot = named ? rsrc.getDisplayHotSpot(value) : rsrc.getDisplayHotSpot(rsrcid);
						//ホットスポットの記録が無いカーソル(Internet Tour の待ちカーソル)は中央。null は createCustomCursor が落ちる
						if(hotSpot==null) hotSpot = new Point(bi.getWidth()/2, bi.getHeight()/2);
						cr = TUtil.customCursor(bi, hotSpot);
					}else if(value.matches("[0-9]+") && TUtil.CLASSIC_MAC_CURSOR_IDS.containsKey(Integer.valueOf(value))){
						//スタックのリソースフォークには入っていない、classic Mac OS標準の
						//システムカーソル（Toolbox内蔵）。現行OSの近いカーソルで代替する。
						cr = Cursor.getPredefinedCursor(TUtil.CLASSIC_MAC_CURSOR_IDS.get(Integer.valueOf(value)));
					}
					//else: 存在しないカーソルが指定された場合、HyperCardと同様にエラーには
					//せず何もしない（現在のカーソルのまま）。
				}
				//同じカーソルなら設定し直さない(Component.setCursor は同じでもカーソルの更新を走らせる)
				if(cr!=null && PCARD.pc.getCursor()!=cr)
					PCARD.pc.setCursor(cr);
				TUtil.compatibilityProperties.put("cursor", value);
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.setHyperCardProperty (the chain asks this in their place): true when one of them matched. */
	static boolean paintSetters(String inProperty, String value, String prpty) throws xTalkException {
		if(0==prpty.compareTo("editbkgnd")){
				boolean on = TUtil.propertyBoolean("editBkgnd", value);
				//editBkgnd は userLevel 3(Painting)以上でだけ使える
				if(on && StackProtection.effectiveUserLevel()<3) throw new xTalkException("editBkgnd requires userLevel 3 or higher");
				HZ.editBackground = on;
			}
			else if(0==prpty.compareTo("filled")){
				PCARD.pc.fill = TUtil.propertyBoolean("filled", value);
			}
			else if(0==prpty.compareTo("centered")){
				PaintTool.drawCentered = TUtil.propertyBoolean("centered", value);
				TUtil.compatibilityProperties.put("centered", Boolean.toString(PaintTool.drawCentered));
			}
			else if(0==prpty.compareTo("multiple")){
				PaintTool.drawMultiple = TUtil.propertyBoolean("multiple", value);
				TUtil.compatibilityProperties.put("multiple", Boolean.toString(PaintTool.drawMultiple));
				Host.get().runOnEDT(() -> GMenu.changeSelected("Options","Draw Multiple",PaintTool.drawMultiple));
			}
			else if(0==prpty.compareTo("multispace")){
				//1以上。メニューは1〜9だが、HC J-2.3 は set the multiSpace to 10 も受け付けて 10 を返す(プローブ A Err MultiSpace)
				int space = TUtil.propertyInteger("multiSpace", value);
				if(space<1) throw new xTalkException("multiSpace must be between 1 and {0}", PaintTool.MULTI_SPACE_MAX);
				PaintTool.setMultiSpace(space);
			}
			else if(0==prpty.compareTo("polysides")){
				int sides = TUtil.propertyInteger("polySides", value);
				//HyperCard J-2.3 keeps 0 and brings the others into 3..50 (-1 → 3, 2 → 3, 60 → 50; Mosaïcon: set the polysides to random (49))
				if(sides!=0) sides = Math.max(3, Math.min(50, sides));
				PaintTool.regPolySides = sides;
			}
			else if(0==prpty.compareTo("gradient")){
				//set the gradient to off / linear / radial (グラデーションボタンと同じ)
				String v = value.trim().toLowerCase(java.util.Locale.ROOT);
				int kind;
				if(v.equals("off") || v.equals("false") || v.equals("none") || v.equals("0")) kind = 0;
				else if(v.equals("linear") || v.equals("on") || v.equals("true") || v.equals("1")) kind = 1;
				else if(v.equals("radial") || v.equals("circular") || v.equals("2")) kind = 2;
				else if(v.equals("texture") || v.equals("3")) kind = 3;
				else throw new xTalkException("gradient requires off, linear, radial or texture");
				final int k = kind;
				Host.get().runOnEDT(() -> { if(PCARD.pc.grad!=null) PCARD.pc.grad.setKind(k); });
			}
			else if(0==prpty.compareTo("gradientangle")){
				int deg = TUtil.propertyInteger("gradientAngle", value);
				Host.get().runOnEDT(() -> {
					if(PCARD.pc.grad!=null){ PCARD.pc.grad.angle = Math.toRadians(GToolOptions.AngleDial.normalize(deg)); PCARD.pc.grad.makeIcon(); }
				});
			}
			else if(0==prpty.compareTo("filltexture")){
				//set the fillTexture to Wood / Marble / … / custom / none / 画像ファイルのパス。
				//none以外なら塗りの種類もテクスチャにする
				if(!PaintTexture.select(value)) throw new xTalkException("fillTexture requires a built-in texture name or an image file: {0}", value);
				Host.get().runOnEDT(() -> { if(PCARD.pc.grad!=null) PCARD.pc.grad.setKind(PaintTexture.current==null ? 0 : 3); });
			}
			else if(0==prpty.compareTo("filltolerance")){
				int t = TUtil.propertyInteger("fillTolerance", value);
				if(t<0 || t>100) throw new xTalkException("fillTolerance must be between 0 and 100");
				PaintTool.fillTolerancePercent = t;
			}
			else if(0==prpty.compareTo("brushsize")){
				int v = TUtil.propertyInteger("brushSize", value);
				if(v<1 || v>256) throw new xTalkException("brushSize must be between 1 and 256");
				PaintTool.brushSize = v;
				PaintTool.classicBrush = 0;
				Host.get().runOnEDT(GToolOptions::changeCursor);
			}
			else if(0==prpty.compareTo("brushhardness")){
				int v = TUtil.propertyInteger("brushHardness", value);
				if(v<0 || v>100) throw new xTalkException("brushHardness must be between 0 and 100");
				PaintTool.brushHardness = v;
			}
			else if(0==prpty.compareTo("smudgestrength")){
				int v = TUtil.propertyInteger("smudgeStrength", value);
				if(v<1 || v>100) throw new xTalkException("smudgeStrength must be between 1 and 100");
				PaintTool.smudgeStrength = v;
			}
			else if(0==prpty.compareTo("stampsource")){
				//set the stampSource to x,y (空で解除)
				String v = value.trim();
				if(v.length()==0){ StampTool.source = null; StampTool.offsetSet = false; }
				else{
					String[] parts = v.split(",");
					if(parts.length!=2) throw new xTalkException("stampSource requires \"x,y\"");
					try{
						int x = Integer.parseInt(parts[0].trim()), y = Integer.parseInt(parts[1].trim());
						StampTool.source = new java.awt.Point(x, y); StampTool.offsetSet = false;
					}catch(NumberFormatException err){ throw new xTalkException("stampSource requires \"x,y\""); }
				}
				Host.get().runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("brushopacity")){
				int v = TUtil.propertyInteger("brushOpacity", value);
				if(v<1 || v>100) throw new xTalkException("brushOpacity must be between 1 and 100");
				PaintTool.brushOpacity = v;
			}
			else if(0==prpty.compareTo("brushspacing")){
				int v = TUtil.propertyInteger("brushSpacing", value);
				if(v<1 || v>200) throw new xTalkException("brushSpacing must be between 1 and 200");
				PaintTool.brushSpacing = v;
			}
			else if(0==prpty.compareTo("brushpressure")){
				//set the brushPressure to size,opacity (both / none / 空も可)
				boolean size = false, opacity = false;
				for(String w : value.toLowerCase(java.util.Locale.ROOT).split("[,\\s]+")){
					if(w.isEmpty() || w.equals("none") || w.equals("false")) continue;
					else if(w.equals("size")) size = true;
					else if(w.equals("opacity")) opacity = true;
					else if(w.equals("both") || w.equals("true")){ size = true; opacity = true; }
					else throw new xTalkException("brushPressure must be size, opacity, both or none");
				}
				PaintTool.pressureSize = size;
				PaintTool.pressureOpacity = opacity;
			}
			else if(0==prpty.compareTo("penpressure")){
				throw new xTalkException("Can't set the penPressure");
			}
			else if(0==prpty.compareTo("brushstabilization") || 0==prpty.compareTo("brushstabilizer")){
				int v = TUtil.propertyInteger(inProperty, value);
				if(v<0 || v>98) throw new xTalkException("{0} must be between 0 and 98", inProperty);
				PaintTool.brushStabilization = v;
			}
			else if(0==prpty.compareTo("screenfilter")){
				//set the screenFilter to "grayscale; blur 2": the card window through imageFilter's steps; empty or none ends it
				TalkImage.setScreenFilter(value);
			}
			else if(0==prpty.compareTo("screenoverlay")){
				//set the screenOverlay to <image data>: drawn over the card window (top left at the card's); empty removes it
				TalkImage.setScreenOverlay(value);
			}
			else if(0==prpty.compareTo("zoom")){
				//set the zoom to 300 (%)。25〜3200
				float pct;
				try{ pct = Float.parseFloat(value.trim().replace("%","")); }
				catch(NumberFormatException err){ throw new xTalkException("zoom requires a number (%)"); }
				if(pct<PaintTool.ZOOM_MIN*100 || pct>PaintTool.ZOOM_MAX*100)
					throw new xTalkException("zoom must be between {0} and {1}", (int)(PaintTool.ZOOM_MIN*100), (int)(PaintTool.ZOOM_MAX*100));
				Host.get().runOnEDT(() -> { PaintTool.owner = PCARD.pc; PaintTool.setZoom(PCARD.pc, pct/100f, -1, -1); });
			}
			else if(0==prpty.compareTo("zoomorigin")){
				//set the zoomOrigin to x,y: 拡大表示の左上に来るカード座標(見える範囲に収める)
				String[] parts = value.split(",");
				if(parts.length!=2) throw new xTalkException("zoomOrigin requires a point (x,y)");
				int ox, oy;
				try{ ox = Integer.parseInt(parts[0].trim()); oy = Integer.parseInt(parts[1].trim()); }
				catch(NumberFormatException err){ throw new xTalkException("zoomOrigin requires a point (x,y)"); }
				Host.get().runOnEDT(() -> {
					if(PCARD.pc.bit==1) return;
					PCARD.pc.bitLeft = ox; PCARD.pc.bitTop = oy;
					PaintTool.clampView(PCARD.pc);
					PCARD.pc.mainPane.repaint();
					FatBitsPreview.repaintFor(PCARD.pc);
				});
			}
			else if(0==prpty.compareTo("grid")){
				PaintTool.drawingGrid = TUtil.propertyBoolean("grid", value);
			}
			else if(0==prpty.compareTo("gridsize")){
				int size = TUtil.propertyInteger("gridSize", value);
				if(size<1 || size>PaintTool.GRID_SIZE_MAX) throw new xTalkException("gridSize must be between 1 and {0}", PaintTool.GRID_SIZE_MAX);
				PaintTool.setDrawingGridSize(size);
			}
			else if(0==prpty.compareTo("paintlayer")){
				//set the paintLayer to N / "name"
				final String v = value;
				final int[] found = new int[1];
				Host.get().runOnEDT(() -> {
					found[0] = PaintLayers.find(PCARD.pc, v);
					if(found[0]>=0) PaintLayers.setActive(PCARD.pc, found[0]);
				});
				if(found[0]<0) throw new xTalkException("No such layer: {0}", value);
			}
			else if(0==prpty.compareTo("layername")){
				final String v = value;
				Host.get().runOnEDT(() -> PaintLayers.rename(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layeropacity")){
				int v = TUtil.propertyInteger("layerOpacity", value);
				if(v<0 || v>100) throw new xTalkException("layerOpacity must be between 0 and 100");
				Host.get().runOnEDT(() -> PaintLayers.setOpacity(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layerblend")){
				int mode = PaintLayer.parseBlend(value);
				if(mode<0) throw new xTalkException("layerBlend requires a blend mode name (Normal, Multiply, etc.)");
				Host.get().runOnEDT(() -> PaintLayers.setBlend(PCARD.pc, mode));
			}
			else if(0==prpty.compareTo("layervisible")){
				boolean v = TUtil.propertyBoolean("layerVisible", value);
				Host.get().runOnEDT(() -> PaintLayers.setVisible(PCARD.pc, PCARD.pc.activeLayer(), v));
			}
			else if(0==prpty.compareTo("layeralphalock")){
				boolean v = TUtil.propertyBoolean("layerAlphaLock", value);
				Host.get().runOnEDT(() -> PaintLayers.setAlphaLock(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layerclip")){
				boolean v = TUtil.propertyBoolean("layerClip", value);
				Host.get().runOnEDT(() -> PaintLayers.setClipToBelow(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("symmetry")){
				//set the symmetry to none/vertical/horizontal/quadrant/radial [N]
				int kind = PaintTool.parseSymmetry(value);
				if(kind<0) throw new xTalkException("symmetry requires none, vertical, horizontal, quadrant or radial");
				PaintTool.setSymmetry(kind);
			}
			else if(0==prpty.compareTo("symmetryaxes")){
				int n = TUtil.propertyInteger("symmetryAxes", value);
				if(n<PaintTool.SYMMETRY_AXES_MIN || n>PaintTool.SYMMETRY_AXES_MAX) throw new xTalkException("symmetryAxes must be between {0} and {1}", PaintTool.SYMMETRY_AXES_MIN, PaintTool.SYMMETRY_AXES_MAX);
				PaintTool.setSymmetryAxes(n);
				Host.get().runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("symmetrycenter")){
				if(!PaintTool.setSymmetryCenter(value)) throw new xTalkException("symmetryCenter requires \"x,y\"");
				Host.get().runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("linesize")){
				try{
					PaintTool.lineSize = Float.parseFloat(value);
				}catch(NumberFormatException err){
					throw new xTalkException("lineSize requires a number");
				}
			}
			else if(0==prpty.compareTo("pattern") || 0==prpty.compareTo("brushpattern") ||
					0==prpty.compareTo("penpattern") || 0==prpty.compareTo("fillpattern")){
				//set the pattern to empty leaves the pattern as it is (HC J-2.3 measured; J-Paint's Fill/Fill_Sp menu items pass an empty pat)
				if(value==null || value.trim().isEmpty()) return true;
				int pattern = TUtil.propertyInteger(inProperty, value);
				if(pattern<1 || pattern>40) throw new xTalkException("{0} must be between 1 and 40", inProperty);
				if(PCARD.pc.pat!=null) PCARD.pc.pat.select(pattern-1);
			}
			else if(0==prpty.compareTo("brushcolor") || 0==prpty.compareTo("pencolor")){
				final Color col = TUtil.paintColorValue(inProperty, value, false);
				if(PCARD.pc.fore!=null) Host.get().runOnEDT(() -> PCARD.pc.fore.makeIcon(col));
			}
			else if(0==prpty.compareTo("brushbackcolor") || 0==prpty.compareTo("penbackcolor")){
				final Color col = TUtil.paintColorValue(inProperty, value, true);
				if(PCARD.pc.back!=null) Host.get().runOnEDT(() -> PCARD.pc.back.makeIcon(col));
			}
			else if(0==prpty.compareTo("messagewatcher")){
				if(MessageWatcher.watcherWindow!=null) MessageWatcher.watcherWindow.setVisible(TUtil.propertyBoolean("messageWatcher", value));
			}
			else if(0==prpty.compareTo("variablewatcher")){
				if(VariableWatcher.watcherWindow!=null) VariableWatcher.watcherWindow.setVisible(TUtil.propertyBoolean("variableWatcher", value));
			}
			else if(0==prpty.compareTo("brush")){
				//HyperCardのブラシ形1〜32(Hyper書道は止まっている間に番号を変えて筆を太らせる)
				int v = TUtil.propertyInteger("brush", value);
				TUtil.compatibilityProperties.put("brush", Integer.toString(v)); //the brush is the number ("2.00" under numberFormat is 2)
				if(v>=1 && v<=32){
					PaintTool.classicBrush = v;
					Host.get().runOnEDT(GToolOptions::changeCursor);
				}
			}
		else return false;
		return true;
	}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec10 (the chain asks this in their place): true when one of them matched. */
	static boolean chooseCommand(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if(str=="choose"){
			if(stringList.size()<2) throw new xTalkException("Cannot understand choose");
			String toolName;
			if(stringList.get(1).equalsIgnoreCase("tool")){
				String numberText = self.Evalution(stringList, typeAry, 2,
					stringList.size()-1, memData, object, target);
				try {
					double number = Double.valueOf(numberText);
					if(number != Math.rint(number)) throw new NumberFormatException();
					toolName = self.toolNameForNumber((int)number);
				} catch (NumberFormatException e) {
					throw new xTalkException("choose tool requires an integer from 1 to 18");
				}
			}
			else if(stringList.get(stringList.size()-1).equalsIgnoreCase("tool")){
				StringBuilder name = new StringBuilder();
				//choose the browse tool (Power Tools Palette Maker)
				int first = stringList.size()>3 && typeAry[1]!=TTalk.wordType.STRING && stringList.get(1).equalsIgnoreCase("the") ? 2 : 1;
				for(int i=first; i<stringList.size()-1; i++){
					if(name.length()>0) name.append(' ');
					name.append(stringList.get(i));
				}
				try{ toolName = self.normalizeToolName(name.toString()); }
				catch(xTalkException e){
					//choose item n of toolList tool: the words before "tool" are an expression (背景用ﾊﾟﾀｰﾝｼﾞｪﾈﾚｰﾀｰ)
					String v;
					try{ v = self.Evalution(stringList, typeAry, first, stringList.size()-2, memData, object, target).trim(); }
					catch(xTalkException e2){ throw e; }
					if(v.toLowerCase(Locale.ROOT).endsWith(" tool")) v = v.substring(0, v.length()-5).trim();
					toolName = v.matches("[0-9]+") ? self.toolNameForNumber(Integer.parseInt(v)) : self.normalizeToolName(v);
				}
			}
			else{
				//choose "Select Tool" / choose theTool: the tool named by an expression (Graph Maker)
				String v = self.Evalution(stringList, typeAry, 1, stringList.size()-1, memData, object, target).trim();
				if(v.toLowerCase(Locale.ROOT).endsWith(" tool")) toolName = self.normalizeToolName(v.substring(0, v.length()-5).trim());
				else if(v.matches("[0-9]+")) toolName = self.toolNameForNumber(Integer.parseInt(v));
				else throw new xTalkException("Cannot understand choose");
			}

			if(!self.isImplementedTool(toolName)){
				throw new xTalkException("Tool \"{0}\" is not yet supported", toolName);
			}
			if(!TBButtonListener.isBrowseTool(toolName)) self.closeOpenField(true, true); //another tool closes the open field (HC J-2.3)
			if(!TBButtonListener.ChangeTool(toolName, null)){
				throw new xTalkException("No such tool: \"{0}\"", toolName);
			}
			PCARD.pc.mainPane.repaint();
		}
		else return false;
		return true;
	}

	// from TTalk
	/** Lines of TTalk.resetIdleCursor (the core calls this in their place). */
	static void browseCursorNow() {
		Cursor cr = TUtil.browseCursor(); //スタックの CURS 128 があればそれ(HyperCard のブラウズカーソル)
		if(PCARD.pc.getCursor()!=cr) PCARD.pc.setCursor(cr);
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction4 (the chain asks this in their place): true when one of them matched. */
	static boolean paintLayerFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("layername")){
			result.theResult = PCARD.pc.mainImg==null ? "" : PaintLayers.nameOf(PCARD.pc, PCARD.pc.activeLayer());
			result.ret = 0;
		}
		else if(0==message.compareTo("layeropacity") || 0==message.compareTo("layerblend") ||
				0==message.compareTo("layervisible") || 0==message.compareTo("layeralphalock") || 0==message.compareTo("layerclip")){
			PaintLayer l = PCARD.pc.mainImg==null ? null : PCARD.pc.activePaintLayer();
			if(0==message.compareTo("layeropacity")) result.theResult = l==null ? "100" : Integer.toString(l.opacity);
			else if(0==message.compareTo("layerblend")) result.theResult = l==null ? "Normal" : PaintLayer.blendName(l.blend);
			else if(0==message.compareTo("layervisible")) result.theResult = l==null ? "true" : Boolean.toString(l.visible);
			else if(0==message.compareTo("layeralphalock")) result.theResult = l==null ? "false" : Boolean.toString(l.alphaLock);
			else result.theResult = l==null ? "false" : Boolean.toString(l.clipToBelow);
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetry")){
			//対称描画(Options > Symmetry): none / vertical / horizontal / quadrant / radial
			result.theResult = PaintTool.symmetryName();
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetryaxes")){
			result.theResult = Integer.toString(PaintTool.symmetryAxes);
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetrycenter")){
			//中心の座標。未設定ならキャンバスの中心
			java.awt.image.BufferedImage sfc = PCARD.pc.getSurface();
			if(sfc==null) sfc = HZ.stack()==null ? null : HZ.stack().curCard==null ? null : HZ.stack().curCard.pict;
			float cx = PaintTool.symmetryCenterX, cy = PaintTool.symmetryCenterY;
			if(cx<0 || cy<0){
				if(sfc!=null){ cx = sfc.getWidth()/2f; cy = sfc.getHeight()/2f; }
				else if(HZ.stack()!=null){ cx = HZ.stack().width/2f; cy = HZ.stack().height/2f; }
				else { cx = 0; cy = 0; }
			}
			result.theResult = Math.round(cx)+","+Math.round(cy);
			result.ret = 0;
		}
		else if(0==message.compareTo("grid")){
			result.theResult = Boolean.toString(PaintTool.drawingGrid);
			result.ret = 0;
		}
		else if(0==message.compareTo("linesize")){
			result.theResult = TTalk.numFormat.format(PaintTool.lineSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("pattern") || 0==message.compareTo("brushpattern") ||
				0==message.compareTo("penpattern") || 0==message.compareTo("fillpattern")){
			result.theResult = PCARD.pc.pat==null ? "1" : Integer.toString(PCARD.pc.pat.pattern+1);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushcolor") || 0==message.compareTo("pencolor")){
			result.theResult = TUtil.paintColorText(PCARD.pc.fore==null ? Color.BLACK : PCARD.pc.fore.color);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushbackcolor") || 0==message.compareTo("penbackcolor")){
			result.theResult = TUtil.paintColorText(PCARD.pc.back==null ? Color.WHITE : PCARD.pc.back.color);
			result.ret = 0;
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction4 (the chain asks this in their place): true when one of them matched. */
	static boolean paintTextFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("textsize")){
			result.theResult = Integer.toString(PCARD.pc.textSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("textstyle")){
			result.theResult = TUtil.textStyleName(PCARD.pc.textStyle);
			result.ret = 0;
		}
		else return false;
		return true;
	}

	// from TUtil
	static String getPatternProperty(OPattern op, String prpty) throws xTalkException {
		java.awt.image.BufferedImage img = op.image();
		if(img==null) throw new xTalkException("No such pattern");
		int w=img.getWidth(), h=img.getHeight();
		if(prpty.equals("width")) return Integer.toString(w);
		if(prpty.equals("height")) return Integer.toString(h);
		if(prpty.equals("number")) return Integer.toString(op.index+1);
		if(prpty.equals("name") || prpty.equals("short name") || prpty.equals("long name")) return op.getShortName();
		if(prpty.equals("imagedata")){
			StringBuilder sb = new StringBuilder(w*h*4);
			for(int y=0;y<h;y++) for(int x=0;x<w;x++){
				int c = img.getRGB(x,y);
				sb.append((char)0).append((char)((c>>16)&0xFF)).append((char)((c>>8)&0xFF)).append((char)(c&0xFF));
			}
			return sb.toString();
		}
		if(prpty.equals("text")){
			StringBuilder sb = new StringBuilder();
			for(int y=0;y<h;y++){
				if(y>0) sb.append('\n');
				for(int x=0;x<w;x++){
					int c = img.getRGB(x,y);
					int a=(c>>>24)&0xFF, lum=(((c>>16)&255)*299+((c>>8)&255)*587+(c&255)*114)/1000;
					sb.append(a>=128 && lum<128 ? '1' : '0');
				}
			}
			return sb.toString();
		}
		return null;
	}

	// from TUtil
	static void setPatternProperty(OPattern op, String prpty, String value) throws xTalkException {
		java.awt.image.BufferedImage img = op.image();
		if(img==null) throw new xTalkException("No such pattern");
		int w=img.getWidth(), h=img.getHeight();
		if(prpty.equals("width") || prpty.equals("height")){
			int n = TUtil.propertyInteger(prpty, value);
			if(n<PatButton.MIN_SIDE || n>PatButton.MAX_SIDE || (n&(n-1))!=0)
				throw new xTalkException("{0} requires 8, 16, 32 or 64 (ppat format)", prpty);
			int nw = prpty.equals("width") ? n : w, nh = prpty.equals("height") ? n : h;
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw,nh,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for(int y=0;y<nh;y++) for(int x=0;x<nw;x++) out.setRGB(x,y,img.getRGB(x%w,y%h));
			op.setImage(out);
			return;
		}
		if(prpty.equals("imagedata")){
			if(value.length()!=w*h*4) throw new xTalkException("Incorrect imageData length ({0} bytes required)", (w*h*4));
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			int p=0;
			for(int y=0;y<h;y++) for(int x=0;x<w;x++){
				int r=value.charAt(p+1)&0xFF, g=value.charAt(p+2)&0xFF, b=value.charAt(p+3)&0xFF;
				p+=4;
				out.setRGB(x,y,PatternEditor.normalize(0xFF000000|(r<<16)|(g<<8)|b));
			}
			op.setImage(out);
			return;
		}
		if(prpty.equals("text")){
			String[] rows = value.split("\r?\n|\r");
			int nh = PatButton.ppatSide(rows.length), nw = 0;
			for(String r : rows) nw = Math.max(nw, r.trim().length());
			nw = PatButton.ppatSide(nw);
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw,nh,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for(int y=0;y<nh;y++){
				String row = y<rows.length ? rows[y].trim() : "";
				for(int x=0;x<nw;x++){
					char ch = x<row.length() ? row.charAt(x) : '0';
					boolean on = ch=='1' || ch=='#' || ch=='*' || ch=='X' || ch=='x';
					out.setRGB(x,y,on ? PatternEditor.INK_FORE : PatternEditor.INK_BACK);
				}
			}
			op.setImage(out);
			return;
		}
		throw new xTalkException("pattern has no property {0}", prpty);
	}

	// from OObject (instance)
	static java.awt.image.BufferedImage image(OPattern self) {
		PatButton b=self.button();
		return (b==null || self.index<0 || self.index>=b.patterns.length) ? null : b.patterns[self.index];
	}

	// from OObject (instance)
	static void setImage(OPattern self, java.awt.image.BufferedImage image) {
		PatButton b=self.button();
		if(b!=null) b.store(self.index, image);
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void selectNewButton(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException {
        			if(Host.get().authToolActive() && AuthTool.tool.getClass()==ButtonTool.class){
        				ButtonGUI.gui.tgtOBtn = obtn;
        				ButtonGUI.gui.target = obtn.getComponent();
        			}
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void selectNewField(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException {
        			if(Host.get().authToolActive() && AuthTool.tool.getClass()==FieldTool.class){
        				FieldGUI.gui.tgtOFld = ofld;
        				FieldGUI.gui.target = ofld.getComponent();
        			}
	}
}
