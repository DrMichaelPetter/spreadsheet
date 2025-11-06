package schule;

import java.util.Map;
import java.util.Scanner;

import schule.Expr.Context;


public class Calculator {
    public static void main(String[] args) {
        // setup environment with some variables
        Map <String, Integer> vars = Map.of(
            "var", 42
            ,"a" , 5
            ,"b" , 8
            );
        // Read expression from keyboard, parse it and evaluate it
        var ctx = new Context(){
            @Override
            public int lookupVariable(String name) {
                return vars.getOrDefault(name, 0);
            }
        };
        var keyboard = new Scanner(System.in);
        System.out.print("Enter expression: ");
        var expression = keyboard.nextLine();

        try {
            var expr = Expr.parse(expression);
            int result = expr.eval(ctx);
            System.out.println("Result: " + result);
            keyboard.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
