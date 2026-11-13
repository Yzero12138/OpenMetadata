package org.openmetadata.service.integration;

final class DorisSqlLiterals {
  private DorisSqlLiterals() {}

  static String defaultLiteral(String value) {
    return doubleQuoted(value);
  }

  static String tableComment(String value) {
    return doubleQuoted(value);
  }

  static String queryLiteral(String value) {
    return doubleQuoted(value);
  }

  static String columnComment(String value) {
    return doubleQuoted(value);
  }

  static String decodeDefault(String value) {
    StringBuilder decoded = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current != '\\' || i + 1 == value.length()) {
        decoded.append(current);
        continue;
      }
      char escaped = value.charAt(++i);
      switch (escaped) {
        case '0' -> decoded.append('\0');
        case 'b' -> decoded.append('\b');
        case 'n' -> decoded.append('\n');
        case 'r' -> decoded.append('\r');
        case 't' -> decoded.append('\t');
        case 'Z' -> decoded.append((char) 26);
        case '%', '_' -> decoded.append('\\').append(escaped);
        default -> decoded.append(escaped);
      }
    }
    return decoded.toString();
  }

  private static String doubleQuoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
