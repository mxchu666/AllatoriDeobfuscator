package com.example.demo;

import com.example.demo.util.MathService;
import com.example.demo.util.Names;

public class App {

  public static final String GREETING = "Hello from OpenAllatori demo!";
  private static int counter;

  public static void main(String[] args) {
    System.out.println(GREETING);
    System.out.println("args count: " + args.length);
    MathService service = new MathService(7);
    int result = service.factorial(5);
    System.out.println("factorial(5) = " + result);
    System.out.println("secret message: " + secret());
    System.out.println("constant name: " + Names.NAME);
    counter = 42;
    System.out.println("counter = " + counter);
    if (args.length > 0 && "crash".equals(args[0])) {
      crash();
    }
  }

  private static String secret() {
    return "The magic word is: abracadabra";
  }

  private static void crash() {
    throw new IllegalStateException("intentional crash for stack trace test");
  }
}
