package schule;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class Expr {
    interface Context {
        default int eval(int row,int col,Set<Expr> evalset) throws Exception {
            return 0;
        };
        default Function<List<Integer>,Integer> lookupFunction(String name) {
            return null;
        };
        default int lookupVariable(String name) {
            return 0;
        };

    }
    public int eval(Context sheet)throws Exception {
        return eval(sheet,new HashSet<Expr>());
    }
    /**
     * evaluate this (sub)expression, considering evalset as already evaluated cells (i.o. to detect cyclic evals)
     * @param sheet
     * @param evalset
     * @return
     * @throws Exception
     */
    public abstract int eval(Context sheet,Set<Expr> evalset) throws Exception;    
    /**
     * puts a String representation of this expression as a formula on the stream
     * @param stream
     */
    public abstract void replicateTo(PrintStream stream);
    public static class Const extends Expr{
        int value;
        @Override
        public int eval(Context sheet,Set<Expr> evalset) {
            return value;
        }
        public Const(int val){
            value=val;
        }
        @Override
        public void replicateTo(PrintStream stream) {
            stream.print(value);
        }
        @Override
        public String toString() {
            return ""+value;
        }
    }
    public static class Var extends Expr {
        String name;
        @Override
        public int eval(Context sheet,Set<Expr> evalset) {
            return sheet.lookupVariable(name);
        }
        public Var(String name){
            this.name=name;
        }
        @Override
        public void replicateTo(PrintStream stream) {
            stream.print(name);
        }
        @Override
        public String toString() {
            return name;
        }
    }
    public static class Ref extends Expr {
        int col,row;
        @Override
        public int eval(Context sheet,Set<Expr> evalset) throws Exception{
            return sheet.eval(row,col,evalset);
        }
        public Ref(String ref){
            this((ref.charAt(0)) - 'A',Integer.parseInt(ref.substring(1))-1);
        }
        private Ref(int col,int row) { this.col=col;this.row=row;}
        @Override
        public void replicateTo(PrintStream stream) {
            stream.print(""+((char)(col+'A'))+(row+1));
        }
        @Override
        public String toString() {
            return ""+(char)('A'+col)+(row+1);
        }
    }
    public static class BinEx extends Expr {
        Expr l,r;
        String op;
        @Override
        public int eval(Context sheet,Set<Expr> evalset) throws Exception {
            switch (op){
                case "+": return l.eval(sheet,evalset) + r.eval(sheet,evalset);
                case "*": return l.eval(sheet,evalset) * r.eval(sheet,evalset);
                case "/": return l.eval(sheet,evalset) / r.eval(sheet,evalset);
                case "-": return l.eval(sheet,evalset) - r.eval(sheet,evalset);
                default: return 0;
            }
        }
        public BinEx(Expr l,String op,Expr r) { this.l=l;this.op=op;this.r=r;}
        @Override
        public void replicateTo(PrintStream stream) {
            stream.print("(");
            l.replicateTo(stream);
            stream.print(op);
            r.replicateTo(stream);
            stream.print(")");
        }
        @Override
        public String toString() {
            return "("+l+" "+op+" "+r+")";
        }
    }
    public static class CallEx extends Expr {
        List<Expr> params;
        String name;
        public CallEx(String name, List<Expr> params){
            this.name=name;
            this.params=params;
        }
        @Override
        public int eval(Context sheet,Set<Expr> evalset) throws Exception {
            List<Integer> l = new LinkedList<>();
            for (var e:params){
                l.add(e.eval(sheet, evalset));
            }
            return sheet.lookupFunction(name).apply(l);
        }
        @Override
        public void replicateTo(PrintStream stream) {
            stream.print(name);
            stream.print("(");
            if (params.size()>0){
                params.get(0).replicateTo(stream);
                if (params.size()>1)
                    params.subList(1,params.size()) .forEach(p-> { stream.print(",");p.replicateTo(stream);} );
            }
            stream.print(")");
        }
        @Override
        public String toString() {
            var p=params.stream().map(x->x.toString()).collect(Collectors.joining(","));
            return "("+name+"("+p+"))";
        }
    }

    public static Expr shuntyard(String expression, Context ctx) throws Parser.Fail {
        var tokenstream = scan(expression);
        // we sort out all the pesky whitespaces
        tokenstream = tokenstream.stream()
            .filter(c->c.type!=TokenType.WHITESPACE)
            .collect(Collectors.toList());
        var s = new Shuntyard(tokenstream,ctx);
        return s.expr();
    }

    public static Expr parse(String expression)throws Parser.Fail {
        var tokenstream = scan(expression);
        // we sort out all the pesky whitespaces
        tokenstream = tokenstream.stream()
            .filter(c->c.type!=TokenType.WHITESPACE)
            .collect(Collectors.toList());
        // hand over the token stream to the parser
        var p = new Parser(tokenstream);
        // return a java object representation of the syntactic structure of the expression
        return p.expr();
    }
    /**
     * Iteratively decapitate the input string, matching the current start of the string with the patterns
     * for the respective tokens of our spreadsheet language. 
     * @param expression
     * @return the input expression converted to a List of classified tokens
     */
    public static List<Token> scan(String expression){
        var tokenstream = new LinkedList<Token>();
        // repeat process as long as there is input to be scanned
        scanning: while (expression.length()>0){
            // iterate through all token types
            tokens: for (var tok:TokenType.values()) {
                // build a matcher for the current pattern
                var patty = tok.getPattern();
                var matchy = patty.matcher(expression);
                if (matchy.find()){ // input matched the pattern for this token type
                    var lex=matchy.group();
                    tokenstream.add(new Token(tok,lex));
                    // split off the found token from the input and continue scanning
                    expression=expression.substring(lex.length());
                    continue scanning;
                }
            }
            throw new RuntimeException("nasty input found!");
        }
        tokenstream.add(new Token(TokenType.EOF,""));
        return tokenstream;
    }

    /**
     * specifies TokenTypes for the spreadlanguage, together with their lexicographic pattern
     */
    public static enum TokenType {
        MULOP("(\\*|/)"),
        ADDOP("(\\+|-)"),
        COMMA(","),
        LBRACK("\\("),
        RBRACK("\\)"),
        RANGE(":"),
        REF("[A-Pa-p]1?\\d"),
        INTCONST("\\d+"),
        NAME("\\w+"),
        WHITESPACE("\\s"),
        CATCHALL(".*"),
        EOF("");
        private Pattern pattern;
        TokenType(String pattern){
            this.pattern=Pattern.compile("\\A"+pattern);
        }
        /**
         * @return regex pattern that matches tokens of this type
         */
        public Pattern getPattern(){
            return pattern;
        }
    }
    /**
     * represents a classified input substring
     * - is generated by the scanner
     * - is consumed by the parser
     */
    public record Token(TokenType type,String input) {};

    /**
     * Recursive Descent Parser for the spreadlanguage
     * 
     */
    public static class Parser {
        public class Fail extends Exception {
            public Fail(String message){
                super(message);
            }
        }
        private List<Token> terminals;
        /**
         * consumes (i.e. removes) whatever token is the current head of the input
         * @return
         */
        public Token consume(){
            return terminals.remove(0);
        }
        /**
         * consumes (i.e. removes) token from the rest of the input stream
         * @param t the expected token to be found at the head of the input
         * @return
         * @throws Fail
         */
        public Token consume(TokenType t) throws Fail {
            if (peek()!=t) throw new Fail("expected "+t+" , but found "+consume());
            return consume();
        }
        /**
         * check what the head of the remaining input looks like
         * @return head of the remaining input
         */
        public TokenType peek(){
            return terminals.get(0).type();
        }
        /**
         * Handover of the Tokenlist to the parser engine
         * @param terminals
         */
        public Parser(List<Token> terminals){
            this.terminals=terminals;
        }
        /**
         * Entry function into parsing expressions
         * @return
         * @throws Fail
         */
        public Expr expr() throws Fail {
            return e();
        }
// C -> name | name ( C , ... )
        private Expr c() throws Fail {
            var name=NAME();
            List<Expr> params=new LinkedList<Expr>();
            if (peek()!=TokenType.LBRACK) {
                // just a variable reference
                return new Var(name);
            }
            consume(TokenType.LBRACK);
            while (peek()!=TokenType.RBRACK) { 
                params.add(e());
                if (peek()!=TokenType.COMMA) {
                    if (peek()!=TokenType.RBRACK) throw new Fail("expected , or ) but found "+consume());
                }
                else consume(TokenType.COMMA);
            }
            consume(TokenType.RBRACK);
            var e = new CallEx(name,params);
            return e;
        }
// E  -> T E'
// E' -> + T E' | -TE' |epsilon
        /**
         * D&C handle the parsing of an expression
         * @return
         * @throws Fail
         */
        private Expr e() throws Fail {
            var e = t();
            while ((peek())==TokenType.ADDOP){
                var t = consume(TokenType.ADDOP);
                var e2 = t();
                e = new BinEx(e,t.input(),e2);
            }
            return e;
        }
// T  -> F T'
// T' -> * F T' | /FT' |epsilon
        /**
         * D&C handling of the parsing of a multiplicative term
         * @return
         * @throws Fail
         */
        private Expr t() throws Fail{
            var e = f();
            while ((peek())==TokenType.MULOP){
                var t = consume(TokenType.MULOP);
                var e2 = f();
                e = new BinEx(e,t.input(),e2);
            }
            return e;
        }
// F  -> (E) | int | ref | C
        /**
         * D&C handling of the parsing of a factor
         * @return
         * @throws Fail
         */
        private Expr f() throws Fail{
            switch (peek()){
                case INTCONST: return INTCONST();
                case REF: return REF();
                case NAME: return c();
                case LBRACK:
                    consume(TokenType.LBRACK);
                    var e = expr();
                    consume(TokenType.RBRACK);
                    return e;
                default: throw new Fail("expected an INTCONST, REF  or ( but found "+consume());
            }
        }
        private Expr INTCONST() throws Fail {
            return new Const(Integer.parseInt(consume(TokenType.INTCONST).input()));
        }
        private Expr REF() throws Fail {
            return new Ref(consume(TokenType.REF).input());
        }
        private String NAME() throws Fail {
            return consume(TokenType.NAME).input();
        }
    }
    /**
     * Shuntyard algorithm for converting infix expressions to postfix notation
     */
    public static class Shuntyard {
        private List<Token> terminals;
        private Context ctx;
        public Shuntyard(List<Token> terminals,Context ctx){
            this.terminals=terminals;
            this.ctx=ctx;    
        }
        public Expr expr(){
            Queue<Token> output = new LinkedList<>();
            Stack<Token> operators = new Stack<>();
            strm: for (var t:terminals){
                switch (t.type()){
                    case INTCONST:  output.offer(t); break;
                    case REF:       output.offer(t); break;
                    case ADDOP: // prefer * over +
                        while (!operators.isEmpty() && operators.peek().type()==TokenType.MULOP){
                            output.offer(operators.pop());
                        }
                    case MULOP:    operators.push(t); break;
                    case EOF:
                        while (!operators.isEmpty())
                            output.offer(operators.pop());
                        break strm;
                    // section for handling bracket expressions, managing priorities
                    case LBRACK:   operators.push(t); break;
                    case RBRACK: // basically a mini EOF, with a special case for function calls
                        while (!operators.isEmpty() && operators.peek().type()!=TokenType.LBRACK){
                            var tok = operators.pop();
                            output.offer(tok);
                        }
                        operators.pop(); // discard the LBRACK
                        // function call name here:
                        if (!operators.isEmpty() && operators.peek().type()==TokenType.NAME) 
                            output.offer(operators.pop());
                        break;
                    // function call stuff
                    case COMMA: // basically a mini EOF
                        while (!operators.isEmpty() && operators.peek().type()!=TokenType.LBRACK){
                            output.offer(operators.pop());
                        }
                        break;
                    case NAME:   
                        if (ctx.lookupFunction(t.input()) != null) 
                            operators.push(t); 
                        else  
                            output.offer(t);
                        
                    default: break;
                }
            }

            

            // build up actual expression tree from postfix notation
            var backlog = new Stack<Expr>();
            System.out.println("Postfix output: " + java.util.Arrays.toString(output.toArray()));
            for (var t:output){
                // print all elements in backlog
                System.out.println("Backlog: " + java.util.Arrays.toString(backlog.toArray()));
                switch (t.type()){
                    case INTCONST: backlog.push(new Const(Integer.parseInt(t.input()))); break;
                    case REF:      backlog.push(new Ref(t.input())); break;
                    case ADDOP:
                    case MULOP:
                        Expr r = backlog.pop(), l = backlog.pop();
                        backlog.push(new BinEx(l,t.input(),r));
                        break;
                    case NAME:
                        List<Expr> params = new LinkedList<>();
                        if (ctx.lookupFunction(t.input())!=null) {
                            var p = backlog.pop();
                            params.add(p);
                            backlog.push(new CallEx(t.input(), params));
                        }
                        else {
                            // no parameters
                            backlog.push(new Var(t.input()));
                        }
                       
                    default: break;
                }
            }

            return backlog.pop();
        }
    }
    public static void main(String[] args) {
        Context ctx = new Context(){};
        var shunt = new Shuntyard(scan("(A1+2/fun(3+5))+a"),ctx).expr();
        shunt.replicateTo(System.out);System.out.println();
        shunt = new Shuntyard(scan("A1+2/3+5*const"),ctx).expr();
        shunt.replicateTo(System.out);System.out.println();
    }
}