import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple OpenGL 1.2.1 for Mac OS 9 (the "OpenGLLibrary", "OpenGLUtility" and "OpenGLMemory" shared libraries) for PowerPC
 * programs: AGL (pixel formats, contexts, drawables, swapping), the OpenGL 1.2 entry points with the ARB multitexture,
 * compiled vertex array, BGRA and packed pixel extensions, and the GLU 1.2 calls programs of the time used. The pipeline
 * itself is {@link GlRenderer}; this class decodes arguments, guest arrays and pixel data and shows frames: a context's
 * colour buffer is copied into a 32-bit GWorld and CopyBits'd into its drawable (the window port) at aglSwapBuffers, or
 * at glFlush / glFinish for a single-buffered pixel format.
 * <p>
 * The renderer answers as ATI Rage 128 hardware (accelerated, renderer ID 0x00021000), as {@link PpcRave} does, so programs
 * that insist on acceleration take their normal path. -Dhzppc.opengl=extension leaves the libraries to be found as
 * files instead (a real OpenGL installation); -Dhzppc.gltrace=true logs every call.
 */
final class PpcOpenGL {
	static final String LIBRARY="OpenGLLibrary",UTILITY="OpenGLUtility",MEMORY="OpenGLMemory";
	static final boolean NATIVE=!"extension".equals(System.getProperty("hzppc.opengl","native"));
	/** -Dhzppc.gltrace=true logs every call (names: only those, comma separated). */
	private static final String TRACE_NAMES=System.getProperty("hzppc.gltrace","");
	static final boolean TRACE=!TRACE_NAMES.isEmpty()&&!TRACE_NAMES.equals("false");
	private static boolean traced(String name){return TRACE_NAMES.equals("true")||(","+TRACE_NAMES+",").contains(","+name+",");}
	static boolean isLibrary(String name){return NATIVE&&(LIBRARY.equals(name)||UTILITY.equals(name)||MEMORY.equals(name));}

	/** AGL_RENDERER_ATI_RAGE_128_ID. */
	static final int RENDERER_ID=0x00021000;
	static final String VENDOR="ATI Technologies Inc.",RENDERER="ATI Rage 128 OpenGL Engine",VERSION="1.2.1 ATI-1.2",
		EXTENSIONS="GL_ARB_multitexture GL_ARB_texture_env_add GL_EXT_texture_env_add GL_EXT_bgra GL_EXT_abgr GL_EXT_packed_pixels GL_APPLE_packed_pixel "
			+"GL_EXT_compiled_vertex_array GL_SGIS_texture_edge_clamp GL_EXT_blend_color GL_EXT_blend_minmax GL_EXT_blend_subtract GL_SGIS_generate_mipmap "
			+"GL_EXT_rescale_normal GL_EXT_separate_specular_color GL_EXT_stencil_wrap GL_ARB_transpose_matrix";

	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcOpenGL(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	private int a(int i) throws M68kCpuException {return rt.arg(i);}
	private float f(int i){return (float)rt.farg(i);}
	private double d(int i){return rt.farg(i);}
	private void ret(int v){rt.ret(v);}
	private int tb(String name,int... args) throws M68kException {return rt.toolbox(name,args);}
	private void free(int p){try{if(p!=0&&memory.isAllocated(p))memory.free(p);}catch(RuntimeException ignored){}}
	private float rf(int p) throws M68kCpuException {return Float.intBitsToFloat(memory.readLong(p));}
	private double rd(int p) throws M68kCpuException {return Double.longBitsToDouble(((long)memory.readLong(p))<<32|(memory.readLong(p+4)&0xffffffffL));}
	private void wf(int p,float v) throws M68kCpuException {memory.writeLong(p,Float.floatToRawIntBits(v));}
	private void wd(int p,double v) throws M68kCpuException {long b=Double.doubleToRawLongBits(v);memory.writeLong(p,(int)(b>>>32));memory.writeLong(p+4,(int)b);}
	private float[] floats(int p,int n) throws M68kCpuException {float[] r=new float[n];for(int i=0;i<n;i++)r[i]=rf(p+4*i);return r;}
	private double[] doubles(int p,int n) throws M68kCpuException {double[] r=new double[n];for(int i=0;i<n;i++)r[i]=rd(p+8*i);return r;}
	private float[] intsAsFloats(int p,int n) throws M68kCpuException {float[] r=new float[n];for(int i=0;i<n;i++)r[i]=memory.readLong(p+4*i);return r;}

	// ================================================================== AGL objects

	static final int AGL_NONE=0,AGL_ALL_RENDERERS=1,AGL_BUFFER_SIZE=2,AGL_LEVEL=3,AGL_RGBA=4,AGL_DOUBLEBUFFER=5,AGL_STEREO=6,AGL_AUX_BUFFERS=7,
		AGL_RED_SIZE=8,AGL_GREEN_SIZE=9,AGL_BLUE_SIZE=10,AGL_ALPHA_SIZE=11,AGL_DEPTH_SIZE=12,AGL_STENCIL_SIZE=13,AGL_ACCUM_RED_SIZE=14,AGL_ACCUM_ALPHA_SIZE=17,
		AGL_PIXEL_SIZE=50,AGL_MINIMUM_POLICY=51,AGL_MAXIMUM_POLICY=52,AGL_OFFSCREEN=53,AGL_FULLSCREEN=54,AGL_SAMPLE_BUFFERS_ARB=55,AGL_SAMPLES_ARB=56,
		AGL_AUX_DEPTH_STENCIL=57,AGL_RENDERER_ID=70,AGL_SINGLE_RENDERER=71,AGL_NO_RECOVERY=72,AGL_ACCELERATED=73,AGL_CLOSEST_POLICY=74,AGL_ROBUST=75,
		AGL_BACKING_STORE=76,AGL_MP_SAFE=78,AGL_WINDOW=80,AGL_MULTISCREEN=81,AGL_VIRTUAL_SCREEN=82,AGL_COMPLIANT=83;
	static final int AGL_SWAP_RECT=200,AGL_BUFFER_RECT=202,AGL_SWAP_LIMIT=203,AGL_COLORMAP_TRACKING=210,AGL_RASTERIZATION=220,AGL_SWAP_INTERVAL=222,
		AGL_STATE_VALIDATION=230,AGL_CLIP_REGION=254;
	static final int AGL_NO_ERROR=0,AGL_BAD_ATTRIBUTE=10000,AGL_BAD_PROPERTY=10001,AGL_BAD_PIXELFMT=10002,AGL_BAD_RENDINFO=10003,AGL_BAD_CONTEXT=10004,
		AGL_BAD_DRAWABLE=10005,AGL_BAD_GDEV=10006,AGL_BAD_STATE=10007,AGL_BAD_VALUE=10008,AGL_BAD_MATCH=10009,AGL_BAD_ENUM=10010,AGL_BAD_OFFSCREEN=10011,
		AGL_BAD_FULLSCREEN=10012,AGL_BAD_WINDOW=10013,AGL_BAD_POINTER=10014,AGL_BAD_MODULE=10015,AGL_BAD_ALLOC=10016;

	private static final class PixelFormat {
		final int ref;boolean doubleBuffer,fullScreen,offScreen;int depthSize,stencilSize,alphaSize,accumSize,auxBuffers,pixelSize=32;
		PixelFormat(int ref){this.ref=ref;}
	}
	private final class Context {
		final int ref;final PixelFormat pf;final GlRenderer g=new GlRenderer();
		int drawable;boolean fullScreen,viewportSet;int fsWidth,fsHeight;
		final int[] bufferRect=new int[4],swapRect=new int[4];boolean bufferRectOn,swapRectOn;int swapInterval;
		int gworld,pixMap,base,rowBytes,bw,bh;
		Context(int ref,PixelFormat pf){this.ref=ref;this.pf=pf;}
		boolean doubleBuffered(){return pf==null||pf.doubleBuffer;}
	}
	private final Map<Integer,PixelFormat> pixelFormats=new HashMap<Integer,PixelFormat>();
	private final Map<Integer,Context> contexts=new HashMap<Integer,Context>();
	private Context current;
	private int aglError;
	private int rendererInfo;

	/** The rectangle of a port's content (portRect: top, left, bottom, right). */
	private int[] portRect(int port) throws M68kCpuException {
		return new int[]{(short)memory.readWord(port+16),(short)memory.readWord(port+18),(short)memory.readWord(port+20),(short)memory.readWord(port+22)};
	}
	/** Sizes a context's buffers after its drawable (the port's content, or the AGL_BUFFER_RECT part of it). */
	private void sizeToDrawable(Context x) throws M68kException {
		int w,h;
		if(x.fullScreen){w=x.fsWidth;h=x.fsHeight;}
		else if(x.drawable!=0){
			if(x.bufferRectOn){w=x.bufferRect[2];h=x.bufferRect[3];}
			else{int[] r=portRect(x.drawable);w=r[3]-r[1];h=r[2]-r[0];}
		}else return;
		w=Math.max(1,Math.min(4096,w));h=Math.max(1,Math.min(4096,h));
		x.g.resize(w,h);
		if(!x.viewportSet){x.g.initialViewport(w,h);x.viewportSet=true;}
		if(TRACE)System.err.printf("[gl] context %08X drawable %08X %dx%d%n",x.ref,x.drawable,w,h);
	}
	private boolean ensureGWorld(Context x,int w,int h) throws M68kException {
		if(x.gworld!=0&&x.bw==w&&x.bh==h)return true;
		if(x.gworld!=0){tb("DisposeGWorld",x.gworld);x.gworld=0;}
		int out=rt.allocate(12,4);
		try{
			int rect=out+4;memory.writeWord(rect,0);memory.writeWord(rect+2,0);memory.writeWord(rect+4,h);memory.writeWord(rect+6,w);
			int err=tb("NewGWorld",out,32,rect,0,0,0);
			if(err!=0)err=tb("NewGWorld",out,32,rect,0,0,4); // useTempMem
			if(err!=0){System.err.println("[gl] no memory for a "+w+"x"+h+" buffer (NewGWorld "+err+")");return false;}
			x.gworld=memory.readLong(out);
		}finally{free(out);}
		x.pixMap=tb("GetGWorldPixMap",x.gworld);tb("LockPixels",x.pixMap);
		x.base=tb("GetPixBaseAddr",x.pixMap);x.rowBytes=memory.readWord(memory.readLong(x.pixMap)+4)&0x3fff;x.bw=w;x.bh=h;
		return true;
	}
	private byte[] rowBuffer=new byte[0];
	/** Debug aid, -Dhzppc.gldump=DIR[,every]: writes every n-th presented frame (default 30) as DIR/gl-NNNNN.png, with frame times. */
	private static final String DUMP=System.getProperty("hzppc.gldump");
	private int frames;private long lastFrame;
	private void dump(GlRenderer g){
		String[] p=DUMP.split(",");int every=p.length>1?Integer.parseInt(p[1]):30;
		frames++;long now=System.nanoTime();
		if(frames%every==0){
			System.err.printf("[gl] frame %d: %.1f ms since the previous one%n",frames,(now-lastFrame)/1e6);
			java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(g.width,g.height,java.awt.image.BufferedImage.TYPE_INT_RGB);
			img.setRGB(0,0,g.width,g.height,g.color,0,g.width);
			try{java.io.File d=new java.io.File(p[0]);d.mkdirs();javax.imageio.ImageIO.write(img,"png",new java.io.File(d,String.format("gl-%05d.png",frames)));}catch(java.io.IOException e){System.err.println("[gl] "+e);}
		}
		lastFrame=now;
	}
	/** Shows a context's colour buffer in its drawable. */
	private void present(Context x) throws M68kException {
		if(DUMP!=null)dump(x.g);
		if(x.drawable==0&&!x.fullScreen)return;
		GlRenderer g=x.g;int w=g.width,h=g.height;
		if(!ensureGWorld(x,w,h))return;
		int rb=x.rowBytes;byte[] b=rowBuffer=rowBuffer.length>=rb*h?rowBuffer:new byte[rb*h];
		int[] c=g.color;
		for(int y=0;y<h;y++){int o=y*rb,i=y*w;for(int xx=0;xx<w;xx++,o+=4){int v=c[i+xx];b[o]=(byte)0xff;b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}}
		memory.load(x.base,b,0,rb*h);
		int tmp=rt.allocate(64,4);
		try{
			int src=tmp+36,dst=tmp+44;
			memory.writeWord(src,0);memory.writeWord(src+2,0);memory.writeWord(src+4,h);memory.writeWord(src+6,w);
			if(x.fullScreen){
				int gd=tb("GetMainDevice");int pmh=gd==0?0:memory.readLong(memory.readLong(gd)+22);
				if(pmh==0||memory.readLong(pmh)==0)return;
				memory.writeWord(dst,0);memory.writeWord(dst+2,0);memory.writeWord(dst+4,h);memory.writeWord(dst+6,w);
				tb("GetGWorld",tmp,tmp+4);tb("SetGWorld",x.gworld,0);
				tb("CopyBits",memory.readLong(x.pixMap),memory.readLong(pmh),src,dst,0,0);
				tb("SetGWorld",memory.readLong(tmp),memory.readLong(tmp+4));
				return;
			}
			int port=x.drawable;int[] pr=portRect(port);
			int left=pr[1],top=pr[0];
			if(x.bufferRectOn){left=pr[1]+x.bufferRect[0];top=pr[2]-x.bufferRect[1]-h;}
			memory.writeWord(dst,top);memory.writeWord(dst+2,left);memory.writeWord(dst+4,top+h);memory.writeWord(dst+6,left+w);
			tb("GetPort",tmp);int savedPort=memory.readLong(tmp);
			tb("SetPort",port);
			tb("GetForeColor",tmp+4);tb("GetBackColor",tmp+10);
			int black=tmp+16,white=tmp+22;
			memory.writeWord(black,0);memory.writeWord(black+2,0);memory.writeWord(black+4,0);
			memory.writeWord(white,0xffff);memory.writeWord(white+2,0xffff);memory.writeWord(white+4,0xffff);
			tb("RGBForeColor",black);tb("RGBBackColor",white);
			// the hardware draws over the window's visible area whatever the port's clip region
			int clip=tb("NewRgn");tb("GetClip",clip);
			int whole=tmp+28;for(int i=0;i<4;i++)memory.writeWord(whole+2*i,pr[i]);
			tb("ClipRect",whole);
			tb("CopyBits",memory.readLong(x.pixMap),port+2,src,dst,0,0);
			tb("SetClip",clip);tb("DisposeRgn",clip);
			tb("RGBForeColor",tmp+4);tb("RGBBackColor",tmp+10);
			tb("SetPort",savedPort);
		}finally{free(tmp);}
	}

	// ================================================================== aglMacro.h: the context record

	/** The functions of an AGLContext's GLIFunctionDispatch table, in the order of gliDispatch.h: the OpenGL 1.1 calls
	 *  alphabetically (the first 336, "accum" to "viewport", as in the Mac OS 9 SDK), then the later ones in the order of the
	 *  Mac OS X header (that tail of the OS 9 table is not verified against its SDK). */
	static final String[] DISPATCH=(
		"accum alpha_func are_textures_resident array_element begin bind_texture bitmap blend_func call_list call_lists clear clear_accum clear_color "
		+"clear_depth clear_index clear_stencil clip_plane color3b color3bv color3d color3dv color3f color3fv color3i color3iv color3s color3sv color3ub "
		+"color3ubv color3ui color3uiv color3us color3usv color4b color4bv color4d color4dv color4f color4fv color4i color4iv color4s color4sv color4ub "
		+"color4ubv color4ui color4uiv color4us color4usv color_mask color_material color_pointer copy_pixels copy_tex_image1D copy_tex_image2D "
		+"copy_tex_sub_image1D copy_tex_sub_image2D cull_face delete_lists delete_textures depth_func depth_mask depth_range disable disable_client_state "
		+"draw_arrays draw_buffer draw_elements draw_pixels edge_flag edge_flag_pointer edge_flagv enable enable_client_state end end_list eval_coord1d "
		+"eval_coord1dv eval_coord1f eval_coord1fv eval_coord2d eval_coord2dv eval_coord2f eval_coord2fv eval_mesh1 eval_mesh2 eval_point1 eval_point2 "
		+"feedback_buffer finish flush fogf fogfv fogi fogiv front_face frustum gen_lists gen_textures get_booleanv get_clip_plane get_doublev get_error "
		+"get_floatv get_integerv get_lightfv get_lightiv get_mapdv get_mapfv get_mapiv get_materialfv get_materialiv get_pixel_mapfv get_pixel_mapuiv "
		+"get_pixel_mapusv get_pointerv get_polygon_stipple get_string get_tex_envfv get_tex_enviv get_tex_gendv get_tex_genfv get_tex_geniv get_tex_image "
		+"get_tex_level_parameterfv get_tex_level_parameteriv get_tex_parameterfv get_tex_parameteriv hint index_mask index_pointer indexd indexdv indexf "
		+"indexfv indexi indexiv indexs indexsv indexub indexubv init_names interleaved_arrays is_enabled is_list is_texture light_modelf light_modelfv "
		+"light_modeli light_modeliv lightf lightfv lighti lightiv line_stipple line_width list_base load_identity load_matrixd load_matrixf load_name logic_op "
		+"map1d map1f map2d map2f map_grid1d map_grid1f map_grid2d map_grid2f materialf materialfv materiali materialiv matrix_mode mult_matrixd mult_matrixf "
		+"new_list normal3b normal3bv normal3d normal3dv normal3f normal3fv normal3i normal3iv normal3s normal3sv normal_pointer ortho pass_through pixel_mapfv "
		+"pixel_mapuiv pixel_mapusv pixel_storef pixel_storei pixel_transferf pixel_transferi pixel_zoom point_size polygon_mode polygon_offset polygon_stipple "
		+"pop_attrib pop_client_attrib pop_matrix pop_name prioritize_textures push_attrib push_client_attrib push_matrix push_name raster_pos2d raster_pos2dv "
		+"raster_pos2f raster_pos2fv raster_pos2i raster_pos2iv raster_pos2s raster_pos2sv raster_pos3d raster_pos3dv raster_pos3f raster_pos3fv raster_pos3i "
		+"raster_pos3iv raster_pos3s raster_pos3sv raster_pos4d raster_pos4dv raster_pos4f raster_pos4fv raster_pos4i raster_pos4iv raster_pos4s raster_pos4sv "
		+"read_buffer read_pixels rectd rectdv rectf rectfv recti rectiv rects rectsv render_mode rotated rotatef scaled scalef scissor select_buffer "
		+"shade_model stencil_func stencil_mask stencil_op tex_coord1d tex_coord1dv tex_coord1f tex_coord1fv tex_coord1i tex_coord1iv tex_coord1s tex_coord1sv "
		+"tex_coord2d tex_coord2dv tex_coord2f tex_coord2fv tex_coord2i tex_coord2iv tex_coord2s tex_coord2sv tex_coord3d tex_coord3dv tex_coord3f tex_coord3fv "
		+"tex_coord3i tex_coord3iv tex_coord3s tex_coord3sv tex_coord4d tex_coord4dv tex_coord4f tex_coord4fv tex_coord4i tex_coord4iv tex_coord4s tex_coord4sv "
		+"tex_coord_pointer tex_envf tex_envfv tex_envi tex_enviv tex_gend tex_gendv tex_genf tex_genfv tex_geni tex_geniv tex_image1D tex_image2D "
		+"tex_parameterf tex_parameterfv tex_parameteri tex_parameteriv tex_sub_image1D tex_sub_image2D translated translatef vertex2d vertex2dv vertex2f "
		+"vertex2fv vertex2i vertex2iv vertex2s vertex2sv vertex3d vertex3dv vertex3f vertex3fv vertex3i vertex3iv vertex3s vertex3sv vertex4d vertex4dv "
		+"vertex4f vertex4fv vertex4i vertex4iv vertex4s vertex4sv vertex_pointer viewport blend_func_separate blend_color blend_equation lock_arrays_EXT "
		+"unlock_arrays_EXT client_active_texture active_texture multi_tex_coord1d multi_tex_coord1dv multi_tex_coord1f multi_tex_coord1fv multi_tex_coord1i "
		+"multi_tex_coord1iv multi_tex_coord1s multi_tex_coord1sv multi_tex_coord2d multi_tex_coord2dv multi_tex_coord2f multi_tex_coord2fv multi_tex_coord2i "
		+"multi_tex_coord2iv multi_tex_coord2s multi_tex_coord2sv multi_tex_coord3d multi_tex_coord3dv multi_tex_coord3f multi_tex_coord3fv multi_tex_coord3i "
		+"multi_tex_coord3iv multi_tex_coord3s multi_tex_coord3sv multi_tex_coord4d multi_tex_coord4dv multi_tex_coord4f multi_tex_coord4fv multi_tex_coord4i "
		+"multi_tex_coord4iv multi_tex_coord4s multi_tex_coord4sv load_transpose_matrixd load_transpose_matrixf mult_transpose_matrixd mult_transpose_matrixf "
		+"compressed_tex_image3D compressed_tex_image2D compressed_tex_image1D compressed_tex_sub_image3D compressed_tex_sub_image2D compressed_tex_sub_image1D "
		+"get_compressed_tex_image secondary_color3b secondary_color3bv secondary_color3d secondary_color3dv secondary_color3f secondary_color3fv "
		+"secondary_color3i secondary_color3iv secondary_color3s secondary_color3sv secondary_color3ub secondary_color3ubv secondary_color3ui "
		+"secondary_color3uiv secondary_color3us secondary_color3usv secondary_color_pointer vertex_array_range_EXT flush_vertex_array_range_EXT "
		+"draw_range_elements color_table color_table_parameterfv color_table_parameteriv copy_color_table get_color_table get_color_table_parameterfv "
		+"get_color_table_parameteriv color_sub_table copy_color_sub_table convolution_filter1D convolution_filter2D convolution_parameterf "
		+"convolution_parameterfv convolution_parameteri convolution_parameteriv copy_convolution_filter1D copy_convolution_filter2D get_convolution_filter "
		+"get_convolution_parameterfv get_convolution_parameteriv get_separable_filter separable_filter2D get_histogram get_histogram_parameterfv "
		+"get_histogram_parameteriv get_minmax get_minmax_parameterfv get_minmax_parameteriv histogram minmax reset_histogram reset_minmax tex_image3D "
		+"tex_sub_image3D copy_tex_sub_image3D get_uniform_indices get_active_uniformsiv get_active_uniform_name get_uniform_block_index "
		+"get_active_uniform_blockiv get_active_uniform_block_name uniform_block_binding get_combiner_input_parameterfv_NV get_combiner_input_parameteriv_NV "
		+"get_combiner_output_parameterfv_NV get_combiner_output_parameteriv_NV get_final_combiner_input_parameterfv_NV get_final_combiner_input_parameteriv_NV "
		+"combiner_stage_parameterfv_NV get_combiner_stage_parameterfv_NV texture_range_APPLE get_tex_parameter_pointerv_APPLE blend_equation_separate_EXT "
		+"sample_coverage sample_pass pn_trianglesi_ATI pn_trianglesf_ATI gen_fences_APPLE delete_fences_APPLE set_fence_APPLE is_fence_APPLE test_fence_APPLE "
		+"finish_fence_APPLE test_object_APPLE finish_object_APPLE bind_program_ARB delete_programs_ARB gen_programs_ARB is_program_ARB vertex_attrib1s_ARB "
		+"vertex_attrib1f_ARB vertex_attrib1d_ARB vertex_attrib2s_ARB vertex_attrib2f_ARB vertex_attrib2d_ARB vertex_attrib3s_ARB vertex_attrib3f_ARB "
		+"vertex_attrib3d_ARB vertex_attrib4s_ARB vertex_attrib4f_ARB vertex_attrib4d_ARB vertex_attrib4Nub_ARB vertex_attrib1sv_ARB vertex_attrib1fv_ARB "
		+"vertex_attrib1dv_ARB vertex_attrib2sv_ARB vertex_attrib2fv_ARB vertex_attrib2dv_ARB vertex_attrib3sv_ARB vertex_attrib3fv_ARB vertex_attrib3dv_ARB "
		+"vertex_attrib4bv_ARB vertex_attrib4sv_ARB vertex_attrib4iv_ARB vertex_attrib4ubv_ARB vertex_attrib4usv_ARB vertex_attrib4uiv_ARB vertex_attrib4fv_ARB "
		+"vertex_attrib4dv_ARB vertex_attrib4Nbv_ARB vertex_attrib4Nsv_ARB vertex_attrib4Niv_ARB vertex_attrib4Nubv_ARB vertex_attrib4Nusv_ARB "
		+"vertex_attrib4Nuiv_ARB vertex_attrib_pointer_ARB enable_vertex_attrib_array_ARB disable_vertex_attrib_array_ARB get_vertex_attribdv_ARB "
		+"get_vertex_attribfv_ARB get_vertex_attribiv_ARB get_vertex_attrib_pointerv_ARB program_env_parameter4d_ARB program_env_parameter4dv_ARB "
		+"program_env_parameter4f_ARB program_env_parameter4fv_ARB program_local_parameter4d_ARB program_local_parameter4dv_ARB program_local_parameter4f_ARB "
		+"program_local_parameter4fv_ARB get_program_env_parameterdv_ARB get_program_env_parameterfv_ARB get_program_local_parameterdv_ARB "
		+"get_program_local_parameterfv_ARB program_string_ARB get_program_string_ARB get_programiv_ARB enable_vertex_attrib_ARB disable_vertex_attrib_ARB "
		+"is_vertex_attrib_enabled_ARB map_vertex_attrib1d_ARB map_vertex_attrib1f_ARB map_vertex_attrib2d_ARB map_vertex_attrib2f_ARB point_parameterf "
		+"point_parameterfv point_parameteri point_parameteriv fog_coordf fog_coordfv fog_coordd fog_coorddv fog_coord_pointer vertex_array_parameteri_EXT "
		+"bind_vertex_array_EXT delete_vertex_arrays_EXT gen_vertex_arrays_EXT is_vertex_array_EXT element_pointer_APPLE draw_element_array_APPLE "
		+"draw_range_element_array_APPLE weightbv_ARB weightsv_ARB weightiv_ARB weightfv_ARB weightdv_ARB weightubv_ARB weightusv_ARB weightuiv_ARB "
		+"weight_pointer_ARB vertex_blend_ARB multi_draw_arrays multi_draw_elements window_pos2d window_pos2dv window_pos2f window_pos2fv window_pos2i "
		+"window_pos2iv window_pos2s window_pos2sv window_pos3d window_pos3dv window_pos3f window_pos3fv window_pos3i window_pos3iv window_pos3s window_pos3sv "
		+"active_stencil_face_EXT stencil_op_separate_ATI stencil_func_separate_ATI flush_render_APPLE finish_render_APPLE swap_APPLE delete_object_ARB "
		+"get_handle_ARB detach_object_ARB create_shader_object_ARB shader_source_ARB compile_shader_ARB create_program_object_ARB attach_object_ARB "
		+"link_program_ARB use_program_object_ARB validate_program_ARB uniform1f_ARB uniform2f_ARB uniform3f_ARB uniform4f_ARB uniform1i_ARB uniform2i_ARB "
		+"uniform3i_ARB uniform4i_ARB uniform1fv_ARB uniform2fv_ARB uniform3fv_ARB uniform4fv_ARB uniform1iv_ARB uniform2iv_ARB uniform3iv_ARB uniform4iv_ARB "
		+"uniform_matrix2fv_ARB uniform_matrix3fv_ARB uniform_matrix4fv_ARB get_object_parameterfv_ARB get_object_parameteriv_ARB get_info_log_ARB "
		+"get_attached_objects_ARB get_uniform_location_ARB get_active_uniform_ARB get_uniformfv_ARB get_uniformiv_ARB get_shader_source_ARB "
		+"bind_attrib_location_ARB get_active_attrib_ARB get_attrib_location_ARB clamp_color_ARB gen_queries delete_queries is_query begin_query end_query "
		+"get_queryiv get_query_objectiv get_query_objectuiv bind_buffer delete_buffers gen_buffers is_buffer buffer_data buffer_sub_data get_buffer_sub_data "
		+"map_buffer unmap_buffer get_buffer_parameteriv get_buffer_pointerv depth_bounds_EXT draw_buffers_ARB is_shader is_program get_shaderiv get_programiv "
		+"get_shader_info_log get_program_info_log stencil_func_separate stencil_mask_separate multi_draw_element_array_APPLE "
		+"multi_draw_range_element_array_APPLE is_renderbuffer_EXT bind_renderbuffer_EXT delete_renderbuffers_EXT gen_renderbuffers_EXT "
		+"renderbuffer_storage_EXT get_renderbuffer_parameteriv_EXT is_framebuffer_EXT bind_framebuffer_EXT delete_framebuffers_EXT gen_framebuffers_EXT "
		+"check_framebuffer_status_EXT framebuffer_texture1D_EXT framebuffer_texture2D_EXT framebuffer_texture3D_EXT framebuffer_renderbuffer_EXT "
		+"get_framebuffer_attachment_parameteriv_EXT generate_mipmap_EXT buffer_parameteri_APPLE flush_mapped_buffer_range_APPLE program_env_parameters4fv_EXT "
		+"program_local_parameters4fv_EXT object_purgeable_APPLE object_unpurgeable_APPLE get_object_parameteriv_APPLE program_parameteri_EXT "
		+"framebuffer_texture_EXT framebuffer_texture_layer_EXT framebuffer_texture_face_EXT bind_buffer_range_EXT bind_buffer_offset_EXT bind_buffer_base_EXT "
		+"begin_transform_feedback_EXT end_transform_feedback_EXT transform_feedback_varyings_EXT get_transform_feedback_varying_EXT get_integer_indexedv_EXT "
		+"get_boolean_indexedv_EXT uniform_buffer_EXT get_uniform_buffer_size_EXT get_uniform_buffer_offset_EXT clear_colorIi_EXT clear_colorIui_EXT "
		+"tex_parameterIiv_EXT tex_parameterIuiv_EXT get_tex_parameterIiv_EXT get_tex_parameterIuiv_EXT vertex_attribI1i_EXT vertex_attribI2i_EXT "
		+"vertex_attribI3i_EXT vertex_attribI4i_EXT vertex_attribI1ui_EXT vertex_attribI2ui_EXT vertex_attribI3ui_EXT vertex_attribI4ui_EXT "
		+"vertex_attribI1iv_EXT vertex_attribI2iv_EXT vertex_attribI3iv_EXT vertex_attribI4iv_EXT vertex_attribI1uiv_EXT vertex_attribI2uiv_EXT "
		+"vertex_attribI3uiv_EXT vertex_attribI4uiv_EXT vertex_attribI4bv_EXT vertex_attribI4sv_EXT vertex_attribI4ubv_EXT vertex_attribI4usv_EXT "
		+"vertex_attribI_pointer_EXT get_vertex_attribIiv_EXT get_vertex_attribIuiv_EXT uniform1ui_EXT uniform2ui_EXT uniform3ui_EXT uniform4ui_EXT "
		+"uniform1uiv_EXT uniform2uiv_EXT uniform3uiv_EXT uniform4uiv_EXT get_uniformuiv_EXT bind_frag_data_location_EXT get_frag_data_location_EXT "
		+"color_mask_indexed_EXT enable_indexed_EXT disable_indexed_EXT is_enabled_indexed_EXT uniform_matrix2x3fv uniform_matrix3x2fv uniform_matrix2x4fv "
		+"uniform_matrix4x2fv uniform_matrix3x4fv uniform_matrix4x3fv blit_framebuffer_EXT renderbuffer_storage_multisample_EXT begin_conditional_render_NV "
		+"end_conditional_render_NV get_attached_shaders provoking_vertex_EXT vertex_attrib_divisor draw_arrays_instanced draw_elements_instanced "
		+"draw_elements_base_vertex draw_range_elements_base_vertex draw_elements_instanced_base_vertex multi_draw_elements_base_vertex bind_vertex_array_ARB "
		+"delete_vertex_arrays_ARB gen_vertex_arrays_ARB is_vertex_array_ARB point_size_pointer vertex_point_sizef_APPLE clear_bufferiv clear_bufferuiv "
		+"clear_bufferfv clear_bufferfi get_stringi fence_sync is_sync delete_sync client_wait_sync wait_sync get_integer64v_sync get_synciv "
		+"tex_image2D_multisample tex_image3D_multisample get_multisamplefv sample_maski tex_buffer copy_buffer_sub_data primitive_restart_index "
		+"get_query_objecti64v get_query_objectui64v map_buffer_range flush_mapped_buffer_range query_counter get_integer64i_v get_buffer_parameteri64v "
		+"gen_samplers delete_samplers is_sampler bind_sampler sampler_parameteri sampler_parameteriv sampler_parameterf sampler_parameterfv "
		+"sampler_parameterIiv sampler_parameterIuiv get_sampler_parameteriv get_sampler_parameterfv get_sampler_parameterIiv get_sampler_parameterIuiv "
		+"label_object_EXT get_object_label_EXT insert_event_marker_EXT push_group_marker_EXT pop_group_marker_EXT use_program_stages active_shader_program "
		+"create_shader_programv bind_program_pipeline delete_program_pipelines gen_program_pipelines is_program_pipeline get_program_pipelineiv "
		+"validate_program_pipeline get_program_pipeline_info_log program_uniform1i program_uniform2i program_uniform3i program_uniform4i program_uniform1f "
		+"program_uniform2f program_uniform3f program_uniform4f program_uniform1iv program_uniform2iv program_uniform3iv program_uniform4iv program_uniform1fv "
		+"program_uniform2fv program_uniform3fv program_uniform4fv program_uniform_matrix2fv program_uniform_matrix3fv program_uniform_matrix4fv "
		+"program_uniform1ui program_uniform2ui program_uniform3ui program_uniform4ui program_uniform1uiv program_uniform2uiv program_uniform3uiv "
		+"program_uniform4uiv program_uniform_matrix2x3fv program_uniform_matrix3x2fv program_uniform_matrix2x4fv program_uniform_matrix4x2fv "
		+"program_uniform_matrix3x4fv program_uniform_matrix4x3fv bind_frag_data_location_indexed get_frag_data_index blend_func_i blend_func_separate_i "
		+"blend_equation_i blend_equation_separate_i named_string_ARB delete_named_string_ARB compile_shader_include_ARB is_named_string_ARB "
		+"get_named_string_ARB get_named_string_iv_ARB release_shader_compiler shader_binary get_shader_precision_format depth_rangef clear_depthf "
		+"vertex_attribP1ui vertex_attribP2ui vertex_attribP3ui vertex_attribP4ui vertex_attribP1uiv vertex_attribP2uiv vertex_attribP3uiv vertex_attribP4uiv "
		+"get_program_binary program_binary min_sample_shading viewport_arrayv viewport_indexedf viewport_indexedfv scissor_arrayv scissor_indexed "
		+"scissor_indexedv depth_range_arrayv depth_range_indexed get_floati_v get_doublei_v draw_arrays_indirect draw_elements_indirect patch_parameteri "
		+"patch_parameterfv bind_transform_feedback gen_transform_feedbacks delete_transform_feedbacks pause_transform_feedback resume_transform_feedback "
		+"is_transform_feedback draw_transform_feedback begin_query_indexed end_query_indexed get_query_indexediv draw_transform_feedback_stream "
		+"program_uniform1d program_uniform2d program_uniform3d program_uniform4d program_uniform1dv program_uniform2dv program_uniform3dv program_uniform4dv "
		+"program_uniform_matrix2dv program_uniform_matrix3dv program_uniform_matrix4dv program_uniform_matrix2x3dv program_uniform_matrix3x2dv "
		+"program_uniform_matrix2x4dv program_uniform_matrix4x2dv program_uniform_matrix3x4dv program_uniform_matrix4x3dv uniform1d uniform2d uniform3d "
		+"uniform4d uniform1dv uniform2dv uniform3dv uniform4dv uniform_matrix2dv uniform_matrix3dv uniform_matrix4dv uniform_matrix2x3dv uniform_matrix3x2dv "
		+"uniform_matrix2x4dv uniform_matrix4x2dv uniform_matrix3x4dv uniform_matrix4x3dv get_uniformdv vertex_attribl1d vertex_attribl2d vertex_attribl3d "
		+"vertex_attribl4d vertex_attribl1dv vertex_attribl2dv vertex_attribl3dv vertex_attribl4dv vertex_attrib_lpointer get_vertex_attrib_ldv "
		+"get_subroutine_uniform_location get_subroutine_index get_active_subroutine_uniformiv get_active_subroutine_uniform_name get_active_subroutine_name "
		+"uniform_subroutinesuiv get_uniform_subroutineuiv get_program_stageiv get_internal_formativ tex_storage1D tex_storage2D tex_storage3D "
		+"label_object_with_responsible_process_APPLE texture_barrier_NV multi_draw_elements_indirect_APPLE "
		).trim().split(" ");
	/** The dispatch table's transition vectors, made once and copied into every context record. */
	private int[] dispatchVectors;

	/** A new context record. Programs built with aglMacro.h compile glClear(m) into (*ctx->disp.clear)(ctx->rend, m), so an
	 *  AGLContext points to {GLIContext rend; GLIFunctionDispatch disp; private} (Otto Matic jumped to address 0 through an
	 *  empty table right after aglCreateContext). Here rend is the record itself. */
	private int newContextRecord() throws M68kException {
		if(dispatchVectors==null){
			dispatchVectors=new int[DISPATCH.length];
			for(int i=0;i<DISPATCH.length;i++)dispatchVectors[i]=rt.nativeVector("disp."+DISPATCH[i],dispatched(DISPATCH[i]));
		}
		int ref=rt.allocate(8+4*DISPATCH.length,4);
		memory.writeLong(ref,ref);
		for(int i=0;i<DISPATCH.length;i++)memory.writeLong(ref+4+4*i,dispatchVectors[i]);
		memory.writeLong(ref+4+4*DISPATCH.length,0);
		return ref;
	}
	/** The table entry {@code name} ("clear_color"): the gl function ("glClearColor", or its ARB/EXT form) called with the
	 *  arguments after the rend - integer words move down one register, and one word of the caller's parameter area; the
	 *  floating-point registers stay. The rend's context is current during the call. */
	private PpcRuntime.Native dispatched(String name){
		StringBuilder sb=new StringBuilder("gl");
		for(String part:name.split("_"))if(!part.isEmpty())sb.append(Character.toUpperCase(part.charAt(0))).append(part,1,part.length());
		String gl=sb.toString();
		PpcRuntime.Native n=lib.function(LIBRARY,gl);
		if(n==null)n=lib.function(LIBRARY,gl+"ARB");
		if(n==null)n=lib.function(LIBRARY,gl+"EXT");
		if(n==null){
			final boolean[] told=new boolean[1];
			return (r,c)->{if(!told[0]){told[0]=true;System.err.println("[ppc] OpenGL dispatch table entry "+name+" ("+gl+") is not implemented: returns 0");}c.r[3]=0;};
		}
		final PpcRuntime.Native target=n;
		return (r,c)->{
			Context x=contexts.get(c.r[3]),saved=current;
			if(x!=null)current=x;
			for(int i=3;i<10;i++)c.r[i]=c.r[i+1];
			c.r[10]=memory.readLong(c.r[1]+24+4*8);
			r.stackArgShift=1;
			try{target.call(r,c);}
			finally{r.stackArgShift=0;if(x!=null&&current==x)current=saved;}
		};
	}

	// ================================================================== GL helpers

	/** The current context's pipeline, or null without a current context. */
	private GlRenderer g(){return current==null?null:current.g;}
	/** Runs (or records into the display list being compiled) a command of the current context. */
	private void cmd(GlRenderer.Command c){GlRenderer g=g();if(g!=null)g.submit(c);}

	private PpcLibraries lib;
	private void def(String name,PpcRuntime.Native n){def(LIBRARY,name,n);}
	private void def(String library,String name,PpcRuntime.Native n){
		if(TRACE&&traced(name)){final PpcRuntime.Native inner=n;n=(r,c)->{
			System.err.printf("[gl] %s(%08X %08X %08X %08X | %g %g %g %g %g %g %g %g %g)%n",name,c.r[3],c.r[4],c.r[5],c.r[6],r.farg(0),r.farg(1),r.farg(2),r.farg(3),r.farg(4),r.farg(5),r.farg(6),r.farg(7),r.farg(8));
			inner.call(r,c);};}
		lib.register(library,name,n);
	}

	// ---- values of the scalar argument types (glVertex3s etc.) and the vector forms
	private static int size(char t){switch(t){case 'b':case 'B':return 1;case 's':case 'S':return 2;case 'd':return 8;default:return 4;}}
	/** n values of type t: from the pointer in GPR slot {@code slot} (vector forms), or from the argument registers. */
	private float[] values(char t,int n,boolean vector,int slot) throws M68kCpuException {
		float[] r=new float[n];
		if(vector){int p=a(slot);for(int i=0;i<n;i++)r[i]=element(t,p+i*size(t));return r;}
		for(int i=0;i<n;i++){
			switch(t){
				case 'f':case 'd':r[i]=f(i);break;
				case 'b':r[i]=(byte)a(slot+i);break;case 'B':r[i]=a(slot+i)&0xff;break;
				case 's':r[i]=(short)a(slot+i);break;case 'S':r[i]=a(slot+i)&0xffff;break;
				case 'I':r[i]=a(slot+i)&0xffffffffL;break;
				default:r[i]=a(slot+i);
			}
		}
		return r;
	}
	private float element(char t,int p) throws M68kCpuException {
		switch(t){
			case 'f':return rf(p);case 'd':return (float)rd(p);
			case 'b':return (byte)memory.readByte(p);case 'B':return memory.readByte(p)&0xff;
			case 's':return (short)memory.readWord(p);case 'S':return memory.readWord(p)&0xffff;
			case 'I':return memory.readLong(p)&0xffffffffL;default:return memory.readLong(p);
		}
	}
	/** Integer colour components mapped to [0, 1] (OpenGL 1.2 table 2.6). */
	private static float normalize(char t,float v){
		switch(t){
			case 'b':return (2*v+1)/255f;case 'B':return v/255f;case 's':return (2*v+1)/65535f;case 'S':return v/65535f;
			case 'i':return (float)((2.0*v+1)/4294967295.0);case 'I':return (float)(v/4294967295.0);default:return v;
		}
	}
	/** The OpenGL suffix letter to our type code (unsigned forms upper case). */
	private static char code(String suffix){
		switch(suffix){case "b":return 'b';case "ub":return 'B';case "s":return 's';case "us":return 'S';case "i":return 'i';case "ui":return 'I';case "f":return 'f';case "d":return 'd';default:return 'f';}
	}

	// ================================================================== pixel transfer

	private int unpackAlignment=4,unpackRowLength,unpackSkipRows,unpackSkipPixels;private boolean unpackSwap,unpackLsbFirst;
	private int packAlignment=4,packRowLength,packSkipRows,packSkipPixels;private boolean packSwap;

	static final int RGB=0x1907,RGBA=0x1908,ALPHA=0x1906,LUMINANCE=0x1909,LUMINANCE_ALPHA=0x190A,RED=0x1903,GREEN=0x1904,BLUE=0x1905,
		BGR=0x80E0,BGRA=0x80E1,ABGR=0x8000,COLOR_INDEX=0x1900,STENCIL_INDEX=0x1901,DEPTH_COMPONENT=0x1902,INTENSITY=0x8049;
	static final int T_BYTE=0x1400,T_UBYTE=0x1401,T_SHORT=0x1402,T_USHORT=0x1403,T_INT=0x1404,T_UINT=0x1405,T_FLOAT=0x1406,T_BITMAP=0x1A00;

	/** The components of a pixel format, in memory order: 0 R, 1 G, 2 B, 3 A, 4 luminance; null when unsupported. */
	private static int[] components(int format){
		switch(format){
			case RGB:return new int[]{0,1,2};case RGBA:return new int[]{0,1,2,3};case BGR:return new int[]{2,1,0};case BGRA:return new int[]{2,1,0,3};
			case ABGR:return new int[]{3,2,1,0};case ALPHA:return new int[]{3};case LUMINANCE:return new int[]{4};case LUMINANCE_ALPHA:return new int[]{4,3};
			case RED:return new int[]{0};case GREEN:return new int[]{1};case BLUE:return new int[]{2};case INTENSITY:return new int[]{4};
			case 1:return new int[]{4};case 2:return new int[]{4,3};case 3:return new int[]{0,1,2};case 4:return new int[]{0,1,2,3};
			default:return null;
		}
	}
	/** Bit widths of a packed pixel type's components (most significant first; for _REV types least significant first), null if not packed. */
	private static int[] packed(int type){
		switch(type){
			case 0x8032:case 0x8362:return new int[]{3,3,2};
			case 0x8363:case 0x8364:return new int[]{5,6,5};
			case 0x8033:case 0x8365:return new int[]{4,4,4,4};
			case 0x8034:case 0x8366:return new int[]{5,5,5,1};
			case 0x8035:case 0x8367:return new int[]{8,8,8,8};
			case 0x8036:case 0x8368:return new int[]{10,10,10,2};
			default:return null;
		}
	}
	private static boolean reversed(int type){return type>=0x8362&&type<=0x8368;}
	private static int packedBytes(int type){switch(type){case 0x8032:case 0x8362:return 1;case 0x8363:case 0x8364:case 0x8033:case 0x8365:case 0x8034:case 0x8366:return 2;default:return 4;}}
	private static int typeBytes(int type){switch(type){case T_BYTE:case T_UBYTE:return 1;case T_SHORT:case T_USHORT:return 2;default:return 4;}}

	/** The stride in bytes of rows of {@code n} pixels of {@code bytes} bytes with components of {@code s} bytes (OpenGL 1.2 3.6.4). */
	private static int stride(int n,int bytes,int s,int align){
		int k=n*bytes;
		if(s>=align)return k;
		return (k+align-1)/align*align;
	}

	/** Decodes a pixel rectangle into ARGB (rows in memory order); luminance spreads to R, G and B. Null when unsupported. */
	private int[] unpack(int w,int h,int format,int type,int p) throws M68kCpuException {
		int[] comps=components(format);
		if(comps==null||p==0||w<=0||h<=0)return null;
		int[] widths=packed(type);
		int pixelBytes,compBytes;
		if(widths!=null){if(widths.length!=comps.length&&!(widths.length==4&&comps.length==4))return null;pixelBytes=packedBytes(type);compBytes=pixelBytes;}
		else{compBytes=typeBytes(type);pixelBytes=compBytes*comps.length;}
		int rowLength=unpackRowLength>0?unpackRowLength:w;
		int stride=stride(rowLength,pixelBytes,compBytes,unpackAlignment);
		int start=p+unpackSkipRows*stride+unpackSkipPixels*pixelBytes;
		byte[] m=memory.readBytes(start,stride*(h-1)+w*pixelBytes);
		int[] out=new int[w*h];
		float[] v=new float[5];
		for(int y=0;y<h;y++){
			int o=y*stride;
			for(int x=0;x<w;x++,o+=pixelBytes){
				v[0]=v[1]=v[2]=0;v[3]=1;v[4]=0;
				if(widths!=null){
					long word=0;for(int i=0;i<pixelBytes;i++)word=word<<8|(m[o+(unpackSwap?pixelBytes-1-i:i)]&0xff);
					int total=0;for(int wd:widths)total+=wd;
					int shift=reversed(type)?0:total;
					for(int i=0;i<comps.length;i++){
						int wd=widths[i];
						int sh=reversed(type)?shift:(shift-=wd);
						if(reversed(type))shift+=wd;
						long max=(1L<<wd)-1;v[comps[i]]=((word>>>sh)&max)/(float)max;
					}
				}else{
					for(int i=0;i<comps.length;i++)v[comps[i]]=component(m,o+i*compBytes,type);
				}
				if(comps[0]==4){v[0]=v[1]=v[2]=v[4];}
				out[y*w+x]=to255(v[3])<<24|to255(v[0])<<16|to255(v[1])<<8|to255(v[2]);
			}
		}
		return out;
	}
	private float component(byte[] m,int o,int type){
		switch(type){
			case T_UBYTE:return (m[o]&0xff)/255f;
			case T_BYTE:return Math.max(0,m[o])/127f;
			case T_USHORT:return u16(m,o)/65535f;
			case T_SHORT:return Math.max(0,(short)u16(m,o))/32767f;
			case T_UINT:return (float)((u32(m,o)&0xffffffffL)/4294967295.0);
			case T_INT:return (float)(Math.max(0,u32(m,o))/2147483647.0);
			case T_FLOAT:return GlRenderer.clamp01(Float.intBitsToFloat(u32(m,o)));
			default:return 0;
		}
	}
	private int u16(byte[] m,int o){return unpackSwap?(m[o+1]&0xff)<<8|m[o]&0xff:(m[o]&0xff)<<8|m[o+1]&0xff;}
	private int u32(byte[] m,int o){return unpackSwap?(m[o+3]&0xff)<<24|(m[o+2]&0xff)<<16|(m[o+1]&0xff)<<8|m[o]&0xff:(m[o]&0xff)<<24|(m[o+1]&0xff)<<16|(m[o+2]&0xff)<<8|m[o+3]&0xff;}
	private static int to255(float v){return v<=0?0:v>=1?255:(int)(v*255+0.5f);}

	/** Encodes ARGB pixels (or depth values) into guest memory in a format and type, with the pack parameters. */
	private void pack(int w,int h,int format,int type,int p,int[] argb,float[] depth) throws M68kCpuException {
		if(p==0||w<=0||h<=0)return;
		int[] comps=format==DEPTH_COMPONENT||format==STENCIL_INDEX?new int[]{5}:components(format);
		if(comps==null)return;
		int[] widths=packed(type);
		int pixelBytes,compBytes;
		if(widths!=null){pixelBytes=packedBytes(type);compBytes=pixelBytes;}else{compBytes=typeBytes(type);pixelBytes=compBytes*comps.length;}
		int rowLength=packRowLength>0?packRowLength:w;
		int stride=stride(rowLength,pixelBytes,compBytes,packAlignment);
		int start=p+packSkipRows*stride+packSkipPixels*pixelBytes;
		byte[] row=new byte[w*pixelBytes];
		float[] v=new float[6];
		for(int y=0;y<h;y++){
			for(int x=0;x<w;x++){
				int c=argb!=null?argb[y*w+x]:0;
				v[0]=((c>>>16)&255)/255f;v[1]=((c>>>8)&255)/255f;v[2]=(c&255)/255f;v[3]=(c>>>24)/255f;
				v[4]=Math.min(1,v[0]+v[1]+v[2]);v[5]=depth!=null?depth[y*w+x]:0;
				int o=x*pixelBytes;
				if(widths!=null){
					int total=0;for(int wd:widths)total+=wd;long word=0;int shift=reversed(type)?0:total;
					for(int i=0;i<comps.length&&i<widths.length;i++){int wd=widths[i];int sh=reversed(type)?shift:(shift-=wd);if(reversed(type))shift+=wd;
						long max=(1L<<wd)-1;word|=((long)Math.round(v[comps[i]]*max)&max)<<sh;}
					for(int i=0;i<pixelBytes;i++)row[o+i]=(byte)(word>>>(8*(pixelBytes-1-i)));
				}else{
					for(int i=0;i<comps.length;i++){
						float val=v[comps[i]];int q=o+i*compBytes;
						switch(type){
							case T_UBYTE:row[q]=(byte)Math.round(val*255);break;
							case T_BYTE:row[q]=(byte)Math.round(val*127);break;
							case T_USHORT:{int iv=Math.round(val*65535);row[q]=(byte)(iv>>8);row[q+1]=(byte)iv;break;}
							case T_SHORT:{int iv=Math.round(val*32767);row[q]=(byte)(iv>>8);row[q+1]=(byte)iv;break;}
							case T_UINT:{long iv=Math.round(val*4294967295.0);put32(row,q,(int)iv);break;}
							case T_INT:{long iv=Math.round(val*2147483647.0);put32(row,q,(int)iv);break;}
							case T_FLOAT:put32(row,q,Float.floatToRawIntBits(val));break;
							default:break;
						}
					}
				}
			}
			memory.load(start+y*stride,row,0,row.length);
		}
	}
	private static void put32(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}

	/** The base internal format (GlRenderer.F_*) of a texture internal format, or 0. */
	static int baseFormat(int internal){
		if(internal==1||internal==LUMINANCE||(internal>=0x803F&&internal<=0x8042))return GlRenderer.F_LUMINANCE;
		if(internal==2||internal==LUMINANCE_ALPHA||(internal>=0x8043&&internal<=0x8048))return GlRenderer.F_LUMINANCE_ALPHA;
		if(internal==3||internal==RGB||internal==BGR||internal==0x2A10||(internal>=0x804F&&internal<=0x8054))return GlRenderer.F_RGB;
		if(internal==4||internal==RGBA||internal==BGRA||internal==ABGR||(internal>=0x8055&&internal<=0x805B))return GlRenderer.F_RGBA;
		if(internal==ALPHA||(internal>=0x803B&&internal<=0x803E))return GlRenderer.F_ALPHA;
		if(internal==INTENSITY||(internal>=0x804A&&internal<=0x804D))return GlRenderer.F_INTENSITY;
		return 0;
	}
	/** Pixels as a texture of a base format keeps them (luminance from red, alpha 1 without one). */
	static int[] toTexels(int[] argb,int base){
		if(argb==null)return null;
		int[] r=argb.clone();
		for(int i=0;i<r.length;i++){
			int c=r[i],red=(c>>>16)&255,al=c>>>24;
			switch(base){
				case GlRenderer.F_LUMINANCE:r[i]=0xff000000|red<<16|red<<8|red;break;
				case GlRenderer.F_LUMINANCE_ALPHA:r[i]=al<<24|red<<16|red<<8|red;break;
				case GlRenderer.F_INTENSITY:r[i]=red<<24|red<<16|red<<8|red;break;
				case GlRenderer.F_ALPHA:r[i]=al<<24;break;
				case GlRenderer.F_RGB:r[i]=c|0xff000000;break;
				default:break;
			}
		}
		return r;
	}
	/** Strips a one-texel border. */
	private static int[] crop(int[] p,int w,int h){int iw=w-2,ih=h-2;int[] r=new int[Math.max(0,iw*ih)];for(int y=0;y<ih;y++)System.arraycopy(p,(y+1)*w+1,r,y*iw,iw);return r;}
	/** Bilinear rescale (gluScaleImage). */
	static int[] scale(int[] p,int w,int h,int nw,int nh){
		int[] r=new int[nw*nh];
		for(int y=0;y<nh;y++){
			float sy=nh==1?0:(y+0.5f)*h/nh-0.5f;int y0=Math.max(0,Math.min(h-1,(int)Math.floor(sy))),y1=Math.min(h-1,y0+1);float fy=Math.max(0,Math.min(1,sy-y0));
			for(int x=0;x<nw;x++){
				float sx=nw==1?0:(x+0.5f)*w/nw-0.5f;int x0=Math.max(0,Math.min(w-1,(int)Math.floor(sx))),x1=Math.min(w-1,x0+1);float fx=Math.max(0,Math.min(1,sx-x0));
				int c00=p[y0*w+x0],c10=p[y0*w+x1],c01=p[y1*w+x0],c11=p[y1*w+x1],v=0;
				for(int sft=0;sft<32;sft+=8){
					float top=((c00>>>sft)&255)*(1-fx)+((c10>>>sft)&255)*fx,bot=((c01>>>sft)&255)*(1-fx)+((c11>>>sft)&255)*fx;
					v|=Math.round(top*(1-fy)+bot*fy)<<sft;
				}
				r[y*nw+x]=v;
			}
		}
		return r;
	}

	// ================================================================== vertex arrays

	private static final class Array {boolean enabled;int size=4,type=T_FLOAT,stride,pointer;}
	private final Array vertexArray=new Array(),normalArray=new Array(),colorArray=new Array(),edgeFlagArray=new Array(),indexArray=new Array();
	private final Array[] texCoordArrays={new Array(),new Array()};
	private int clientActiveUnit;
	private Array clientArray(int cap){
		switch(cap){case 0x8074:return vertexArray;case 0x8075:return normalArray;case 0x8076:return colorArray;case 0x8077:return indexArray;
			case 0x8078:return texCoordArrays[clientActiveUnit];case 0x8079:return edgeFlagArray;default:return null;}
	}
	private static char arrayType(int type){switch(type){case T_BYTE:return 'b';case T_UBYTE:return 'B';case T_SHORT:return 's';case T_USHORT:return 'S';case T_INT:return 'i';case T_UINT:return 'I';case 0x140A:return 'd';default:return 'f';}}
	private float[] arrayValues(Array ar,int i,int n) throws M68kCpuException {
		char t=arrayType(ar.type);int es=size(t);int stride=ar.stride!=0?ar.stride:es*ar.size;
		int p=ar.pointer+i*stride;float[] r=new float[n];
		for(int k=0;k<n&&k<ar.size;k++)r[k]=element(t,p+k*es);
		return r;
	}
	/** One array element (glArrayElement): its colour, normal, texture coordinates and vertex as immediate-mode commands. */
	private void arrayElement(int i) throws M68kCpuException {
		if(colorArray.enabled){char t=arrayType(colorArray.type);float[] v=arrayValues(colorArray,i,4);
			float r=normalize(t,v[0]),gg=normalize(t,v[1]),b=normalize(t,v[2]),al=colorArray.size>3?normalize(t,v[3]):1;cmd(g->g.color(r,gg,b,al));}
		if(normalArray.enabled){char t=arrayType(normalArray.type);float[] v=arrayValues(normalArray,i,3);
			float x=t=='f'||t=='d'?v[0]:normalize(t,v[0])*2-1,y=t=='f'||t=='d'?v[1]:normalize(t,v[1])*2-1,z=t=='f'||t=='d'?v[2]:normalize(t,v[2])*2-1;cmd(g->g.normal(x,y,z));}
		for(int u=0;u<texCoordArrays.length;u++){Array ar=texCoordArrays[u];if(!ar.enabled)continue;float[] v=arrayValues(ar,i,4);
			float s=v[0],t=ar.size>1?v[1]:0,rr=ar.size>2?v[2]:0,q=ar.size>3?v[3]:1;final int unit=u;cmd(g->g.texCoord(unit,s,t,rr,q));}
		if(vertexArray.enabled){float[] v=arrayValues(vertexArray,i,4);float x=v[0],y=v[1],z=vertexArray.size>2?v[2]:0,w=vertexArray.size>3?v[3]:1;cmd(g->g.vertex(x,y,z,w));}
	}
	private int index(int type,int p,int i) throws M68kCpuException {
		switch(type){case T_UBYTE:return memory.readByte(p+i)&0xff;case T_USHORT:return memory.readWord(p+2*i)&0xffff;default:return memory.readLong(p+4*i);}
	}

	// ================================================================== strings

	private final Map<String,Integer> strings=new HashMap<String,Integer>();
	private int string(String s) throws M68kCpuException {
		Integer p=strings.get(s);if(p!=null)return p;
		byte[] b=s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);int q=rt.allocate(b.length+1,4);memory.load(q,b);memory.writeByte(q+b.length,0);
		strings.put(s,q);return q;
	}

	// ================================================================== glGet

	/** The values of a state variable, null for an unknown one. */
	private double[] get(GlRenderer g,int pname){
		GlRenderer.State s=g.s;
		Boolean en=g.isEnabled(pname);if(en!=null)return new double[]{en?1:0};
		switch(pname){
			case 0x0BA2:return new double[]{s.vpX,s.vpY,s.vpW,s.vpH}; // VIEWPORT
			case 0x0D33:return new double[]{GlRenderer.MAX_TEXTURE_SIZE};
			case 0x0D31:return new double[]{GlRenderer.LIGHTS};
			case 0x0D32:return new double[]{GlRenderer.CLIP_PLANES};
			case 0x84E2:return new double[]{GlRenderer.UNITS};
			case 0x0BA0:return new double[]{g.matrixMode};
			case 0x0BA6:return g.matrix(GlRenderer.MODELVIEW).clone();
			case 0x0BA7:return g.matrix(GlRenderer.PROJECTION).clone();
			case 0x0BA8:return g.matrix(GlRenderer.TEXTURE).clone();
			case 0x84E3:return transpose(g.matrix(GlRenderer.MODELVIEW));case 0x84E4:return transpose(g.matrix(GlRenderer.PROJECTION));case 0x84E5:return transpose(g.matrix(GlRenderer.TEXTURE));
			case 0x0BA3:return new double[]{g.stackDepth(GlRenderer.MODELVIEW)};case 0x0BA4:return new double[]{g.stackDepth(GlRenderer.PROJECTION)};case 0x0BA5:return new double[]{g.stackDepth(GlRenderer.TEXTURE)};
			case 0x0D36:case 0x0D38:case 0x0D39:return new double[]{GlRenderer.STACK_DEPTH};
			case 0x0D35:case 0x0D3B:return new double[]{GlRenderer.ATTRIB_DEPTH};
			case 0x0B31:return new double[]{64}; // MAX_LIST_NESTING
			case 0x0D37:return new double[]{64};
			case 0x0D3A:return new double[]{4096,4096};
			case 0x0D50:return new double[]{4}; // SUBPIXEL_BITS
			case 0x0D52:case 0x0D53:case 0x0D54:return new double[]{8};
			case 0x0D55:return new double[]{current!=null&&current.pf!=null?current.pf.alphaSize>0?8:0:8};
			case 0x0D56:return new double[]{current!=null&&current.pf!=null&&current.pf.depthSize>0?Math.max(16,Math.min(32,current.pf.depthSize)):16};
			case 0x0D57:return new double[]{current!=null&&current.pf!=null&&current.pf.stencilSize>0?8:0};
			case 0x0D51:return new double[]{0};
			case 0x0D58:case 0x0D59:case 0x0D5A:case 0x0D5B:return new double[]{0}; // accumulation bits
			case 0x0C32:return new double[]{current==null||current.doubleBuffered()?1:0};
			case 0x0C33:return new double[]{0}; // STEREO
			case 0x0B00:return f2d(s.color);
			case 0x0B02:return f2d(s.normal);
			case 0x0B03:return f2d(s.texCoord[s.activeUnit]);
			case 0x0B04:return f2d(g.rasterColor);
			case 0x0B07:return new double[]{g.rasterX,g.rasterY,g.rasterZ,1};
			case 0x0B08:return new double[]{g.rasterValid?1:0};
			case 0x0B11:return new double[]{s.pointSize};case 0x0B12:return new double[]{1,64};case 0x0B13:return new double[]{1};
			case 0x0B21:return new double[]{s.lineWidth};case 0x0B22:return new double[]{1,64};case 0x0B23:return new double[]{1};
			case 0x0B30:return new double[]{g.compileAndExecute?GlRenderer.COMPILE_AND_EXECUTE:GlRenderer.COMPILE};
			case 0x0B32:return new double[]{g.listBase};
			case 0x0B33:return new double[]{g.compilingName()};
			case 0x0B40:return new double[]{s.polygonModeFront,s.polygonModeBack};
			case 0x0B45:return new double[]{s.cullMode};case 0x0B46:return new double[]{s.frontFace};
			case 0x0B53:return f2d(s.lightModelAmbient);case 0x0B51:return new double[]{s.lightModelLocalViewer?1:0};case 0x0B52:return new double[]{s.lightModelTwoSide?1:0};
			case 0x0B54:return new double[]{s.shadeModel};
			case 0x0B55:return new double[]{s.colorMaterialFace};case 0x0B56:return new double[]{s.colorMaterialMode};
			case 0x0B62:return new double[]{s.fogDensity};case 0x0B63:return new double[]{s.fogStart};case 0x0B64:return new double[]{s.fogEnd};case 0x0B65:return new double[]{s.fogMode};case 0x0B66:return f2d(s.fogColor);
			case 0x0B70:return new double[]{s.depthNear,s.depthFar};
			case 0x0B72:return new double[]{s.depthMask?1:0};case 0x0B73:return new double[]{s.clearDepth};case 0x0B74:return new double[]{s.depthFunc};
			case 0x0B91:return new double[]{s.clearStencil};case 0x0B92:return new double[]{s.stencilFunc};case 0x0B93:return new double[]{s.stencilValueMask};
			case 0x0B94:return new double[]{s.stencilFail};case 0x0B95:return new double[]{s.stencilZFail};case 0x0B96:return new double[]{s.stencilZPass};
			case 0x0B97:return new double[]{s.stencilRef};case 0x0B98:return new double[]{s.stencilWriteMask};
			case 0x0BC1:return new double[]{s.alphaFunc};case 0x0BC2:return new double[]{s.alphaRef};
			case 0x0BE0:return new double[]{s.blendDst};case 0x0BE1:return new double[]{s.blendSrc};case 0x8009:return new double[]{s.blendEquation};case 0x8005:return f2d(s.blendColor);
			case 0x0C10:return new double[]{s.scX,s.scY,s.scW,s.scH};
			case 0x0C22:return f2d(s.clearColor);
			case 0x0C23:return new double[]{s.colorMask[0]?1:0,s.colorMask[1]?1:0,s.colorMask[2]?1:0,s.colorMask[3]?1:0};
			case 0x0C40:return new double[]{0x1C00}; // RENDER_MODE
			case 0x0C50:case 0x0C51:case 0x0C52:case 0x0C53:case 0x0C54:return new double[]{0x1100};
			case 0x0CF2:return new double[]{unpackRowLength};case 0x0CF3:return new double[]{unpackSkipRows};case 0x0CF4:return new double[]{unpackSkipPixels};case 0x0CF5:return new double[]{unpackAlignment};
			case 0x0CF0:return new double[]{unpackSwap?1:0};case 0x0CF1:return new double[]{unpackLsbFirst?1:0};
			case 0x0D02:return new double[]{packRowLength};case 0x0D03:return new double[]{packSkipRows};case 0x0D04:return new double[]{packSkipPixels};case 0x0D05:return new double[]{packAlignment};
			case 0x0D00:return new double[]{packSwap?1:0};case 0x0D01:return new double[]{0};
			case 0x0D16:return new double[]{s.zoomX};case 0x0D17:return new double[]{s.zoomY};
			case 0x8068:return new double[]{s.units[s.activeUnit].binding1D};case 0x8069:return new double[]{s.units[s.activeUnit].binding2D};
			case 0x84E0:return new double[]{0x84C0+s.activeUnit};case 0x84E1:return new double[]{0x84C0+clientActiveUnit};
			case 0x8038:return new double[]{s.polygonOffsetFactor};case 0x2A00:return new double[]{s.polygonOffsetUnits};
			case 0x0C01:case 0x0C02:return new double[]{current==null||current.doubleBuffered()?0x405:0x404}; // DRAW_BUFFER, READ_BUFFER: BACK or FRONT
			case 0x0D30:return new double[]{0}; // MAX_EVAL_ORDER... (unused)
			case 0x8074:case 0x8075:case 0x8076:case 0x8077:case 0x8078:case 0x8079:{Array ar=clientArray(pname);return new double[]{ar!=null&&ar.enabled?1:0};}
			case 0x807A:return new double[]{vertexArray.size};case 0x807B:return new double[]{vertexArray.type};case 0x807C:return new double[]{vertexArray.stride};
			case 0x81F8:return new double[]{s.separateSpecular?0x81FA:0x81F9};
			case 0x0DE0+0x80:return null;
			default:return null;
		}
	}
	private static double[] f2d(float[] f){double[] r=new double[f.length];for(int i=0;i<f.length;i++)r[i]=f[i];return r;}
	private static double[] transpose(double[] m){double[] r=new double[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)r[i*4+j]=m[j*4+i];return r;}
	private static boolean colorLike(int pname){return pname==0x0B00||pname==0x0C22||pname==0x0B66||pname==0x0B53||pname==0x8005||pname==0x0B04;}

	// ================================================================== GLU quadrics

	private static final class Quadric {int drawStyle=100012/*GLU_FILL*/,normals=100000/*GLU_SMOOTH*/,orientation=100020/*GLU_OUTSIDE*/;boolean texture;}
	private final Map<Integer,Quadric> quadrics=new HashMap<Integer,Quadric>();
	static final int GLU_POINT=100010,GLU_LINE=100011,GLU_FILL=100012,GLU_SILHOUETTE=100013,GLU_SMOOTH=100000,GLU_FLAT=100001,GLU_NONE=100002,GLU_OUTSIDE=100020,GLU_INSIDE=100021;

	/** Emits a quadric's quad-strip mesh: points[i][j] = {x,y,z,nx,ny,nz,s,t}, strips between rows i and i+1. */
	private void mesh(Quadric q,double[][][] pts,boolean closedRows){
		int rows=pts.length,cols=pts[0].length;
		boolean inside=q.orientation==GLU_INSIDE;
		if(q.drawStyle==GLU_FILL){
			for(int i=0;i+1<rows;i++){
				cmd(g->g.begin(GlRenderer.QUAD_STRIP));
				for(int j=0;j<cols;j++){
					double[] p0=pts[inside?i+1:i][j],p1=pts[inside?i:i+1][j];
					emit(q,p0,inside);emit(q,p1,inside);
				}
				cmd(GlRenderer::end);
			}
			return;
		}
		if(q.drawStyle==GLU_POINT){cmd(g->g.begin(GlRenderer.POINTS));for(double[][] r:pts)for(double[] p:r)emit(q,p,inside);cmd(GlRenderer::end);return;}
		// lines: the rows (and, except for a silhouette, the columns)
		for(int i=0;i<rows;i++){
			if(q.drawStyle==GLU_SILHOUETTE&&i!=0&&i!=rows-1)continue;
			cmd(g->g.begin(GlRenderer.LINE_STRIP));for(int j=0;j<cols;j++)emit(q,pts[i][j],inside);cmd(GlRenderer::end);
		}
		if(q.drawStyle==GLU_LINE)for(int j=0;j<cols;j++){cmd(g->g.begin(GlRenderer.LINE_STRIP));for(int i=0;i<rows;i++)emit(q,pts[i][j],inside);cmd(GlRenderer::end);}
	}
	private void emit(Quadric q,double[] p,boolean inside){
		if(q.normals!=GLU_NONE){float nx=(float)(inside?-p[3]:p[3]),ny=(float)(inside?-p[4]:p[4]),nz=(float)(inside?-p[5]:p[5]);cmd(g->g.normal(nx,ny,nz));}
		if(q.texture){float s=(float)p[6],t=(float)p[7];cmd(g->g.texCoord(0,s,t,0,1));}
		float x=(float)p[0],y=(float)p[1],z=(float)p[2];cmd(g->g.vertex(x,y,z,1));
	}
	private void sphere(Quadric q,double radius,int slices,int stacks){
		slices=Math.max(2,slices);stacks=Math.max(1,stacks);
		double[][][] pts=new double[stacks+1][slices+1][];
		for(int i=0;i<=stacks;i++){double rho=Math.PI*i/stacks;
			for(int j=0;j<=slices;j++){double th=j==slices?0:2*Math.PI*j/slices;
				double nx=Math.cos(th)*Math.sin(rho),ny=Math.sin(th)*Math.sin(rho),nz=Math.cos(rho);
				pts[i][j]=new double[]{nx*radius,ny*radius,nz*radius,nx,ny,nz,(double)j/slices,1-(double)i/stacks};}}
		mesh(q,pts,true);
	}
	private void cylinder(Quadric q,double base,double top,double height,int slices,int stacks){
		slices=Math.max(2,slices);stacks=Math.max(1,stacks);
		double nzc=height==0?0:(base-top)/height;double len=Math.sqrt(1+nzc*nzc);
		double[][][] pts=new double[stacks+1][slices+1][];
		// rows from the top (z = height) down, as the sphere's go from its +z pole, so the strips face outwards
		for(int i=0;i<=stacks;i++){double fz=1-(double)i/stacks,z=height*fz,r=base+(top-base)*fz;
			for(int j=0;j<=slices;j++){double th=j==slices?0:2*Math.PI*j/slices;double c=Math.cos(th),s=Math.sin(th);
				pts[i][j]=new double[]{c*r,s*r,z,c/len,s/len,nzc/len,(double)j/slices,fz};}}
		mesh(q,pts,true);
	}
	private void disk(Quadric q,double inner,double outer,int slices,int loops,double start,double sweep,boolean partial){
		slices=Math.max(2,slices);loops=Math.max(1,loops);
		double[][][] pts=new double[loops+1][slices+1][];
		for(int i=0;i<=loops;i++){
			// rows from the outer edge in, so the strips face +z
			double r=outer-(outer-inner)*i/loops;
			for(int j=0;j<=slices;j++){
				double x,y;
				if(partial){double ang=Math.toRadians(start+sweep*j/slices);x=Math.sin(ang);y=Math.cos(ang);}
				else{double th=j==slices?0:2*Math.PI*j/slices;x=Math.cos(th);y=Math.sin(th);}
				pts[i][j]=new double[]{x*r,y*r,0,0,0,1,0.5+x*r/(2*outer),0.5+y*r/(2*outer)};
			}
		}
		if(partial&&sweep<0){ // clockwise columns turned the other way: flip to keep the strips facing +z
			for(double[][] row:pts)for(int j=0;j<row.length/2;j++){double[] t=row[j];row[j]=row[row.length-1-j];row[row.length-1-j]=t;}
		}
		if(!partial){for(double[][] row:pts)for(int j=0;j<row.length/2;j++){double[] t=row[j];row[j]=row[row.length-1-j];row[row.length-1-j]=t;}}
		mesh(q,pts,!partial);
	}

	// ================================================================== aglUseFont

	/** Display lists base..base+count-1 drawing characters first..first+count-1 of a Macintosh font as glBitmaps. */
	private void useFont(GlRenderer g,int fontID,int face,int size,int first,int count,int base){
		java.awt.Font font=Fonts68k.font(fontID,Math.max(1,size),face);
		java.awt.image.BufferedImage scratch=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D sg=scratch.createGraphics();sg.setFont(font);java.awt.FontMetrics fm=sg.getFontMetrics();sg.dispose();
		int ascent=fm.getAscent(),descent=fm.getDescent(),h=Math.max(1,ascent+descent);
		java.nio.charset.Charset roman=java.nio.charset.Charset.forName("MacRoman");
		for(int i=0;i<count;i++){
			int code=first+i;String ch=code<32?" ":new String(new byte[]{(byte)code},roman);
			int adv=fm.stringWidth(ch),w=Math.max(1,adv);
			java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D ig=img.createGraphics();ig.setFont(font);ig.setColor(java.awt.Color.BLACK);
			ig.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			if(code>=32)ig.drawString(ch,0,ascent);ig.dispose();
			int rowBytes=(w+7)/8;byte[] bits=new byte[rowBytes*h];
			for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((img.getRGB(x,h-1-y)>>>24)>=128)bits[y*rowBytes+(x>>3)]|=(byte)(0x80>>(x&7));
			final int fw=w,fh=h,fd=descent,fadv=adv,frb=rowBytes;
			List<GlRenderer.Command> l=new ArrayList<GlRenderer.Command>();
			l.add(gg->gg.bitmap(fw,fh,0,fd,fadv,0,bits,frb));
			g.lists.put(base+i,l);
		}
	}

	// ================================================================== the libraries

	void define(PpcLibraries l){
		lib=l;
		defineAgl();defineGl();defineGlu();
		def(MEMORY,"glmSetMode",(r,c)->ret(1));
		def(MEMORY,"glmGetInteger",(r,c)->{if(a(1)!=0)memory.writeLong(a(1),0);ret(1);});
		def(MEMORY,"glmReportMemoryStatus",(r,c)->{});
	}

	/** The one-element GDHandle array {@code aglDevicesOfPixelFormat} returns. */
	private int devicesArray;

	private void defineAgl(){
		def("aglChoosePixelFormat",(r,c)->{
			int attribs=a(2);PixelFormat pf=new PixelFormat(rt.allocate(16,4));
			boolean ok=true;
			if(attribs!=0){
				for(int p=attribs,n=0;n<256;n++){
					int at=memory.readLong(p);p+=4;if(at==AGL_NONE)break;
					switch(at){
						case AGL_DOUBLEBUFFER:pf.doubleBuffer=true;break;
						case AGL_STEREO:ok=false;break;
						case AGL_FULLSCREEN:pf.fullScreen=true;break;
						case AGL_OFFSCREEN:pf.offScreen=true;break;
						case AGL_ALL_RENDERERS:case AGL_RGBA:case AGL_MINIMUM_POLICY:case AGL_MAXIMUM_POLICY:case AGL_AUX_DEPTH_STENCIL:case AGL_SINGLE_RENDERER:
						case AGL_NO_RECOVERY:case AGL_ACCELERATED:case AGL_CLOSEST_POLICY:case AGL_ROBUST:case AGL_BACKING_STORE:case AGL_MP_SAFE:case AGL_WINDOW:
						case AGL_MULTISCREEN:case AGL_VIRTUAL_SCREEN:case AGL_COMPLIANT:break;
						case AGL_DEPTH_SIZE:pf.depthSize=memory.readLong(p);p+=4;break;
						case AGL_STENCIL_SIZE:pf.stencilSize=memory.readLong(p);p+=4;break;
						case AGL_ALPHA_SIZE:pf.alphaSize=memory.readLong(p);p+=4;break;
						case AGL_AUX_BUFFERS:pf.auxBuffers=memory.readLong(p);p+=4;break;
						case AGL_PIXEL_SIZE:pf.pixelSize=memory.readLong(p);p+=4;break;
						case AGL_RENDERER_ID:{int id=memory.readLong(p);p+=4;if(id!=RENDERER_ID&&id!=0x00020200&&id!=0x00020600)ok=false;break;}
						case AGL_ACCUM_RED_SIZE:case AGL_ACCUM_RED_SIZE+1:case AGL_ACCUM_RED_SIZE+2:case AGL_ACCUM_ALPHA_SIZE:pf.accumSize=Math.max(pf.accumSize,memory.readLong(p));p+=4;break;
						default:p+=4; // a valued attribute (sizes, level, samples)
					}
				}
			}
			if(!ok||pf.auxBuffers>0){free(pf.ref);aglError=AGL_BAD_MATCH;ret(0);return;}
			pixelFormats.put(pf.ref,pf);aglError=AGL_NO_ERROR;
			if(TRACE)System.err.printf("[gl] pixel format %08X double %b depth %d stencil %d alpha %d fullscreen %b%n",pf.ref,pf.doubleBuffer,pf.depthSize,pf.stencilSize,pf.alphaSize,pf.fullScreen);
			ret(pf.ref);
		});
		def("aglDestroyPixelFormat",(r,c)->{PixelFormat pf=pixelFormats.remove(a(0));if(pf!=null)free(pf.ref);});
		def("aglNextPixelFormat",(r,c)->ret(0));
		// GDHandle *aglDevicesOfPixelFormat(AGLPixelFormat pix, GLint *ndevs): the one display every format covers (Heavy Metal F.A.K.K.2 reads it right after choosing a format)
		def("aglDevicesOfPixelFormat",(r,c)->{
			if(!pixelFormats.containsKey(a(0))){aglError=AGL_BAD_PIXELFMT;if(a(1)!=0)memory.writeLong(a(1),0);ret(0);return;}
			if(devicesArray==0){devicesArray=rt.allocate(4,4);memory.writeLong(devicesArray,tb("GetMainDevice"));}
			if(a(1)!=0)memory.writeLong(a(1),1);
			aglError=AGL_NO_ERROR;ret(devicesArray);
		});
		def("aglDescribePixelFormat",(r,c)->{
			PixelFormat pf=pixelFormats.get(a(0));int out=a(2);
			if(pf==null){aglError=AGL_BAD_PIXELFMT;ret(0);return;}
			int v;
			switch(a(1)){
				case AGL_RGBA:case AGL_ACCELERATED:case AGL_WINDOW:case AGL_COMPLIANT:case AGL_BACKING_STORE:case AGL_ALL_RENDERERS:v=1;break;
				case AGL_DOUBLEBUFFER:v=pf.doubleBuffer?1:0;break;
				case AGL_FULLSCREEN:v=1;break;case AGL_OFFSCREEN:v=1;break;
				case AGL_BUFFER_SIZE:case AGL_PIXEL_SIZE:v=32;break;
				case AGL_RED_SIZE:case AGL_GREEN_SIZE:case AGL_BLUE_SIZE:v=8;break;
				case AGL_ALPHA_SIZE:v=pf.alphaSize>0?8:0;break;
				case AGL_DEPTH_SIZE:v=pf.depthSize>0?Math.max(16,Math.min(32,pf.depthSize)):0;break;
				case AGL_STENCIL_SIZE:v=pf.stencilSize>0?8:0;break;
				case AGL_RENDERER_ID:v=RENDERER_ID;break;
				default:v=0;
			}
			if(out!=0)memory.writeLong(out,v);ret(1);
		});
		def("aglQueryRendererInfo",(r,c)->{if(rendererInfo==0)rendererInfo=rt.allocate(16,4);ret(rendererInfo);});
		def("aglDestroyRendererInfo",(r,c)->{});
		def("aglNextRendererInfo",(r,c)->ret(0));
		def("aglDescribeRenderer",(r,c)->{
			int out=a(2);int v;
			switch(a(1)){
				case AGL_RENDERER_ID:v=RENDERER_ID;break;
				case AGL_OFFSCREEN:case AGL_FULLSCREEN:case AGL_WINDOW:case AGL_ACCELERATED:case AGL_COMPLIANT:case AGL_BACKING_STORE:v=1;break;
				case AGL_ROBUST:case AGL_MP_SAFE:case AGL_MULTISCREEN:v=0;break;
				case 100:v=0x1|0x4|0x8;break; // AGL_BUFFER_MODES: monoscopic, single, double
				case 101:case 102:v=0;break; // levels
				case 103:v=0x00ff0000|0x0000ffff;break; // AGL_COLOR_MODES
				case 104:v=0;break; // AGL_ACCUM_MODES
				case 105:v=0x1|0x400|0x800|0x1000;break; // AGL_DEPTH_MODES: 0, 16, 24, 32 bits
				case 106:v=0x1|0x80;break; // AGL_STENCIL_MODES: 0, 8 bits
				case 107:v=0;break; // AGL_MAX_AUX_BUFFERS
				case 120:case 121:v=16<<20;break; // AGL_VIDEO_MEMORY, AGL_TEXTURE_MEMORY
				default:aglError=AGL_BAD_PROPERTY;ret(0);return;
			}
			if(out!=0)memory.writeLong(out,v);ret(1);
		});
		def("aglCreateContext",(r,c)->{
			PixelFormat pf=pixelFormats.get(a(0));
			if(pf==null&&a(0)!=0){aglError=AGL_BAD_PIXELFMT;ret(0);return;}
			Context x=new Context(newContextRecord(),pf);contexts.put(x.ref,x);
			Context share=contexts.get(a(1));
			if(share!=null){x.g.textures.putAll(share.g.textures);x.g.lists.putAll(share.g.lists);} // (a copy, not live sharing)
			aglError=AGL_NO_ERROR;ret(x.ref);
		});
		def("aglDestroyContext",(r,c)->{
			Context x=contexts.remove(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			if(x==current)current=null;
			if(x.gworld!=0)tb("DisposeGWorld",x.gworld);
			free(x.ref);ret(1);
		});
		def("aglCopyContext",(r,c)->ret(1));
		def("aglUpdateContext",(r,c)->{Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}sizeToDrawable(x);ret(1);});
		def("aglSetCurrentContext",(r,c)->{
			if(a(0)==0){current=null;ret(1);return;}
			Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			current=x;if(x.drawable!=0||x.fullScreen)sizeToDrawable(x);ret(1);
		});
		def("aglGetCurrentContext",(r,c)->ret(current==null?0:current.ref));
		def("aglSetDrawable",(r,c)->{
			Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			x.drawable=a(1);x.fullScreen=false;
			if(x.drawable!=0)sizeToDrawable(x);
			ret(1);
		});
		def("aglGetDrawable",(r,c)->{Context x=contexts.get(a(0));ret(x==null?0:x.drawable);});
		def("aglSetOffScreen",(r,c)->{aglError=AGL_BAD_OFFSCREEN;ret(0);});
		def("aglSetFullScreen",(r,c)->{
			Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			int w=a(1),h=a(2);
			if(w<=0||h<=0){int gd=tb("GetMainDevice");if(gd!=0){int dev=memory.readLong(gd);w=(short)memory.readWord(dev+40)-(short)memory.readWord(dev+36);h=(short)memory.readWord(dev+38)-(short)memory.readWord(dev+34);}}
			x.fullScreen=true;x.fsWidth=Math.max(1,w);x.fsHeight=Math.max(1,h);x.drawable=0;sizeToDrawable(x);ret(1);
		});
		def("aglSwapBuffers",(r,c)->{Context x=contexts.get(a(0));if(x!=null)present(x);});
		def("aglEnable",(r,c)->{Context x=contexts.get(a(0));if(x==null){ret(0);return;}
			if(a(1)==AGL_BUFFER_RECT){x.bufferRectOn=true;sizeToDrawable(x);}else if(a(1)==AGL_SWAP_RECT)x.swapRectOn=true;ret(1);});
		def("aglDisable",(r,c)->{Context x=contexts.get(a(0));if(x==null){ret(0);return;}
			if(a(1)==AGL_BUFFER_RECT){x.bufferRectOn=false;sizeToDrawable(x);}else if(a(1)==AGL_SWAP_RECT)x.swapRectOn=false;ret(1);});
		def("aglIsEnabled",(r,c)->{Context x=contexts.get(a(0));ret(x!=null&&(a(1)==AGL_BUFFER_RECT&&x.bufferRectOn||a(1)==AGL_SWAP_RECT&&x.swapRectOn)?1:0);});
		def("aglSetInteger",(r,c)->{
			Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			int p=a(2);
			switch(a(1)){
				case AGL_BUFFER_RECT:for(int i=0;i<4;i++)x.bufferRect[i]=memory.readLong(p+4*i);if(x.bufferRectOn)sizeToDrawable(x);break;
				case AGL_SWAP_RECT:for(int i=0;i<4;i++)x.swapRect[i]=memory.readLong(p+4*i);break;
				case AGL_SWAP_INTERVAL:x.swapInterval=memory.readLong(p);break;
				default:break;
			}
			ret(1);
		});
		def("aglGetInteger",(r,c)->{
			Context x=contexts.get(a(0));if(x==null){aglError=AGL_BAD_CONTEXT;ret(0);return;}
			int p=a(2);
			switch(a(1)){
				case AGL_BUFFER_RECT:for(int i=0;i<4;i++)memory.writeLong(p+4*i,x.bufferRect[i]);break;
				case AGL_SWAP_RECT:for(int i=0;i<4;i++)memory.writeLong(p+4*i,x.swapRect[i]);break;
				case AGL_SWAP_INTERVAL:memory.writeLong(p,x.swapInterval);break;
				default:memory.writeLong(p,0);
			}
			ret(1);
		});
		def("aglConfigure",(r,c)->ret(1));
		def("aglGetVersion",(r,c)->{if(a(0)!=0)memory.writeLong(a(0),2);if(a(1)!=0)memory.writeLong(a(1),0);});
		def("aglGetError",(r,c)->{int e=aglError;aglError=AGL_NO_ERROR;ret(e);});
		def("aglErrorString",(r,c)->{
			String s;
			switch(a(0)){case AGL_NO_ERROR:s="no error";case AGL_BAD_ATTRIBUTE:s="invalid pixel format attribute";break;case AGL_BAD_PROPERTY:s="invalid renderer property";break;
				case AGL_BAD_PIXELFMT:s="invalid pixel format";break;case AGL_BAD_RENDINFO:s="invalid renderer info";break;case AGL_BAD_CONTEXT:s="invalid context";break;
				case AGL_BAD_DRAWABLE:s="invalid drawable";break;case AGL_BAD_MATCH:s="invalid share context";break;case AGL_BAD_ALLOC:s="memory allocation failure";break;
				default:s="unknown error";}
			if(a(0)==AGL_NO_ERROR)s="no error";
			ret(string(s));
		});
		def("aglResetLibrary",(r,c)->{});
		def("aglUseFont",(r,c)->{
			Context x=contexts.get(a(0));if(x==null){ret(0);return;}
			useFont(x.g,(short)a(1),a(2)&0xff,a(3),a(4),a(5),a(6));ret(1);
		});
		def("aglSurfaceTexture",(r,c)->{});
	}

	private void defineGl(){
		// ---- errors, strings, state queries
		def("glGetError",(r,c)->{GlRenderer g=g();ret(g==null?0:g.getError());});
		def("glGetString",(r,c)->{
			switch(a(0)){case 0x1F00:ret(string(VENDOR));break;case 0x1F01:ret(string(RENDERER));break;case 0x1F02:ret(string(VERSION));break;case 0x1F03:ret(string(EXTENSIONS));break;default:ret(0);}
		});
		def("glGetIntegerv",(r,c)->{GlRenderer g=g();if(g==null)return;double[] v=get(g,a(0));if(v==null){g.error(GlRenderer.INVALID_ENUM);return;}
			boolean cl=colorLike(a(0));for(int i=0;i<v.length;i++)memory.writeLong(a(1)+4*i,cl?(int)Math.round(v[i]*2147483647.0):(int)Math.round(v[i]));});
		def("glGetFloatv",(r,c)->{GlRenderer g=g();if(g==null)return;double[] v=get(g,a(0));if(v==null){g.error(GlRenderer.INVALID_ENUM);return;}for(int i=0;i<v.length;i++)wf(a(1)+4*i,(float)v[i]);});
		def("glGetDoublev",(r,c)->{GlRenderer g=g();if(g==null)return;double[] v=get(g,a(0));if(v==null){g.error(GlRenderer.INVALID_ENUM);return;}for(int i=0;i<v.length;i++)wd(a(1)+8*i,v[i]);});
		def("glGetBooleanv",(r,c)->{GlRenderer g=g();if(g==null)return;double[] v=get(g,a(0));if(v==null){g.error(GlRenderer.INVALID_ENUM);return;}for(int i=0;i<v.length;i++)memory.writeByte(a(1)+i,v[i]!=0?1:0);});
		def("glIsEnabled",(r,c)->{GlRenderer g=g();Boolean b=g==null?null:g.isEnabled(a(0));if(b==null&&a(0)>=0x8074&&a(0)<=0x8079){Array ar=clientArray(a(0));b=ar!=null&&ar.enabled;}ret(b!=null&&b?1:0);});
		def("glGetLightfv",(r,c)->{GlRenderer g=g();float[] v=g==null?null:g.getLight(a(0),a(1));if(v!=null)for(int i=0;i<v.length;i++)wf(a(2)+4*i,v[i]);});
		def("glGetLightiv",(r,c)->{GlRenderer g=g();float[] v=g==null?null:g.getLight(a(0),a(1));if(v!=null)for(int i=0;i<v.length;i++)memory.writeLong(a(2)+4*i,(int)v[i]);});
		def("glGetMaterialfv",(r,c)->{GlRenderer g=g();float[] v=g==null?null:g.getMaterial(a(0),a(1));if(v!=null)for(int i=0;i<v.length;i++)wf(a(2)+4*i,v[i]);});
		def("glGetMaterialiv",(r,c)->{GlRenderer g=g();float[] v=g==null?null:g.getMaterial(a(0),a(1));if(v!=null)for(int i=0;i<v.length;i++)memory.writeLong(a(2)+4*i,(int)v[i]);});
		def("glGetTexEnvfv",(r,c)->{GlRenderer g=g();if(g==null)return;GlRenderer.Unit u=g.s.units[g.s.activeUnit];
			if(a(1)==0x2200)wf(a(2),u.envMode);else if(a(1)==0x2201)for(int i=0;i<4;i++)wf(a(2)+4*i,u.envColor[i]);});
		def("glGetTexEnviv",(r,c)->{GlRenderer g=g();if(g==null)return;GlRenderer.Unit u=g.s.units[g.s.activeUnit];
			if(a(1)==0x2200)memory.writeLong(a(2),u.envMode);else if(a(1)==0x2201)for(int i=0;i<4;i++)memory.writeLong(a(2)+4*i,(int)(u.envColor[i]*2147483647.0));});
		def("glGetTexParameteriv",(r,c)->{GlRenderer g=g();if(g==null)return;GlRenderer.Texture t=g.boundTexture();memory.writeLong(a(2),texParam(t,a(1)));});
		def("glGetTexParameterfv",(r,c)->{GlRenderer g=g();if(g==null)return;GlRenderer.Texture t=g.boundTexture();wf(a(2),texParam(t,a(1)));});
		def("glGetTexLevelParameteriv",(r,c)->{GlRenderer g=g();if(g==null)return;memory.writeLong(a(3),levelParam(g.boundTexture(),a(1),a(2)));});
		def("glGetTexLevelParameterfv",(r,c)->{GlRenderer g=g();if(g==null)return;wf(a(3),levelParam(g.boundTexture(),a(1),a(2)));});
		def("glGetClipPlane",(r,c)->{GlRenderer g=g();if(g==null)return;int i=a(0)-0x3000;if(i<0||i>=GlRenderer.CLIP_PLANES)return;for(int k=0;k<4;k++)wd(a(1)+8*k,g.s.clipPlanes[i][k]);});
		def("glGetPointerv",(r,c)->{Array ar=null;switch(a(0)){case 0x808E:ar=vertexArray;break;case 0x808F:ar=normalArray;break;case 0x8090:ar=colorArray;break;case 0x8092:ar=texCoordArrays[clientActiveUnit];break;case 0x8093:ar=edgeFlagArray;break;default:break;}
			memory.writeLong(a(1),ar==null?0:ar.pointer);});
		def("glHint",(r,c)->{});
		def("glFlush",(r,c)->{if(current!=null&&!current.doubleBuffered())present(current);});
		def("glFinish",(r,c)->{if(current!=null&&!current.doubleBuffered())present(current);});
		def("glDrawBuffer",(r,c)->{});
		def("glReadBuffer",(r,c)->{});
		def("glRenderMode",(r,c)->ret(0));
		def("glPixelStorei",(r,c)->pixelStore(a(0),a(1)));
		def("glPixelStoref",(r,c)->pixelStore(a(0),(int)f(0)));
		def("glPixelTransferf",(r,c)->{});def("glPixelTransferi",(r,c)->{});def("glPixelMapfv",(r,c)->{});def("glPixelMapuiv",(r,c)->{});def("glPixelMapusv",(r,c)->{});

		// ---- enables
		def("glEnable",(r,c)->{int cap=a(0);GlRenderer g=g();if(g==null)return;if(g.isEnabled(cap)==null&&!isQuietCap(cap)){g.error(GlRenderer.INVALID_ENUM);return;}cmd(gg->gg.setCapability(cap,true));});
		def("glDisable",(r,c)->{int cap=a(0);GlRenderer g=g();if(g==null)return;if(g.isEnabled(cap)==null&&!isQuietCap(cap)){g.error(GlRenderer.INVALID_ENUM);return;}cmd(gg->gg.setCapability(cap,false));});
		def("glEnableClientState",(r,c)->{Array ar=clientArray(a(0));if(ar!=null)ar.enabled=true;});
		def("glDisableClientState",(r,c)->{Array ar=clientArray(a(0));if(ar!=null)ar.enabled=false;});
		def("glPushAttrib",(r,c)->cmd(GlRenderer::pushAttrib));
		def("glPopAttrib",(r,c)->cmd(GlRenderer::popAttrib));
		def("glPushClientAttrib",(r,c)->{});def("glPopClientAttrib",(r,c)->{});

		// ---- simple state
		def("glClearColor",(r,c)->{float rr=f(0),gg=f(1),b=f(2),al=f(3);cmd(g->{float[] cc=g.s.clearColor;cc[0]=GlRenderer.clamp01(rr);cc[1]=GlRenderer.clamp01(gg);cc[2]=GlRenderer.clamp01(b);cc[3]=GlRenderer.clamp01(al);});});
		def("glClearDepth",(r,c)->{float v=f(0);cmd(g->g.s.clearDepth=v);});
		def("glClearStencil",(r,c)->{int v=a(0);cmd(g->g.s.clearStencil=v);});
		def("glClearIndex",(r,c)->{});def("glClearAccum",(r,c)->{});def("glAccum",(r,c)->{});
		def("glClear",(r,c)->{int m=a(0);cmd(g->g.clear(m));});
		def("glViewport",(r,c)->{int x=a(0),y=a(1),w=a(2),h=a(3);if(current!=null)current.viewportSet=true;cmd(g->{g.s.vpX=x;g.s.vpY=y;g.s.vpW=Math.max(0,w);g.s.vpH=Math.max(0,h);});});
		def("glDepthRange",(r,c)->{float n=GlRenderer.clamp01(f(0)),fr=GlRenderer.clamp01(f(1));cmd(g->{g.s.depthNear=n;g.s.depthFar=fr;});});
		def("glScissor",(r,c)->{int x=a(0),y=a(1),w=a(2),h=a(3);cmd(g->{g.s.scX=x;g.s.scY=y;g.s.scW=w;g.s.scH=h;});});
		def("glShadeModel",(r,c)->{int m=a(0);cmd(g->g.s.shadeModel=m);});
		def("glCullFace",(r,c)->{int m=a(0);cmd(g->g.s.cullMode=m);});
		def("glFrontFace",(r,c)->{int m=a(0);cmd(g->g.s.frontFace=m);});
		def("glPolygonMode",(r,c)->{int face=a(0),m=a(1);cmd(g->{if(face==GlRenderer.FRONT||face==GlRenderer.FRONT_AND_BACK)g.s.polygonModeFront=m;if(face==GlRenderer.BACK||face==GlRenderer.FRONT_AND_BACK)g.s.polygonModeBack=m;});});
		def("glPolygonOffset",(r,c)->{float fa=f(0),u=f(1);cmd(g->{g.s.polygonOffsetFactor=fa;g.s.polygonOffsetUnits=u;});});
		def("glDepthFunc",(r,c)->{int fn=a(0);cmd(g->g.s.depthFunc=fn);});
		def("glDepthMask",(r,c)->{boolean m=(a(0)&0xff)!=0;cmd(g->g.s.depthMask=m);});
		def("glColorMask",(r,c)->{boolean[] m={(a(0)&0xff)!=0,(a(1)&0xff)!=0,(a(2)&0xff)!=0,(a(3)&0xff)!=0};cmd(g->g.s.colorMask=m.clone());});
		def("glBlendFunc",(r,c)->{int sf=a(0),df=a(1);cmd(g->{g.s.blendSrc=sf;g.s.blendDst=df;});});
		def("glBlendEquation",(r,c)->{int e=a(0);cmd(g->g.s.blendEquation=e);});
		def("glBlendEquationEXT",(r,c)->{int e=a(0);cmd(g->g.s.blendEquation=e);});
		def("glBlendColor",(r,c)->{float[] v={f(0),f(1),f(2),f(3)};cmd(g->g.s.blendColor=v.clone());});
		def("glBlendColorEXT",(r,c)->{float[] v={f(0),f(1),f(2),f(3)};cmd(g->g.s.blendColor=v.clone());});
		def("glAlphaFunc",(r,c)->{int fn=a(0);float ref=GlRenderer.clamp01(f(0));cmd(g->{g.s.alphaFunc=fn;g.s.alphaRef=ref;});});
		def("glStencilFunc",(r,c)->{int fn=a(0),ref=a(1),m=a(2);cmd(g->{g.s.stencilFunc=fn;g.s.stencilRef=ref;g.s.stencilValueMask=m;});});
		def("glStencilOp",(r,c)->{int sf=a(0),zf=a(1),zp=a(2);cmd(g->{g.s.stencilFail=sf;g.s.stencilZFail=zf;g.s.stencilZPass=zp;});});
		def("glStencilMask",(r,c)->{int m=a(0);cmd(g->g.s.stencilWriteMask=m);});
		def("glPointSize",(r,c)->{float v=f(0);cmd(g->g.s.pointSize=v);});
		def("glLineWidth",(r,c)->{float v=f(0);cmd(g->g.s.lineWidth=v);});
		def("glPixelZoom",(r,c)->{float x=f(0),y=f(1);cmd(g->{g.s.zoomX=x;g.s.zoomY=y;});});
		def("glLineStipple",(r,c)->{});def("glPolygonStipple",(r,c)->{});def("glLogicOp",(r,c)->{});def("glIndexMask",(r,c)->{});
		def("glEdgeFlag",(r,c)->{});def("glEdgeFlagv",(r,c)->{});
		def("glPointParameterfARB",(r,c)->{});def("glPointParameterfvARB",(r,c)->{});def("glPointParameterfEXT",(r,c)->{});def("glPointParameterfvEXT",(r,c)->{});
		def("glClipPlane",(r,c)->{int pl=a(0);double[] eq=doubles(a(1),4);cmd(g->g.clipPlane(pl,eq));});

		// ---- lighting, materials, fog
		def("glLightf",(r,c)->{int li=a(0),pn=a(1);float v=f(0);cmd(g->g.light(li,pn,new float[]{v}));});
		def("glLighti",(r,c)->{int li=a(0),pn=a(1);float v=a(2);cmd(g->g.light(li,pn,new float[]{v}));});
		def("glLightfv",(r,c)->{int li=a(0),pn=a(1);float[] v=floats(a(2),lightCount(pn));cmd(g->g.light(li,pn,v));});
		def("glLightiv",(r,c)->{int li=a(0),pn=a(1);float[] v=lightInts(pn,a(2));cmd(g->g.light(li,pn,v));});
		def("glMaterialf",(r,c)->{int fa=a(0),pn=a(1);float v=f(0);cmd(g->g.material(fa,pn,new float[]{v}));});
		def("glMateriali",(r,c)->{int fa=a(0),pn=a(1);float v=a(2);cmd(g->g.material(fa,pn,new float[]{v}));});
		def("glMaterialfv",(r,c)->{int fa=a(0),pn=a(1);float[] v=floats(a(2),pn==GlRenderer.SHININESS?1:4);cmd(g->g.material(fa,pn,v));});
		def("glMaterialiv",(r,c)->{int fa=a(0),pn=a(1);float[] v=pn==GlRenderer.SHININESS?intsAsFloats(a(2),1):colorInts(a(2));cmd(g->g.material(fa,pn,v));});
		def("glLightModelf",(r,c)->{int pn=a(0);float v=f(0);cmd(g->g.lightModel(pn,new float[]{v}));});
		def("glLightModeli",(r,c)->{int pn=a(0);float v=a(1);cmd(g->g.lightModel(pn,new float[]{v}));});
		def("glLightModelfv",(r,c)->{int pn=a(0);float[] v=floats(a(1),pn==0xB53?4:1);cmd(g->g.lightModel(pn,v));});
		def("glLightModeliv",(r,c)->{int pn=a(0);float[] v=pn==0xB53?colorInts(a(1)):intsAsFloats(a(1),1);cmd(g->g.lightModel(pn,v));});
		def("glColorMaterial",(r,c)->{int fa=a(0),m=a(1);cmd(g->g.colorMaterial(fa,m));});
		def("glFogf",(r,c)->{int pn=a(0);float v=f(0);cmd(g->g.fog(pn,new float[]{v}));});
		def("glFogi",(r,c)->{int pn=a(0);float v=a(1);cmd(g->g.fog(pn,new float[]{v}));});
		def("glFogfv",(r,c)->{int pn=a(0);float[] v=floats(a(1),pn==0xB66?4:1);cmd(g->g.fog(pn,v));});
		def("glFogiv",(r,c)->{int pn=a(0);float[] v=pn==0xB66?colorInts(a(1)):intsAsFloats(a(1),1);cmd(g->g.fog(pn,v));});

		// ---- matrices
		def("glMatrixMode",(r,c)->{int m=a(0);cmd(g->g.matrixMode(m));});
		def("glLoadIdentity",(r,c)->cmd(GlRenderer::loadIdentity));
		def("glPushMatrix",(r,c)->cmd(GlRenderer::pushMatrix));
		def("glPopMatrix",(r,c)->cmd(GlRenderer::popMatrix));
		def("glLoadMatrixf",(r,c)->{double[] m=matrixF(a(0),false);cmd(g->g.loadMatrix(m));});
		def("glLoadMatrixd",(r,c)->{double[] m=doubles(a(0),16);cmd(g->g.loadMatrix(m));});
		def("glMultMatrixf",(r,c)->{double[] m=matrixF(a(0),false);cmd(g->g.multMatrix(m));});
		def("glMultMatrixd",(r,c)->{double[] m=doubles(a(0),16);cmd(g->g.multMatrix(m));});
		def("glLoadTransposeMatrixfARB",(r,c)->{double[] m=matrixF(a(0),true);cmd(g->g.loadMatrix(m));});
		def("glLoadTransposeMatrixdARB",(r,c)->{double[] m=transpose(doubles(a(0),16));cmd(g->g.loadMatrix(m));});
		def("glMultTransposeMatrixfARB",(r,c)->{double[] m=matrixF(a(0),true);cmd(g->g.multMatrix(m));});
		def("glMultTransposeMatrixdARB",(r,c)->{double[] m=transpose(doubles(a(0),16));cmd(g->g.multMatrix(m));});
		def("glTranslatef",(r,c)->{double x=f(0),y=f(1),z=f(2);cmd(g->g.translate(x,y,z));});
		def("glTranslated",(r,c)->{double x=d(0),y=d(1),z=d(2);cmd(g->g.translate(x,y,z));});
		def("glScalef",(r,c)->{double x=f(0),y=f(1),z=f(2);cmd(g->g.scale(x,y,z));});
		def("glScaled",(r,c)->{double x=d(0),y=d(1),z=d(2);cmd(g->g.scale(x,y,z));});
		def("glRotatef",(r,c)->{double an=f(0),x=f(1),y=f(2),z=f(3);cmd(g->g.rotate(an,x,y,z));});
		def("glRotated",(r,c)->{double an=d(0),x=d(1),y=d(2),z=d(3);cmd(g->g.rotate(an,x,y,z));});
		def("glFrustum",(r,c)->{double l0=d(0),r0=d(1),b=d(2),t=d(3),n=d(4),fa=d(5);cmd(g->g.frustum(l0,r0,b,t,n,fa));});
		def("glOrtho",(r,c)->{double l0=d(0),r0=d(1),b=d(2),t=d(3),n=d(4),fa=d(5);cmd(g->g.ortho(l0,r0,b,t,n,fa));});

		// ---- immediate mode
		def("glBegin",(r,c)->{int m=a(0);cmd(g->g.begin(m));});
		def("glEnd",(r,c)->cmd(GlRenderer::end));
		def("glVertex3f",(r,c)->{float x=f(0),y=f(1),z=f(2);cmd(g->g.vertex(x,y,z,1));});
		def("glVertex3fv",(r,c)->{int p=a(0);float x=rf(p),y=rf(p+4),z=rf(p+8);cmd(g->g.vertex(x,y,z,1));});
		def("glVertex2f",(r,c)->{float x=f(0),y=f(1);cmd(g->g.vertex(x,y,0,1));});
		def("glColor3f",(r,c)->{float x=f(0),y=f(1),z=f(2);cmd(g->g.color(x,y,z,1));});
		def("glColor4f",(r,c)->{float x=f(0),y=f(1),z=f(2),w=f(3);cmd(g->g.color(x,y,z,w));});
		def("glNormal3f",(r,c)->{float x=f(0),y=f(1),z=f(2);cmd(g->g.normal(x,y,z));});
		def("glNormal3fv",(r,c)->{int p=a(0);float x=rf(p),y=rf(p+4),z=rf(p+8);cmd(g->g.normal(x,y,z));});
		def("glTexCoord2f",(r,c)->{float s=f(0),t=f(1);cmd(g->g.texCoord(0,s,t,0,1));});
		def("glTexCoord2fv",(r,c)->{int p=a(0);float s=rf(p),t=rf(p+4);cmd(g->g.texCoord(0,s,t,0,1));});
		String[] types={"s","i","f","d","b","ub","us","ui"};
		for(String ty:types){
			char t=code(ty);
			if(!ty.startsWith("u")&&!ty.equals("b")){
				for(int n=2;n<=4;n++){final int k=n;
					for(boolean vec:new boolean[]{false,true}){String name="glVertex"+n+ty+(vec?"v":"");if(name.equals("glVertex3f")||name.equals("glVertex3fv")||name.equals("glVertex2f"))continue;
						def(name,(r,c)->{float[] v=values(t,k,vec,0);float x=v[0],y=v[1],z=k>2?v[2]:0,w=k>3?v[3]:1;cmd(g->g.vertex(x,y,z,w));});}
				}
				for(int n=1;n<=4;n++){final int k=n;
					for(boolean vec:new boolean[]{false,true}){String name="glTexCoord"+n+ty+(vec?"v":"");if(name.equals("glTexCoord2f")||name.equals("glTexCoord2fv"))continue;
						def(name,(r,c)->{float[] v=values(t,k,vec,0);float s=v[0],tt=k>1?v[1]:0,rr=k>2?v[2]:0,q=k>3?v[3]:1;cmd(g->g.texCoord(0,s,tt,rr,q));});
						String mname="glMultiTexCoord"+n+ty+(vec?"v":"")+"ARB";
						def(mname,(r,c)->{int unit=a(0)-0x84C0;float[] v=values(t,k,vec,1);float s=v[0],tt=k>1?v[1]:0,rr=k>2?v[2]:0,q=k>3?v[3]:1;cmd(g->g.texCoord(unit,s,tt,rr,q));});}
				}
				for(int n=2;n<=4;n++){final int k=n;
					for(boolean vec:new boolean[]{false,true}){String name="glRasterPos"+n+ty+(vec?"v":"");
						def(name,(r,c)->{float[] v=values(t,k,vec,0);float x=v[0],y=v[1],z=k>2?v[2]:0,w=k>3?v[3]:1;cmd(g->g.rasterPos(x,y,z,w));});}
				}
				for(boolean vec:new boolean[]{false,true}){String name="glRect"+ty+(vec?"v":"");
					def(name,(r,c)->{float[] v;if(vec){float[] p1=values(t,2,true,0),p2=values(t,2,true,1);v=new float[]{p1[0],p1[1],p2[0],p2[1]};}else v=values(t,4,false,0);
						float x1=v[0],y1=v[1],x2=v[2],y2=v[3];cmd(g->g.rect(x1,y1,x2,y2));});}
			}
			if(!ty.startsWith("u")){
				for(boolean vec:new boolean[]{false,true}){String name="glNormal3"+ty+(vec?"v":"");if(name.equals("glNormal3f")||name.equals("glNormal3fv"))continue;
					def(name,(r,c)->{float[] v=values(t,3,vec,0);float x=t=='f'||t=='d'?v[0]:normalize(t,v[0]),y=t=='f'||t=='d'?v[1]:normalize(t,v[1]),z=t=='f'||t=='d'?v[2]:normalize(t,v[2]);cmd(g->g.normal(x,y,z));});}
			}
			for(int n=3;n<=4;n++){final int k=n;
				for(boolean vec:new boolean[]{false,true}){String name="glColor"+n+ty+(vec?"v":"");if(name.equals("glColor3f")||name.equals("glColor4f"))continue;
					def(name,(r,c)->{float[] v=values(t,k,vec,0);float x=normalize(t,v[0]),y=normalize(t,v[1]),z=normalize(t,v[2]),w=k>3?normalize(t,v[3]):1;cmd(g->g.color(x,y,z,w));});}
			}
		}
		def("glIndexi",(r,c)->{});def("glIndexf",(r,c)->{});
		def("glActiveTextureARB",(r,c)->{int u=a(0)-0x84C0;cmd(g->g.activeTexture(u));});
		def("glClientActiveTextureARB",(r,c)->{int u=a(0)-0x84C0;if(u>=0&&u<texCoordArrays.length)clientActiveUnit=u;});

		// ---- vertex arrays
		def("glVertexPointer",(r,c)->{vertexArray.size=a(0);vertexArray.type=a(1);vertexArray.stride=a(2);vertexArray.pointer=a(3);});
		def("glNormalPointer",(r,c)->{normalArray.size=3;normalArray.type=a(0);normalArray.stride=a(1);normalArray.pointer=a(2);});
		def("glColorPointer",(r,c)->{colorArray.size=a(0);colorArray.type=a(1);colorArray.stride=a(2);colorArray.pointer=a(3);});
		def("glTexCoordPointer",(r,c)->{Array ar=texCoordArrays[clientActiveUnit];ar.size=a(0);ar.type=a(1);ar.stride=a(2);ar.pointer=a(3);});
		def("glEdgeFlagPointer",(r,c)->{edgeFlagArray.stride=a(0);edgeFlagArray.pointer=a(1);});
		def("glIndexPointer",(r,c)->{indexArray.type=a(0);indexArray.stride=a(1);indexArray.pointer=a(2);});
		def("glArrayElement",(r,c)->arrayElement(a(0)));
		def("glDrawArrays",(r,c)->{int mode=a(0),first=a(1),count=a(2);if(g()==null)return;cmd(g->g.begin(mode));for(int i=0;i<count;i++)arrayElement(first+i);cmd(GlRenderer::end);});
		def("glDrawElements",(r,c)->{int mode=a(0),count=a(1),type=a(2),p=a(3);if(g()==null)return;cmd(g->g.begin(mode));for(int i=0;i<count;i++)arrayElement(index(type,p,i));cmd(GlRenderer::end);});
		def("glDrawRangeElements",(r,c)->{int mode=a(0),count=a(3),type=a(4),p=a(5);if(g()==null)return;cmd(g->g.begin(mode));for(int i=0;i<count;i++)arrayElement(index(type,p,i));cmd(GlRenderer::end);});
		def("glLockArraysEXT",(r,c)->{});def("glUnlockArraysEXT",(r,c)->{});
		def("glInterleavedArrays",(r,c)->interleaved(a(0),a(1),a(2)));

		// ---- display lists
		def("glGenLists",(r,c)->{GlRenderer g=g();ret(g==null?0:g.genLists(a(0)));});
		def("glNewList",(r,c)->{GlRenderer g=g();if(g!=null)g.newList(a(0),a(1));});
		def("glEndList",(r,c)->{GlRenderer g=g();if(g!=null)g.endList();});
		def("glCallList",(r,c)->{int n=a(0);cmd(g->g.callList(n));});
		def("glCallLists",(r,c)->{
			int n=a(0),type=a(1),p=a(2);int[] names=new int[Math.max(0,n)];
			for(int i=0;i<n;i++){
				switch(type){
					case T_BYTE:names[i]=(byte)memory.readByte(p+i);break;case T_UBYTE:names[i]=memory.readByte(p+i)&0xff;break;
					case T_SHORT:names[i]=(short)memory.readWord(p+2*i);break;case T_USHORT:names[i]=memory.readWord(p+2*i)&0xffff;break;
					case T_FLOAT:names[i]=(int)rf(p+4*i);break;
					case 0x1407:names[i]=(memory.readByte(p+2*i)&0xff)<<8|memory.readByte(p+2*i+1)&0xff;break;
					case 0x1408:names[i]=(memory.readByte(p+3*i)&0xff)<<16|(memory.readByte(p+3*i+1)&0xff)<<8|memory.readByte(p+3*i+2)&0xff;break;
					default:names[i]=memory.readLong(p+4*i);
				}
			}
			cmd(g->{for(int nm:names)g.callList(g.listBase+nm);});
		});
		def("glListBase",(r,c)->{int b=a(0);cmd(g->g.listBase=b);});
		def("glDeleteLists",(r,c)->{GlRenderer g=g();if(g!=null)g.deleteLists(a(0),a(1));});
		def("glIsList",(r,c)->{GlRenderer g=g();ret(g!=null&&g.isList(a(0))?1:0);});

		// ---- textures
		def("glGenTextures",(r,c)->{GlRenderer g=g();if(g==null)return;int[] n=g.genTextures(a(0));for(int i=0;i<n.length;i++)memory.writeLong(a(1)+4*i,n[i]);});
		def("glDeleteTextures",(r,c)->{GlRenderer g=g();if(g==null)return;int[] n=new int[Math.max(0,a(0))];for(int i=0;i<n.length;i++)n[i]=memory.readLong(a(1)+4*i);g.deleteTextures(n);});
		def("glBindTexture",(r,c)->{int t=a(0),n=a(1);cmd(g->g.bindTexture(t,n));});
		def("glIsTexture",(r,c)->{GlRenderer g=g();ret(g!=null&&g.isTexture(a(0))?1:0);});
		def("glAreTexturesResident",(r,c)->{for(int i=0;i<a(0);i++)if(a(2)!=0)memory.writeByte(a(2)+i,1);ret(1);});
		def("glPrioritizeTextures",(r,c)->{});
		def("glTexParameteri",(r,c)->{int target=a(0),pn=a(1);float v=a(2);cmd(g->withTarget(g,target,()->g.texParameter(pn,new float[]{v})));});
		def("glTexParameterf",(r,c)->{int target=a(0),pn=a(1);float v=f(0);cmd(g->withTarget(g,target,()->g.texParameter(pn,new float[]{v})));});
		def("glTexParameterfv",(r,c)->{int target=a(0),pn=a(1);float[] v=floats(a(2),pn==0x1004?4:1);cmd(g->withTarget(g,target,()->g.texParameter(pn,v)));});
		def("glTexParameteriv",(r,c)->{int target=a(0),pn=a(1);float[] v=pn==0x1004?colorInts(a(2)):intsAsFloats(a(2),1);cmd(g->withTarget(g,target,()->g.texParameter(pn,v)));});
		def("glTexEnvf",(r,c)->{int pn=a(1);float v=f(0);cmd(g->g.texEnv(pn,new float[]{v}));});
		def("glTexEnvi",(r,c)->{int pn=a(1);float v=a(2);cmd(g->g.texEnv(pn,new float[]{v}));});
		def("glTexEnvfv",(r,c)->{int pn=a(1);float[] v=floats(a(2),pn==0x2201?4:1);cmd(g->g.texEnv(pn,v));});
		def("glTexEnviv",(r,c)->{int pn=a(1);float[] v=pn==0x2201?colorInts(a(2)):intsAsFloats(a(2),1);cmd(g->g.texEnv(pn,v));});
		def("glTexGeni",(r,c)->{int co=a(0),pn=a(1);float v=a(2);cmd(g->g.texGen(co,pn,new float[]{v}));});
		def("glTexGenf",(r,c)->{int co=a(0),pn=a(1);float v=f(0);cmd(g->g.texGen(co,pn,new float[]{v}));});
		def("glTexGend",(r,c)->{int co=a(0),pn=a(1);float v=f(0);cmd(g->g.texGen(co,pn,new float[]{v}));});
		def("glTexGenfv",(r,c)->{int co=a(0),pn=a(1);float[] v=floats(a(2),pn==0x2500?1:4);cmd(g->g.texGen(co,pn,v));});
		def("glTexGeniv",(r,c)->{int co=a(0),pn=a(1);float[] v=intsAsFloats(a(2),pn==0x2500?1:4);cmd(g->g.texGen(co,pn,v));});
		def("glTexGendv",(r,c)->{int co=a(0),pn=a(1);double[] dv=doubles(a(2),pn==0x2500?1:4);float[] v=new float[dv.length];for(int i=0;i<v.length;i++)v[i]=(float)dv[i];cmd(g->g.texGen(co,pn,v));});
		def("glTexImage2D",(r,c)->{
			// target level internalformat width height border format type pixels
			int target=a(0),level=a(1),internal=a(2),w=a(3),h=a(4),border=a(5),format=a(6),type=a(7),p=a(8);
			texImage(target,level,internal,w,h,border,format,type,p);
		});
		def("glTexImage1D",(r,c)->{int target=a(0),level=a(1),internal=a(2),w=a(3),border=a(4),format=a(5),type=a(6),p=a(7);texImage(target,level,internal,w,1+2*border,border,format,type,p);});
		def("glTexSubImage2D",(r,c)->{
			int target=a(0),level=a(1),xo=a(2),yo=a(3),w=a(4),h=a(5),format=a(6),type=a(7),p=a(8);
			int[] px=unpack(w,h,format,type,p);if(px==null)return;
			cmd(g->withTarget(g,target,()->{GlRenderer.Texture t=g.boundTexture();g.texSubImage(level,xo,yo,w,h,toTexels(px,t.format));}));
		});
		def("glTexSubImage1D",(r,c)->{
			int target=a(0),level=a(1),xo=a(2),w=a(3),format=a(4),type=a(5),p=a(6);
			int[] px=unpack(w,1,format,type,p);if(px==null)return;
			cmd(g->withTarget(g,target,()->{GlRenderer.Texture t=g.boundTexture();g.texSubImage(level,xo,0,w,1,toTexels(px,t.format));}));
		});
		def("glCopyTexImage2D",(r,c)->{
			int target=a(0),level=a(1),internal=a(2),x=a(3),y=a(4),w=a(5),h=a(6);int base=baseFormat(internal);if(base==0)base=GlRenderer.F_RGBA;final int fb=base;
			cmd(g->withTarget(g,target,()->g.texImage(level,fb,w,h,toTexels(g.readColor(x,y,w,h),fb))));
		});
		def("glCopyTexSubImage2D",(r,c)->{
			int target=a(0),level=a(1),xo=a(2),yo=a(3),x=a(4),y=a(5),w=a(6),h=a(7);
			cmd(g->withTarget(g,target,()->{GlRenderer.Texture t=g.boundTexture();g.texSubImage(level,xo,yo,w,h,toTexels(g.readColor(x,y,w,h),t.format));}));
		});
		def("glGetTexImage",(r,c)->{
			GlRenderer g=g();if(g==null)return;int target=a(0),level=a(1),format=a(2),type=a(3),p=a(4);
			GlRenderer.Unit u=g.s.units[g.s.activeUnit];int saved=u.binding2D;if(target==0xDE0)u.binding2D=u.binding1D;
			GlRenderer.Texture t=g.boundTexture();u.binding2D=saved;
			if(level<t.levels.length&&t.levels[level]!=null)pack(t.widths[level],t.heights[level],format,type,p,t.levels[level],null);
		});

		// ---- pixel rectangles
		def("glReadPixels",(r,c)->{
			GlRenderer g=g();if(g==null)return;int x=a(0),y=a(1),w=a(2),h=a(3),format=a(4),type=a(5),p=a(6);
			if(format==DEPTH_COMPONENT)pack(w,h,format,type,p,null,g.readDepth(x,y,w,h));else pack(w,h,format,type,p,g.readColor(x,y,w,h),null);
		});
		def("glDrawPixels",(r,c)->{int w=a(0),h=a(1),format=a(2),type=a(3),p=a(4);int[] px=unpack(w,h,format,type,p);if(px==null)return;cmd(g->g.drawPixels(w,h,px));});
		def("glCopyPixels",(r,c)->{int x=a(0),y=a(1),w=a(2),h=a(3);cmd(g->g.drawPixels(w,h,g.readColor(x,y,w,h)));});
		def("glBitmap",(r,c)->{
			// width height xorig yorig xmove ymove bitmap: the floats take one argument word each
			int w=a(0),h=a(1),p=a(6);float xo=f(0),yo=f(1),xm=f(2),ym=f(3);
			int rowLength=unpackRowLength>0?unpackRowLength:w;int rowBytes=((rowLength+7)/8+unpackAlignment-1)/unpackAlignment*unpackAlignment;
			byte[] bits=null;
			if(p!=0&&w>0&&h>0){bits=new byte[((w+7)/8)*h];int rb=(w+7)/8;
				for(int y=0;y<h;y++)for(int x=0;x<rb;x++){int b=memory.readByte(p+(unpackSkipRows+y)*rowBytes+x)&0xff;if(unpackLsbFirst)b=Integer.reverse(b)>>>24;bits[y*rb+x]=(byte)b;}}
			final byte[] fb=bits;final int frb=(w+7)/8;
			cmd(g->g.bitmap(w,h,xo,yo,xm,ym,fb,frb));
		});

		// ---- selection and feedback (not supported: render mode only)
		def("glSelectBuffer",(r,c)->{});def("glFeedbackBuffer",(r,c)->{});def("glInitNames",(r,c)->{});def("glLoadName",(r,c)->{});
		def("glPushName",(r,c)->{});def("glPopName",(r,c)->{});def("glPassThrough",(r,c)->{});
	}
	private static boolean isQuietCap(int cap){
		switch(cap){case 0xB10:case 0xB20:case 0xB24:case 0xB41:case 0xB42:case 0xBF1:case 0xBF2:case 0x809D:case 0x8458:case 0x806F:case 0x8513:return true;default:return cap>=0x0D90&&cap<=0x0DB8;}
	}
	private void pixelStore(int pname,int v){
		switch(pname){
			case 0x0CF5:unpackAlignment=v==1||v==2||v==4||v==8?v:unpackAlignment;break;case 0x0CF2:unpackRowLength=v;break;case 0x0CF3:unpackSkipRows=v;break;case 0x0CF4:unpackSkipPixels=v;break;
			case 0x0CF0:unpackSwap=v!=0;break;case 0x0CF1:unpackLsbFirst=v!=0;break;
			case 0x0D05:packAlignment=v==1||v==2||v==4||v==8?v:packAlignment;break;case 0x0D02:packRowLength=v;break;case 0x0D03:packSkipRows=v;break;case 0x0D04:packSkipPixels=v;break;
			case 0x0D00:packSwap=v!=0;break;
			default:break;
		}
	}
	private static int lightCount(int pname){return pname==GlRenderer.SPOT_DIRECTION?3:pname>=GlRenderer.SPOT_EXPONENT&&pname<=GlRenderer.QUADRATIC_ATTENUATION?1:4;}
	private float[] lightInts(int pname,int p) throws M68kCpuException {
		int n=lightCount(pname);
		if(pname==GlRenderer.AMBIENT||pname==GlRenderer.DIFFUSE||pname==GlRenderer.SPECULAR)return colorInts(p);
		return intsAsFloats(p,n);
	}
	/** Four integer colour components mapped to [-1, 1]. */
	private float[] colorInts(int p) throws M68kCpuException {float[] r=new float[4];for(int i=0;i<4;i++)r[i]=(float)(memory.readLong(p+4*i)/2147483647.0);return r;}
	private double[] matrixF(int p,boolean transpose) throws M68kCpuException {double[] m=new double[16];for(int i=0;i<16;i++)m[i]=rf(p+4*i);return transpose?transpose(m):m;}
	private static int texParam(GlRenderer.Texture t,int pname){
		switch(pname){case 0x2800:return t.magFilter;case 0x2801:return t.minFilter;case 0x2802:return t.wrapS;case 0x2803:return t.wrapT;case 0x8067:return 1;case 0x8191:return t.generateMipmap?1:0;default:return 0;}
	}
	private static int levelParam(GlRenderer.Texture t,int level,int pname){
		if(level<0||level>=t.levels.length||t.levels[level]==null)return 0;
		switch(pname){
			case 0x1000:return t.widths[level];case 0x1001:return t.heights[level];case 0x1003:return t.format;case 0x1005:return 0;
			case 0x805C:case 0x805D:case 0x805E:return t.format==GlRenderer.F_ALPHA?0:8; // red/green/blue size
			case 0x805F:return t.format==GlRenderer.F_RGBA||t.format==GlRenderer.F_ALPHA||t.format==GlRenderer.F_LUMINANCE_ALPHA||t.format==GlRenderer.F_INTENSITY?8:0;
			case 0x8060:return t.format==GlRenderer.F_LUMINANCE||t.format==GlRenderer.F_LUMINANCE_ALPHA?8:0;case 0x8061:return t.format==GlRenderer.F_INTENSITY?8:0;
			default:return 0;
		}
	}
	/** Runs a texture call against the 1D binding for GL_TEXTURE_1D targets (the renderer's calls use the 2D one). */
	private static void withTarget(GlRenderer g,int target,Runnable r){
		if(target!=0xDE0){r.run();return;}
		GlRenderer.Unit u=g.s.units[g.s.activeUnit];int saved=u.binding2D;u.binding2D=u.binding1D;
		try{r.run();}finally{u.binding1D=u.binding2D;u.binding2D=saved;}
	}
	private void texImage(int target,int level,int internal,int w,int h,int border,int format,int type,int p) throws M68kCpuException {
		GlRenderer g0=g();if(g0==null)return;
		if(target==0x8064||target==0x8063){return;} // proxy textures: nothing to store
		int base=baseFormat(internal);if(base==0)base=baseFormat(format);if(base==0){g0.error(GlRenderer.INVALID_ENUM);return;}
		int[] px=p==0?null:unpack(w,h,format,type,p);
		int iw=w,ih=h;
		if(border==1&&px!=null&&w>2&&h>2){px=crop(px,w,h);iw=w-2;ih=h-2;}else if(border==1){iw=Math.max(1,w-2);ih=Math.max(1,h-2);}
		final int[] texels=toTexels(px,base);final int fb=base,fw=iw,fh=ih;
		if(TRACE)System.err.printf("[gl] glTexImage level %d %dx%d internal %X format %X type %X%n",level,w,h,internal,format,type);
		cmd(g->withTarget(g,target,()->g.texImage(level,fb,fw,fh,texels)));
	}
	private void interleaved(int format,int stride,int p){
		// sizes of the parts (texture, colour, normal, vertex) and the colour type
		int tc=0,cc=0,nc=0,vc=3;int ctype=T_FLOAT;
		switch(format){
			case 0x2A20:vc=2;break;case 0x2A21:break;case 0x2A22:cc=4;ctype=T_UBYTE;vc=2;break;case 0x2A23:cc=4;ctype=T_UBYTE;break;case 0x2A24:cc=3;break;
			case 0x2A25:nc=3;break;case 0x2A26:cc=4;nc=3;break;case 0x2A27:tc=2;break;case 0x2A28:tc=4;vc=4;break;case 0x2A29:tc=2;cc=4;ctype=T_UBYTE;break;
			case 0x2A2A:tc=2;cc=3;break;case 0x2A2B:tc=2;nc=3;break;case 0x2A2C:tc=2;cc=4;nc=3;break;case 0x2A2D:tc=4;cc=4;nc=3;vc=4;break;default:return;
		}
		int csize=ctype==T_UBYTE?cc:cc*4;int off=0;
		int total=tc*4+csize+nc*4+vc*4;if(stride==0)stride=total;
		Array t=texCoordArrays[clientActiveUnit];
		t.enabled=tc>0;if(tc>0){t.size=tc;t.type=T_FLOAT;t.stride=stride;t.pointer=p+off;off+=tc*4;}
		colorArray.enabled=cc>0;if(cc>0){colorArray.size=cc;colorArray.type=ctype;colorArray.stride=stride;colorArray.pointer=p+off;off+=csize;}
		normalArray.enabled=nc>0;if(nc>0){normalArray.size=3;normalArray.type=T_FLOAT;normalArray.stride=stride;normalArray.pointer=p+off;off+=nc*4;}
		vertexArray.enabled=true;vertexArray.size=vc;vertexArray.type=T_FLOAT;vertexArray.stride=stride;vertexArray.pointer=p+off;
		edgeFlagArray.enabled=false;indexArray.enabled=false;
	}

	private void defineGlu(){
		def(UTILITY,"gluPerspective",(r,c)->{
			double fovy=d(0),aspect=d(1),n=d(2),fa=d(3);double top=n*Math.tan(Math.toRadians(fovy)/2),right=top*aspect;
			cmd(g->g.frustum(-right,right,-top,top,n,fa));
		});
		def(UTILITY,"gluOrtho2D",(r,c)->{double l0=d(0),r0=d(1),b=d(2),t=d(3);cmd(g->g.ortho(l0,r0,b,t,-1,1));});
		def(UTILITY,"gluLookAt",(r,c)->{
			double ex=d(0),ey=d(1),ez=d(2),cx=d(3),cy=d(4),cz=d(5),ux=d(6),uy=d(7),uz=d(8);
			double fx=cx-ex,fy=cy-ey,fz=cz-ez,fl=Math.sqrt(fx*fx+fy*fy+fz*fz);if(fl!=0){fx/=fl;fy/=fl;fz/=fl;}
			double sx=fy*uz-fz*uy,sy=fz*ux-fx*uz,sz=fx*uy-fy*ux,sl=Math.sqrt(sx*sx+sy*sy+sz*sz);if(sl!=0){sx/=sl;sy/=sl;sz/=sl;}
			double vx=sy*fz-sz*fy,vy=sz*fx-sx*fz,vz=sx*fy-sy*fx;
			double[] m=GlRenderer.identity();
			m[0]=sx;m[4]=sy;m[8]=sz;m[1]=vx;m[5]=vy;m[9]=vz;m[2]=-fx;m[6]=-fy;m[10]=-fz;
			cmd(g->{g.multMatrix(m);g.translate(-ex,-ey,-ez);});
		});
		def(UTILITY,"gluPickMatrix",(r,c)->{
			double x=d(0),y=d(1),dx=d(2),dy=d(3);int vp=a(8);if(dx<=0||dy<=0)return;
			int v0=memory.readLong(vp),v1=memory.readLong(vp+4),v2=memory.readLong(vp+8),v3=memory.readLong(vp+12);
			cmd(g->{g.translate((v2-2*(x-v0))/dx,(v3-2*(y-v1))/dy,0);g.scale(v2/dx,v3/dy,1);});
		});
		def(UTILITY,"gluProject",(r,c)->{
			double ox=d(0),oy=d(1),oz=d(2);double[] mv=doubles(a(6),16),pr=doubles(a(7),16);int vp=a(8);
			double[] e=mulv(mv,ox,oy,oz,1),cl=mulv(pr,e[0],e[1],e[2],e[3]);
			if(cl[3]==0){ret(0);return;}
			double nx=cl[0]/cl[3],ny=cl[1]/cl[3],nz=cl[2]/cl[3];
			wd(a(9),memory.readLong(vp)+(nx+1)*memory.readLong(vp+8)/2);wd(a(10),memory.readLong(vp+4)+(ny+1)*memory.readLong(vp+12)/2);wd(a(11),(nz+1)/2);
			ret(1);
		});
		def(UTILITY,"gluUnProject",(r,c)->{
			double wx=d(0),wy=d(1),wz=d(2);double[] mv=doubles(a(6),16),pr=doubles(a(7),16);int vp=a(8);
			double[] inv=invert(GlRenderer.mul(pr,mv));if(inv==null){ret(0);return;}
			double nx=(wx-memory.readLong(vp))*2/memory.readLong(vp+8)-1,ny=(wy-memory.readLong(vp+4))*2/memory.readLong(vp+12)-1,nz=2*wz-1;
			double[] o=mulv(inv,nx,ny,nz,1);if(o[3]==0){ret(0);return;}
			wd(a(9),o[0]/o[3]);wd(a(10),o[1]/o[3]);wd(a(11),o[2]/o[3]);ret(1);
		});
		def(UTILITY,"gluErrorString",(r,c)->{
			String s;switch(a(0)){case 0:s="no error";break;case 0x500:s="invalid enumerant";break;case 0x501:s="invalid value";break;case 0x502:s="invalid operation";break;
				case 0x503:s="stack overflow";break;case 0x504:s="stack underflow";break;case 0x505:s="out of memory";break;default:s="unknown error";}
			ret(string(s));
		});
		def(UTILITY,"gluGetString",(r,c)->ret(string(a(0)==100800?"1.2":a(0)==100801?"":"")));
		def(UTILITY,"gluScaleImage",(r,c)->{
			int format=a(0),wi=a(1),hi=a(2),ti=a(3),pi=a(4),wo=a(5),ho=a(6),to=a(7),po=a(8);
			int[] px=unpack(wi,hi,format,ti,pi);if(px==null){ret(100900);return;} // GLU_INVALID_ENUM
			pack(wo,ho,format,to,po,scale(px,wi,hi,wo,ho),null);ret(0);
		});
		def(UTILITY,"gluBuild2DMipmaps",(r,c)->{
			int target=a(0),internal=a(1),w=a(2),h=a(3),format=a(4),type=a(5),p=a(6);
			int[] px=unpack(w,h,format,type,p);if(px==null){ret(100900);return;}
			int base=baseFormat(internal);if(base==0)base=baseFormat(format);if(base==0)base=GlRenderer.F_RGBA;
			int nw=pot(w),nh=pot(h);while(nw>GlRenderer.MAX_TEXTURE_SIZE)nw>>=1;while(nh>GlRenderer.MAX_TEXTURE_SIZE)nh>>=1;
			int[] lv=nw==w&&nh==h?px:scale(px,w,h,nw,nh);
			final int fb=base;final int[] level0=toTexels(lv,base);final int fw=nw,fh=nh;
			cmd(g->withTarget(g,target,()->{g.texImage(0,fb,fw,fh,level0);g.boundTexture().buildMipmaps();}));
			ret(0);
		});
		def(UTILITY,"gluBuild1DMipmaps",(r,c)->{
			int target=a(0),internal=a(1),w=a(2),format=a(3),type=a(4),p=a(5);
			int[] px=unpack(w,1,format,type,p);if(px==null){ret(100900);return;}
			int base=baseFormat(internal);if(base==0)base=GlRenderer.F_RGBA;int nw=Math.min(GlRenderer.MAX_TEXTURE_SIZE,pot(w));
			final int fb=base;final int[] l0=toTexels(nw==w?px:scale(px,w,1,nw,1),base);final int fw=nw;
			cmd(g->withTarget(g,target,()->{g.texImage(0,fb,fw,1,l0);g.boundTexture().buildMipmaps();}));ret(0);
		});
		def(UTILITY,"gluNewQuadric",(r,c)->{int q=rt.allocate(16,4);quadrics.put(q,new Quadric());ret(q);});
		def(UTILITY,"gluDeleteQuadric",(r,c)->{if(quadrics.remove(a(0))!=null)free(a(0));});
		def(UTILITY,"gluQuadricDrawStyle",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null)q.drawStyle=a(1);});
		def(UTILITY,"gluQuadricNormals",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null)q.normals=a(1);});
		def(UTILITY,"gluQuadricOrientation",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null)q.orientation=a(1);});
		def(UTILITY,"gluQuadricTexture",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null)q.texture=(a(1)&0xff)!=0;});
		def(UTILITY,"gluQuadricCallback",(r,c)->{});
		// gluSphere(quad, radius, slices, stacks): the double takes argument words 1-2
		def(UTILITY,"gluSphere",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null&&g()!=null)sphere(q,d(0),a(3),a(4));});
		def(UTILITY,"gluCylinder",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null&&g()!=null)cylinder(q,d(0),d(1),d(2),a(7),a(8));});
		def(UTILITY,"gluDisk",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null&&g()!=null)disk(q,d(0),d(1),a(5),a(6),0,360,false);});
		def(UTILITY,"gluPartialDisk",(r,c)->{Quadric q=quadrics.get(a(0));if(q!=null&&g()!=null)disk(q,d(0),d(1),a(5),a(6),d(2),d(3),true);});
	}
	private static int pot(int v){int p=1;while(p<v)p<<=1;if(p>v&&p-v>v-p/2)p>>=1;return Math.max(1,p);}
	private static double[] mulv(double[] m,double x,double y,double z,double w){
		return new double[]{m[0]*x+m[4]*y+m[8]*z+m[12]*w,m[1]*x+m[5]*y+m[9]*z+m[13]*w,m[2]*x+m[6]*y+m[10]*z+m[14]*w,m[3]*x+m[7]*y+m[11]*z+m[15]*w};
	}
	private static double[] invert(double[] m){
		// Gauss-Jordan
		double[][] a=new double[4][8];
		for(int r=0;r<4;r++){for(int c=0;c<4;c++)a[r][c]=m[c*4+r];a[r][4+r]=1;}
		for(int c=0;c<4;c++){
			int piv=c;for(int r=c+1;r<4;r++)if(Math.abs(a[r][c])>Math.abs(a[piv][c]))piv=r;
			if(Math.abs(a[piv][c])<1e-300)return null;
			double[] t=a[c];a[c]=a[piv];a[piv]=t;
			double d=a[c][c];for(int k=0;k<8;k++)a[c][k]/=d;
			for(int r=0;r<4;r++)if(r!=c){double f=a[r][c];if(f!=0)for(int k=0;k<8;k++)a[r][k]-=f*a[c][k];}
		}
		double[] inv=new double[16];for(int r=0;r<4;r++)for(int c=0;c<4;c++)inv[c*4+r]=a[r][4+c];
		return inv;
	}
}
