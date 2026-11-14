import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

/** Desktop code moved out of the core's OShape unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class ShapesUI {
	private ShapesUI(){}

	// from OShape (instance)
	static void convertTextToBezier(OShape self) {
		if(!self.kind.equals("text")) return;
		self.pathData=ShapePath.serialize(ShapePath.cubic(self.geometry()));self.kind="bezier";self.changed();self.repaintView();
	}

	// from OShape (instance)
	static void fitText(OShape self) {if(!self.kind.equals("text"))return;Rectangle2D b=self.naturalTextBounds();if(!b.isEmpty())self.setSize(Math.max(1,(int)Math.ceil(b.getWidth())),Math.max(1,(int)Math.ceil(b.getHeight())));}

	// from OShape
	static String num(double v){return ShapeFilterPanel.num(v);}

	// from OShape (instance)
	/** Lines of OShape.setSize (the core calls this in their place). */
	static void scalePath(OShape self, int w,int h) {
		if(!self.pathData.isEmpty()&&self.width>0&&self.height>0&&(w!=self.width||h!=self.height))
			self.pathData=ShapePath.serialize(AffineTransform.getScaleInstance((double)w/self.width,(double)h/self.height).createTransformedShape(ShapePath.parse(self.pathData)));
	}

	// from OShape (instance)
	/** Lines of OShape.setKind (the core calls this in their place). */
	static void outlineAsPath(OShape self, String value, String k) throws xTalkException {
		if(self.pathData.isEmpty()||self.kind.equals("text"))self.pathData=ShapePath.serialize(k.equals("bezier")?ShapePath.cubic(self.geometry()):self.geometry());
	}
}
