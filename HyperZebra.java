

public class HyperZebra{

	public static void main(final String[] args) {
		if(GMenu.isMacOSX()){
			//Property
			System.setProperty("com.apple.mrj.application.apple.menu.about.name",
					PCARD.AppName);//about box
			
			System.setProperty("apple.laf.useScreenMenuBar", "true");
			System.setProperty("com.apple.macos.useScreenMenuBar", "true");
			//System.setProperty("com.apple.macos.smallTabs", "true");

			System.setProperty("apple.awt.rendering","speed");
			System.setProperty("apple.awt.graphics.UseQuartz","false");
		}

		PCARD.main(args);
    }

    //@SuppressWarnings({ "deprecation", "restriction" })
	static
    void installMacHandler(){
		/*if(GMenu.isMacOSX()){
			try{
			//Mac Application Menu
			com.apple.eawt.Application fApplication = com.apple.eawt.Application.getApplication();
			fApplication.setEnabledPreferencesMenu(true);
			fApplication.addApplicationListener(
				new com.apple.eawt.ApplicationAdapter() {
			
					@Override
					public void handleAbout(com.apple.eawt.ApplicationEvent e) {
					//showAbout();
						//new GDialog(null, AppName+" "+PCARD.longVersion,
						//		null,"OK",null,null);
						try {
							TTalk.doScriptforMenu("about this");
						} catch (xTalkException e2) {
							e2.printStackTrace();
						}
					e.setHandled(true);
					}
			
					@Override
					public void handleOpenApplication(
					com.apple.eawt.ApplicationEvent e) {
						//new GDialog(null, "handleOpenApplication"+e.getFilename(),
								//null,"OK",null,null);
					}
			
					@Override
					public void handleOpenFile(com.apple.eawt.ApplicationEvent e) {
						try {
							TTalk.doScriptforMenu("open stack "+"\""+e.getFilename()+"\"");
						} catch (xTalkException e1) {
							try {
								TTalk.doScriptforMenu("edit picture "+"\""+e.getFilename()+"\"");
							} catch (xTalkException e2) {
								e2.printStackTrace();
							}
						}
					}
			
					@Override
					public void handlePreferences(
					com.apple.eawt.ApplicationEvent e) {
					//doPreference();
						//new GDialog(null, "handlePreferences",
								//null,"OK",null,null);
					}
			
					@Override
					public void handlePrintFile(
					com.apple.eawt.ApplicationEvent e) {
						new GDialog(null, "handlePrintFile",
								null,"OK",null,null);
					}
			
					@Override
					public void handleQuit(com.apple.eawt.ApplicationEvent e) {
						try {
							GMenuBrowse.doMenu("Quit HyperCard");
						} catch (xTalkException e1) {
							e1.printStackTrace();
						}
						//processExit();
					}
				}
			);
			}catch(Exception e){
				
			}
		}*/
    }
}