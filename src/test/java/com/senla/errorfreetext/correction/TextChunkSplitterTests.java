package com.senla.errorfreetext.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextChunkSplitterTests {

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 9999, 10000})
  void keepsTextWithinLimitInOneChunk(int length) {
    assertEquals(length, TextChunkSplitter.endPosition("a".repeat(length), 0));
  }

  @Test
  void splitsLongWordAtLimit() {
    String text = "a".repeat(20001);

    assertEquals(10000, TextChunkSplitter.endPosition(text, 0));
    assertEquals(20000, TextChunkSplitter.endPosition(text, 10000));
    assertEquals(20001, TextChunkSplitter.endPosition(text, 20000));
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "\t", "\n", "\r\n"})
  void prefersLastWhitespaceWithinLimit(String whitespace) {
    String first = "first word" + whitespace;
    String text = first + "a".repeat(10000);

    assertEquals(first.length(), TextChunkSplitter.endPosition(text, 0));
  }

  @Test
  void keepsUnicodePairTogetherAtBoundaryOfLaterChunk() {
    String text = "a".repeat(19999) + "😀tail";

    assertEquals(10000, TextChunkSplitter.endPosition(text, 0));
    assertEquals(19999, TextChunkSplitter.endPosition(text, 10000));
    assertEquals(text.length(), TextChunkSplitter.endPosition(text, 19999));
  }

  @Test
  void preservesLargeTextAndWhitespaceAcrossAllChunks() {
    String text = ("Привет 😀\tмир!\r\n\n" + "a".repeat(10001) + "  ").repeat(100);
    StringBuilder reconstructed = new StringBuilder();

    for (int start = 0; start < text.length();) {
      int end = TextChunkSplitter.endPosition(text, start);
      assertTrue(end > start && end - start <= 10000);
      if (end < text.length()) {
        assertFalse(Character.isHighSurrogate(text.charAt(end - 1))
            && Character.isLowSurrogate(text.charAt(end)));
      }
      reconstructed.append(text, start, end);
      start = end;
    }

    assertEquals(text, reconstructed.toString());
  }
}
