import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SVG path の編集に使う M/L/Q/C/Z と基本 SVG コマンドの読取・書出し。 */
final class ShapePath {
	private static final Pattern TOKEN=Pattern.compile("[AaCcHhLlMmQqSsTtVvZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
	private ShapePath(){}
	static Path2D.Double parse(String data){
		Path2D.Double path=new Path2D.Double();
		if(data==null) return path;
		ArrayList<String> tokens=new ArrayList<>();
		Matcher m=TOKEN.matcher(data); while(m.find()) tokens.add(m.group());
		int i=0; char cmd=' '; double x=0,y=0,sx=0,sy=0,cx=0,cy=0,qx=0,qy=0;
		char previous=' ';
		while(i<tokens.size()){
			String t=tokens.get(i);
			if(Character.isLetter(t.charAt(0))){ cmd=t.charAt(0); i++; if(cmd=='Z'||cmd=='z'){path.closePath();x=sx;y=sy;previous=cmd;continue;} }
			if(cmd==' ') break;
			boolean relative=Character.isLowerCase(cmd);
			char op=Character.toUpperCase(cmd);
			int need=op=='H'||op=='V'?1:op=='C'?6:op=='S'||op=='Q'?4:op=='A'?7:2;
			if(i+need>tokens.size() || Character.isLetter(tokens.get(i).charAt(0))) break;
			try{
				double[] v=new double[need]; for(int k=0;k<need;k++) v[k]=Double.parseDouble(tokens.get(i++));
				switch(op){
				case 'M': x=(relative?x:0)+v[0];y=(relative?y:0)+v[1];path.moveTo(x,y);sx=x;sy=y;cmd=relative?'l':'L';break;
				case 'L': x=(relative?x:0)+v[0];y=(relative?y:0)+v[1];path.lineTo(x,y);break;
				case 'H': x=(relative?x:0)+v[0];path.lineTo(x,y);break;
				case 'V': y=(relative?y:0)+v[0];path.lineTo(x,y);break;
				case 'C': {
					double ax=(relative?x:0)+v[0],ay=(relative?y:0)+v[1];
					cx=(relative?x:0)+v[2];cy=(relative?y:0)+v[3];
					x=(relative?x:0)+v[4];y=(relative?y:0)+v[5];
					path.curveTo(ax,ay,cx,cy,x,y);break;
				}
				case 'S': {
					double ax=(previous=='C'||previous=='c'||previous=='S'||previous=='s')?2*x-cx:x;
					double ay=(previous=='C'||previous=='c'||previous=='S'||previous=='s')?2*y-cy:y;
					cx=(relative?x:0)+v[0];cy=(relative?y:0)+v[1];
					x=(relative?x:0)+v[2];y=(relative?y:0)+v[3];path.curveTo(ax,ay,cx,cy,x,y);break;
				}
				case 'Q': qx=(relative?x:0)+v[0];qy=(relative?y:0)+v[1];x=(relative?x:0)+v[2];y=(relative?y:0)+v[3];path.quadTo(qx,qy,x,y);break;
				case 'T': qx=(previous=='Q'||previous=='q'||previous=='T'||previous=='t')?2*x-qx:x;
					qy=(previous=='Q'||previous=='q'||previous=='T'||previous=='t')?2*y-qy:y;
					x=(relative?x:0)+v[0];y=(relative?y:0)+v[1];path.quadTo(qx,qy,x,y);break;
				case 'A': {
					double nx=(relative?x:0)+v[5],ny=(relative?y:0)+v[6];
					arc(path,x,y,nx,ny,v[0],v[1],v[2],v[3]!=0,v[4]!=0);x=nx;y=ny;break;
				}
				}
				previous=op;
			}catch(NumberFormatException e){break;}
		}
		return path;
	}
	private static void arc(Path2D.Double p,double x1,double y1,double x2,double y2,double rx,double ry,double rotation,boolean large,boolean sweep){
		// SVG arc endpoint parameterization; convert segments of at most 90 degrees to cubics.
		rx=Math.abs(rx);ry=Math.abs(ry);if(rx==0||ry==0||x1==x2&&y1==y2){p.lineTo(x2,y2);return;}
		double phi=Math.toRadians(rotation),co=Math.cos(phi),si=Math.sin(phi);
		double dx=(x1-x2)/2,dy=(y1-y2)/2,xp=co*dx+si*dy,yp=-si*dx+co*dy;
		double lambda=xp*xp/(rx*rx)+yp*yp/(ry*ry);if(lambda>1){double s=Math.sqrt(lambda);rx*=s;ry*=s;}
		double numerator=Math.max(0,(rx*rx*ry*ry-rx*rx*yp*yp-ry*ry*xp*xp)/(rx*rx*yp*yp+ry*ry*xp*xp));
		double sign=large==sweep?-1:1,coef=sign*Math.sqrt(numerator);
		double cxp=coef*rx*yp/ry,cyp=-coef*ry*xp/rx;
		double cx=co*cxp-si*cyp+(x1+x2)/2,cy=si*cxp+co*cyp+(y1+y2)/2;
		double start=Math.atan2((yp-cyp)/ry,(xp-cxp)/rx);
		double end=Math.atan2((-yp-cyp)/ry,(-xp-cxp)/rx),delta=end-start;
		if(sweep&&delta<0)delta+=Math.PI*2;if(!sweep&&delta>0)delta-=Math.PI*2;
		int count=(int)Math.ceil(Math.abs(delta)/(Math.PI/2));double step=delta/count;
		for(int j=0;j<count;j++){
			double a=start+j*step,b=a+step,k=4.0/3*Math.tan(step/4);
			double[] c1=arcPoint(cx,cy,rx,ry,co,si,Math.cos(a)-k*Math.sin(a),Math.sin(a)+k*Math.cos(a));
			double[] c2=arcPoint(cx,cy,rx,ry,co,si,Math.cos(b)+k*Math.sin(b),Math.sin(b)-k*Math.cos(b));
			double[] dest=arcPoint(cx,cy,rx,ry,co,si,Math.cos(b),Math.sin(b));
			p.curveTo(c1[0],c1[1],c2[0],c2[1],dest[0],dest[1]);
		}
	}
	private static double[] arcPoint(double cx,double cy,double rx,double ry,double co,double si,double x,double y){return new double[]{cx+co*rx*x-si*ry*y,cy+si*rx*x+co*ry*y};}
	static String serialize(Shape shape){
		StringBuilder s=new StringBuilder();double[] a=new double[6];
		for(PathIterator it=shape.getPathIterator(null);!it.isDone();it.next()){
			int t=it.currentSegment(a);char c=t==0?'M':t==1?'L':t==2?'Q':t==3?'C':'Z';s.append(c);
			int n=t==0||t==1?2:t==2?4:t==3?6:0;
			for(int k=0;k<n;k++) s.append(k==0?' ':',').append(trim(a[k]));s.append(' ');
		}return s.toString().trim();
	}
	static String normalized(Shape shape,Rectangle2D bounds){return serialize(AffineTransform.getTranslateInstance(-bounds.getX(),-bounds.getY()).createTransformedShape(shape));}
	static Shape cubic(Shape shape){
		Path2D.Double p=new Path2D.Double();double[] a=new double[6];double x=0,y=0,sx=0,sy=0;
		for(PathIterator it=shape.getPathIterator(null);!it.isDone();it.next())switch(it.currentSegment(a)){
			case PathIterator.SEG_MOVETO:p.moveTo(a[0],a[1]);x=sx=a[0];y=sy=a[1];break;
			case PathIterator.SEG_LINETO:p.lineTo(a[0],a[1]);x=a[0];y=a[1];break;
			case PathIterator.SEG_QUADTO:{double c1x=x+2*(a[0]-x)/3,c1y=y+2*(a[1]-y)/3,c2x=a[2]+2*(a[0]-a[2])/3,c2y=a[3]+2*(a[1]-a[3])/3;p.curveTo(c1x,c1y,c2x,c2y,a[2],a[3]);x=a[2];y=a[3];break;}
			case PathIterator.SEG_CUBICTO:p.curveTo(a[0],a[1],a[2],a[3],a[4],a[5]);x=a[4];y=a[5];break;
			case PathIterator.SEG_CLOSE:p.closePath();x=sx;y=sy;break;
		}
		return p;
	}
	static String trim(double n){return Double.toString(Math.rint(n*1000)/1000);}
}
