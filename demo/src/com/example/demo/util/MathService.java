package com.example.demo.util;

public class MathService {

  private final int base;

  public MathService(int base) {
    this.base = base;
  }

  public int getBase() {
    return this.base;
  }

  public int factorial(int n) {
    int acc = this.base;
    for (int i = 2; i <= n; i++) {
      acc *= i;
    }
    return acc;
  }

  public String describe() {
    return "MathService with base " + this.base;
  }
}
