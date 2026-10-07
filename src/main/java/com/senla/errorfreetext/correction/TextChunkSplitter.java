package com.senla.errorfreetext.correction;

public final class TextChunkSplitter {

  static final int MAX_CHUNK_SIZE = 10_000;

  private TextChunkSplitter() {
  }

  public static int endPosition(String text, int start) {
    int end = Math.min(start + MAX_CHUNK_SIZE, text.length());
    if (end == text.length()) {
      return end;
    }
    int boundary = end;
    while (boundary > start && !Character.isWhitespace(text.charAt(boundary - 1))) {
      boundary--;
    }
    if (boundary > start) {
      return boundary;
    }
    return Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
  }
}
