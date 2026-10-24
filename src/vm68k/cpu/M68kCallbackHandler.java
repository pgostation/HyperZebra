interface M68kCallbackHandler {
	/** Returns true when the HyperCard callback request was handled. */
	boolean handle(int request) throws M68kException;
	/** The same for a request made through {@code block} (the XCmdBlock the external was called with; see M68kCpu.callExternal). */
	default boolean handle(int request,int block) throws M68kException {return handle(request);}
}
