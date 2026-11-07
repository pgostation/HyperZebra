/** Register-based subset of the classic Macintosh Toolbox Utilities. */
final class ToolUtils68k {
	private final M68kMemory memory;

	ToolUtils68k(M68kMemory memory) {this.memory=memory;}

	/** Implements the register-based _RelString trap ($A050). */
	boolean relString(M68kCpu cpu,int opcode) throws M68kCpuException {
		int firstLength=cpu.d[0]>>>16,secondLength=cpu.d[0]&0xffff;
		String first=macRoman(cpu.a[0],firstLength),second=macRoman(cpu.a[1],secondLength);
		boolean ignoreMarks=(opcode&0x0200)!=0,caseSensitive=(opcode&0x0400)!=0;
		if(ignoreMarks){first=stripMarks(first);second=stripMarks(second);}
		if(!caseSensitive){first=first.toUpperCase(java.util.Locale.ROOT);second=second.toUpperCase(java.util.Locale.ROOT);}
		cpu.d[0]=Integer.compare(first.compareTo(second),0);return true;
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap) {
			// Toolbox Utilities bit/word routines are stack-based Pascal functions (IM I: Toolbox Utilities): the caller
			// reserves the result (CLR.L/CLR.W -(SP)) before pushing the arguments, so the result goes where the arguments
			// were popped from, and SP ends up exactly at the result. (They used to push the result instead, which left SP
			// one result short per call: HyperCard's style-menu loop calls BitTst and then passes the result word to a
			// routine with more arguments underneath, and every iteration shifted that routine's frame by a word.)
			case 0x858:{long b=popLong(cpu),a=popLong(cpu);memory.writeLong(cpu.a[7],(int)(a&b));return true;}   // BitAnd
			case 0x859:{long b=popLong(cpu),a=popLong(cpu);memory.writeLong(cpu.a[7],(int)(a^b));return true;}   // BitXor
			case 0x85a:{long a=popLong(cpu);memory.writeLong(cpu.a[7],(int)~a);return true;}                     // BitNot
			case 0x85b:{long b=popLong(cpu),a=popLong(cpu);memory.writeLong(cpu.a[7],(int)(a|b));return true;}   // BitOr
			case 0x85c:{int count=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;int value=(int)popLong(cpu);memory.writeLong(cpu.a[7],bitShift(value,count));return true;}
			case 0x85d:{int bit=(int)popLong(cpu),address=(int)popLong(cpu);memory.writeWord(cpu.a[7],bitMemory(bit,address,0)!=0?0x0100:0);return true;} // BitTst: Boolean
			case 0x85e:{int bit=(int)popLong(cpu),address=(int)popLong(cpu);bitMemory(bit,address,1);return true;}
			case 0x85f:{int bit=(int)popLong(cpu),address=(int)popLong(cpu);bitMemory(bit,address,2);return true;}
			case 0x86a:{int x=(int)popLong(cpu);memory.writeWord(cpu.a[7],x>>>16);return true;} // HiWord
			case 0x86b:{int x=(int)popLong(cpu);memory.writeWord(cpu.a[7],x);return true;}      // LoWord
			case 0x9ee:return pack7(cpu);
			default:return false;
		}
	}

	/** Pack7 consumes a word selector: 0 is NumToString and 1 is StringToNum (register based); 2 PStr2Dec, 3 Dec2Str
	 *  and 4 CStr2Dec are the SANE decimal-string conversions (stack based; the SANE.p glue numbers - HyperCard prints
	 *  every non-integer result through selector 3, which used to be taken for PStr2Dec and left the text empty). */
	private boolean pack7(M68kCpu cpu) throws M68kCpuException {
		int selector=memory.readWord(cpu.a[7]);
		if(selector<0||selector>4)return false;
		cpu.a[7]+=2;
		switch(selector){
			case 0:return numToString(cpu);
			case 1:return stringToNum(cpu);
			case 2:case 4:return stringToDecimal(cpu,selector==2);
			default:return decimalToString(cpu);
		}
	}

	/** PStr2Dec/CStr2Dec(s, VAR index, VAR d: Decimal, VAR validPrefix): scans a decimal number (sign, digits, point,
	 *  exponent) from s starting at index and leaves index after it. HyperCard reads numbers out of text with this. A Pascal
	 *  string is scanned by byte position, the length byte being position 0: FreeHand 3.11 hands its C strings to Str2Dec
	 *  with index 0, so the scan starts at the length byte - the first digit ("6" of the Snap-to-grid field) - and stops at
	 *  the NUL; skipping it read "Value out of range" for every Document setup value. */
	private boolean stringToDecimal(M68kCpu cpu,boolean pascal) throws M68kCpuException {
		int sp=cpu.a[7],validPtr=memory.readLong(sp),decimal=memory.readLong(sp+4),indexPtr=memory.readLong(sp+8),text=memory.readLong(sp+12);cpu.a[7]+=16;
		int index=(short)memory.readWord(indexPtr);StringBuilder str=new StringBuilder();
		if(pascal){int n=memory.readByte(text);for(int i=0;i<=n;i++)str.append((char)memory.readByte(text+i));}
		else{for(int i=0;i<255;i++){int c=memory.readByte(text+i);if(c==0)break;str.append((char)c);}}
		int i=Math.max(0,index),n=str.length();
		while(i<n&&(str.charAt(i)==' '||str.charAt(i)=='\t'))i++;
		boolean negative=false;if(i<n&&(str.charAt(i)=='+'||str.charAt(i)=='-')){negative=str.charAt(i)=='-';i++;}
		StringBuilder digits=new StringBuilder();int exp=0,intDigits=0,fracDigits=0;
		while(i<n&&Character.isDigit(str.charAt(i))){digits.append(str.charAt(i));i++;intDigits++;}
		if(i<n&&str.charAt(i)=='.'){int j=i+1;while(j<n&&Character.isDigit(str.charAt(j))){digits.append(str.charAt(j));j++;fracDigits++;}if(intDigits+fracDigits>0)i=j;}
		boolean any=intDigits+fracDigits>0;
		if(any&&i<n&&(str.charAt(i)=='e'||str.charAt(i)=='E')){int j=i+1;boolean eneg=false;if(j<n&&(str.charAt(j)=='+'||str.charAt(j)=='-')){eneg=str.charAt(j)=='-';j++;}
			int e=0,k=j;while(k<n&&Character.isDigit(str.charAt(k))){e=e*10+(str.charAt(k)-'0');k++;}if(k>j){exp=eneg?-e:e;i=k;}}
		String sig=digits.toString().replaceFirst("^0+(?=\\d)","");if(sig.isEmpty())sig="0";
		exp-=fracDigits;if(sig.equals("0"))exp=0;
		if(sig.length()>19){exp+=sig.length()-19;sig=sig.substring(0,19);}
		memory.writeByte(decimal,negative?1:0);memory.writeByte(decimal+1,0);memory.writeWord(decimal+2,exp);
		memory.writeByte(decimal+4,sig.length());for(int k=0;k<sig.length();k++)memory.writeByte(decimal+5+k,sig.charAt(k));
		memory.writeWord(indexPtr,i);
		memory.writeByte(validPtr,any&&i>=n?1:0);
		if(Mac68kApi.TRACE)System.err.println("[68K] "+(pascal?"PStr2Dec":"CStr2Dec")+" index "+index+" -> sig "+sig+" exp "+exp+" index "+i+" validPrefix "+(any&&i>=n));
		return true;
	}
	/** Dec2Str(f: DecForm; d: Decimal; VAR s: DecStr): fixed notation with f.digits fraction digits, or floating
	 *  notation with f.digits significant digits ("1.25e+3"). */
	private boolean decimalToString(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],result=memory.readLong(sp),decimal=memory.readLong(sp+4),form=memory.readLong(sp+8);cpu.a[7]+=12;
		int style=memory.readByte(form),digits=(short)memory.readWord(form+2);
		int sign=memory.readByte(decimal),exp=(short)memory.readWord(decimal+2),n=memory.readByte(decimal+4);
		StringBuilder sig=new StringBuilder();for(int i=0;i<n;i++)sig.append((char)memory.readByte(decimal+5+i));
		String s;
		if(sig.length()>0&&sig.charAt(0)=='N')s="NAN";else if(sig.length()>0&&sig.charAt(0)=='I')s="INF";
		else{java.math.BigDecimal v=new java.math.BigDecimal(new java.math.BigInteger(sig.length()==0?"0":sig.toString()),-exp);
			if(style==0){java.math.BigDecimal r=v.round(new java.math.MathContext(Math.max(1,digits),java.math.RoundingMode.HALF_EVEN));
				String u=r.unscaledValue().abs().toString();int e=u.length()-1-r.scale();
				s=u.length()>1?u.charAt(0)+"."+u.substring(1)+"e"+(e<0?"-":"+")+Math.abs(e):u+"e"+(e<0?"-":"+")+Math.abs(e);}
			else s=v.setScale(Math.max(0,digits),java.math.RoundingMode.HALF_EVEN).toPlainString();}
		if(sign!=0)s="-"+s;
		memory.writeByte(result,Math.min(255,s.length()));for(int i=0;i<Math.min(255,s.length());i++)memory.writeByte(result+1+i,s.charAt(i));
		return true;
	}

	private long popLong(M68kCpu cpu) throws M68kCpuException {long v=memory.readLong(cpu.a[7])&0xffffffffL;cpu.a[7]+=4;return v;}
	private void pushLong(M68kCpu cpu,int v) throws M68kCpuException {cpu.a[7]-=4;memory.writeLong(cpu.a[7],v);}

	private static int bitShift(int value,int count) {
		int distance=Math.abs(count)%32;
		return count>0?value<<distance:count<0?value>>>distance:value;
	}

	private int bitMemory(int bit,int base,int operation) throws M68kCpuException {
		int address=base+(bit>>>3),mask=0x80>>>(bit&7);
		int value=memory.readByte(address);if(operation==0)return (value&mask)!=0?1:0;
		if(operation==1)memory.writeByte(address,value|mask);else memory.writeByte(address,value&~mask);return 0;
	}

	private boolean numToString(M68kCpu cpu) throws M68kCpuException {
		if(cpu.a[0]==0)return true;String value=Integer.toString(cpu.d[0]);memory.writeByte(cpu.a[0],value.length());
		for(int i=0;i<value.length();i++)memory.writeByte(cpu.a[0]+1+i,value.charAt(i));return true;
	}

	/** StringToNum checks no digits: each character after a leading sign counts as its low four bits (Inside Macintosh I-490:
	 *  "2:" is 30, a space is 0). CalendarPallete splits "9999, 1" at the comma and converts " 1" with it. */
	private boolean stringToNum(M68kCpu cpu) throws M68kCpuException {
		if(cpu.a[0]==0){cpu.d[0]=0;return true;}int length=memory.readByte(cpu.a[0]),index=0;boolean negative=false;
		if(length>0){int sign=memory.readByte(cpu.a[0]+1);if(sign=='+'||sign=='-'){negative=sign=='-';index++;}}
		long value=0;for(;index<length;index++){int digit=memory.readByte(cpu.a[0]+1+index)&0x0f;value=(value*10+digit)&0xffffffffL;}
		cpu.d[0]=(int)(negative?-value:value);return true;
	}

	private String macRoman(int address,int length) throws M68kCpuException {byte[] bytes=new byte[length];for(int i=0;i<length;i++)bytes[i]=(byte)memory.readByte(address+i);return new String(bytes,java.nio.charset.Charset.forName("x-MacRoman"));}
	private static String stripMarks(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");}

	static String name(int trap) {
		switch(trap) {
			case 0x858:return "BitAnd";case 0x859:return "BitXor";case 0x85a:return "BitNot";case 0x85b:return "BitOr";
			case 0x85c:return "BitShift";case 0x85d:return "BitTst";case 0x85e:return "BitSet";case 0x85f:return "BitClr";
			case 0x86a:return "HiWord";case 0x86b:return "LoWord";case 0x9ee:return "Pack7 (NumToString/StringToNum)";
			default:return null;
		}
	}
}
