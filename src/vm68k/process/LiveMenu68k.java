/** A custom MDEF asked on demand (emulation thread): the item at a point of its picture - its mChooseMsg, which also
 *  draws its own highlight - and the picture as it now looks. */
interface LiveMenu68k { int choose(int x,int y) throws M68kException; java.awt.image.BufferedImage image(); }
