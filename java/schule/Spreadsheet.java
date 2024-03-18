package schule;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

public class Spreadsheet {
    public static final int DIGITS=3;
    public static final int ROWS=16;
    public static final int COLS=ROWS;
    private Optional<Expr>[][] formulae = new Optional[ROWS][COLS];
    public Optional<Integer>[][] values = new Optional[ROWS][COLS];
    /**
     * Gives the optionally empty raw Formula for [row/col] back, e.g. A1*5+B3
     * @param row
     * @param col
     * @return
     */
    public Optional<Expr> getFormula(int row, int col){
        return formulae[row][col];
    }
    public void setFormula(int row,int col,Optional<Expr> form) { 
        formulae[row][col]=form; 
    }
    private int cursorcol=0,cursorrow=0;
    private Map<String,Function<List<Integer>,Integer>> funcRegistry = new HashMap<>();
    public Function<List<Integer>,Integer> lookupFunction(String name){
        return funcRegistry.get(name.toUpperCase());
    }
    public Spreadsheet(){
        for (int i=0;i<ROWS;i++) 
            for (int j=0;j<COLS;j++)
                setFormula(i, j, Optional.empty());
        funcRegistry.put("MAX",x -> Math.max(x.get(0),x.get(1)));
    }
    /**
     * To start evaluation anew, we need to purge all old cached values from values[][]
     */
    private void purgeValues(){
        values = new Optional[ROWS][COLS];
    }
    /**
     *  print out the frame for a Cell
     */
    private static void drawCellToConsole(TextGraphics textGraphics, int row ,int col , String txt) {
        col++;
        int x0 = col * (3+DIGITS);
        int y0 = row * 2;
        int x1 = x0 + (3+DIGITS);
        int y1 = y0 + 2;
        textGraphics.drawRectangle(new TerminalPosition(x0, y0), new TerminalSize(4+DIGITS, 3), (char)0x2500); //-
        textGraphics.drawLine(x0,y0,x0,y1,(char)0x2502); // |
        textGraphics.drawLine(x1,y0,x1,y1,(char)0x2502); // |
        textGraphics.setCharacter(x0,y0,(char)0x253C);   // +
        textGraphics.setCharacter(x0,y1,(char)0x253C);   // +
        textGraphics.setCharacter(x1,y0,(char)0x253C);   // +
        textGraphics.setCharacter(x1,y1,(char)0x253C);   // +
        textGraphics.putString(x0+1,y0+1," "+txt+" ");
    }

    /**
     *  print out the actual content of the cell, and delegate to drawCellToConsole for the frame 
     */
    private void drawDataCellToConsole(TextGraphics textgraphics, int row,int col){
        var str = "   ";
        if (!getFormula(row,col).isEmpty())  {
            try {
                str=String.format("%"+DIGITS+"d",eval(row,col));
            }catch(Exception e){
                str="### "+e.getMessage();
            }
        }
        drawCellToConsole(textgraphics, row+1,col, str);
    }
    /**
     *  prints the whole array of cells to the console, and evens out some borderframes
     */
    public String printToConsole(Screen screen, TextGraphics textGraphics) throws IOException, InterruptedException {
        screen.clear();
        IntStream.range(0,COLS).forEachOrdered(cols ->
            drawCellToConsole(textGraphics, 0, cols, " "+(char)(cols+'A'))
            );
        for (int cols = 0; cols < COLS;cols++)
            for (int rows=0; rows< ROWS; rows++)
                drawDataCellToConsole(textGraphics, rows,cols);
        for (int j=0; j< COLS+1; j++) { // Finalize Border lines
            textGraphics.setCharacter(6,j*2,(char)0x251C);              // ├
            textGraphics.setCharacter(6*(COLS+1),j*2,(char)0x2524);     // ┤
            textGraphics.setCharacter(6*(j+1),0,(char)0x252C);          // ┬
            textGraphics.setCharacter(6*(j+1),(ROWS+1)*2,(char)0x2534); // ┴
            textGraphics.putString(0,1+(j)*2," "+j);
        }
        // red frame around the active cell:
        textGraphics.setForegroundColor(TextColor.ANSI.RED);
        drawDataCellToConsole(textGraphics,cursorrow,cursorcol);
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        // little prompt at the bottom line:
        var cell = "Current Cell "+((char)(cursorcol+'A'))+(cursorrow+1)+": ";
        var raw = getFormula(cursorrow,cursorcol);
        var bo = new ByteArrayOutputStream();
        raw.ifPresent(x -> x.replicateTo(new PrintStream(bo)));
        cell+="="+bo.toString();
        textGraphics.putString(1,2*(ROWS+2),cell);
        // cursor to indicate readyness for new input
        screen.setCursorPosition(new TerminalPosition(1+cell.length(), 2*(ROWS+2)));
        return bo.toString();
    }

    public void writeToCSV(String filename) throws IOException {
        var pw = new PrintWriter(new File(filename));
        for (var r:formulae){
            pw.println(Arrays.stream(r)
                .filter(x->x.isPresent())
                .map(x->"="+x.get().toString())
                .collect(Collectors.joining(";")));
        }
        pw.close();
    }
    /**
     * evaluate the expression at position Row / Col, caching its value in value[][] as sideeffect; this is the entry point into an evaluation, starting without any already touched references
     * @param row
     * @param col
     * @return
     * @throws Exception
     */
    public int eval(int row, int col) throws Exception {
        return eval(row,col,new HashSet<Expr>());
    }
    /**
     * evaluate the expression at position Row / Col, caching its value in value[][] as sideeffect; this version explicitely starts with an initial set of already touched references
     * @param row
     * @param col
     * @param refset set of so far evaluated references i.o. to detect cycles
     * @return final integer value
     * @throws Exception
     */
    public int eval(int row, int col,Set<Expr> refset) throws Exception {
        if (values[row][col]==null) {
            if (!getFormula(row,col).isPresent())
                values[row][col]=Optional.empty();
            else {
                var e = getFormula(row,col).get();
                var hs = new HashSet<Expr>(refset);
                if (refset.contains(e)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    e.replicateTo(new PrintStream(bo));
                    var str = refset.stream()
                        .map(x->x.toString())
                        .collect(Collectors.joining(","));
                    throw new Exception("Circular evaluation during evaluation of "+bo.toString()+" : "+str);
                }
                hs.add(e);
                // caches evaluated formula[x][y] in value[x][y]
                values[row][col]=Optional.of(e.eval(this, hs));
            }
        }
        return values[row][col].get();
    }
    /**
     * Takes a string representation of a spreadsheet formula or value (e.g. =5*A1+B5 or 42) and returns a literal formula object
     * @param cell
     * @return
     */
    public static Optional<Expr> parseCell(String cell){
        if (cell.isEmpty()) return Optional.empty();
        else {
            if (cell.startsWith("=")) {
                try {
                    return Optional.of(Expr.parse(cell.substring(1)));
                }catch(Expr.Parser.Fail f){
                    return Optional.empty();
                }
            }
            else {
                return Optional.of(new Expr.Const(Integer.parseInt(cell)));
            }
        }
    }
    public static Spreadsheet parseCSV(String filename) throws FileNotFoundException,IOException,NumberFormatException {
        var result = new Spreadsheet();
        var buffy = new BufferedReader(new FileReader(filename));
        var row =0;
        while (buffy.ready() && row < result.ROWS) {
            var scanner = new Scanner(buffy.readLine()).useDelimiter(";");
            var col = 0;
            while(scanner.hasNext()){
                result.setFormula(row,col++,parseCell(scanner.next()));        
            }
            row++;
            scanner.close();
        }
        buffy.close();
        return result;
    }
    public static void main(String[] args) throws Exception{
        var e = parseCSV(args[0]);
        var terminal = new DefaultTerminalFactory().createTerminal();
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var textGraphics = screen.newTextGraphics();
        KeyStroke key;
        final WindowBasedTextGUI textGUI = new MultiWindowTextGUI(screen);
        do {
            var content = e.printToConsole(screen,textGraphics);
            screen.refresh();
            key=screen.readInput();
            switch (key.getKeyType()) {
                case ArrowDown:  e.cursorrow=(16+e.cursorrow+1)%16; break;
                case ArrowUp:    e.cursorrow=(16+e.cursorrow-1)%16; break;
                case ArrowLeft:  e.cursorcol=(16+e.cursorcol-1)%16; break;
                case ArrowRight: e.cursorcol=(16+e.cursorcol+1)%16; break;
                case Enter: 
                      String input = TextInputDialog.showDialog(textGUI,"Content for Cell","edit the content of Cell ","="+content);
                      e.setFormula(e.cursorrow,e.cursorcol,parseCell(input));
                      e.purgeValues();                     
                      break;
                default:
            }
        }
        while (key.getKeyType()!=KeyType.Escape);
        screen.stopScreen();
        screen.close();
        e.writeToCSV("out.csv");
        System.out.println(e);
   }
   @Override
   public String toString() {
       return Arrays.stream(values)
               .map(line -> Optional.ofNullable(line)
                       .map(line2 -> Arrays.stream(line2)
                               .map(c -> c.map(cc -> String.format("%" + 3 + "d", cc))
                               .orElse("   "))
                               .collect(Collectors.joining(" - ")))
                       .orElse(""))
               .collect(Collectors.joining("\n"));
   }
}  