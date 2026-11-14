import java.io.File;

/** HostXattr68k on the desktop: macOS's xattr tool, a process per call, as FinderInfo68k ran it before P4. */
final class XattrTool68k extends HostXattr68k {
	XattrTool68k() {}
	@Override boolean isMac() { return GMenu.isMacOSX(); }
	@Override byte[] get(File file, String name) {
		try{Process p=new ProcessBuilder("xattr","-px",name,file.getPath()).redirectErrorStream(true).start();
			String out=new String(p.getInputStream().readAllBytes(),"US-ASCII");p.waitFor();
			String hex=out.replaceAll("[^0-9A-Fa-f]","");if(p.exitValue()!=0)return null;
			byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return b;}
		catch(Exception e){return null;}
	}
	@Override boolean set(File file, String name, byte[] value) {
		try{StringBuilder hex=new StringBuilder();for(byte b:value)hex.append(String.format("%02X",b&0xff));
			Process p=new ProcessBuilder("xattr","-wx",name,hex.toString(),file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();p.waitFor();return p.exitValue()==0;}
		catch(Exception e){return false;}
	}
	@Override boolean delete(File file, String name) {
		try{Process p=new ProcessBuilder("xattr","-d",name,file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();p.waitFor();return p.exitValue()==0;}
		catch(Exception e){return false;}
	}
	@Override boolean ownerReadOnly(File file) {
		try { java.nio.file.attribute.PosixFileAttributes a = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.PosixFileAttributes.class);
			return !a.permissions().contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE) && a.owner().getName().equals(System.getProperty("user.name")); }
		catch (java.io.IOException | UnsupportedOperationException | SecurityException e) { return false; }
	}
	@Override boolean finderLock(File file, boolean locked) {
		try { Process p = new ProcessBuilder("/usr/bin/chflags", locked ? "uchg" : "nouchg", file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes(); if (p.waitFor() == 0) return true; }
		catch (java.io.IOException e) {} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		return false;
	}
	@Override boolean birthTime(File file, long millis) {
		try { java.nio.file.Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.BasicFileAttributeView.class).setTimes(null, null, java.nio.file.attribute.FileTime.fromMillis(millis)); return true; }
		catch (java.io.IOException | UnsupportedOperationException | SecurityException e) { return false; }
	}
}
