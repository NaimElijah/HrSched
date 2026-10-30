package io.github.naimelijah.hrsched.architecture.fixtures.payroll.util;

/**
 * Breaks the layer rule: a class in payroll but in none of its layers, where neither the domain's
 * rules nor the application's would see it.
 */
public final class PayslipFormatting {

  private PayslipFormatting() {}

  /** Something a helper package collects. */
  public static String upperCase(String reference) {
    return reference.toUpperCase(java.util.Locale.ROOT);
  }
}
