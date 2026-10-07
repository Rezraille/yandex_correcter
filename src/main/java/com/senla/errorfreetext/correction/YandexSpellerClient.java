package com.senla.errorfreetext.correction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.senla.errorfreetext.exception.app.TextCorrectionException;
import com.senla.errorfreetext.db.enums.Language;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class YandexSpellerClient {

  private static final int IGNORE_DIGITS = 2;
  private static final int IGNORE_URLS = 4;
  private static final Pattern URL_PATTERN = Pattern.compile(
      "(?i)\\b(?:https?://|ftp://|www\\.)\\S+"
          + "|\\b[\\p{L}\\d-]+\\.[\\p{L}]{2,}"
  );
  private final RestClient yandexRestClient;

  public String correct(String text, Language language) {
    if (text.length() > TextChunkSplitter.MAX_CHUNK_SIZE) {
      throw new IllegalArgumentException("Chunk exceeds 10000 characters");
    }
    if (text.isEmpty()) {
      return text;
    }
    return correctChunk(text, language, optionsFor(text));
  }

  private int optionsFor(String text) {
    int options = text.codePoints().anyMatch(Character::isDigit) ? IGNORE_DIGITS : 0;
    if (URL_PATTERN.matcher(text).find()) {
      options |= IGNORE_URLS;
    }
    return options;
  }

  private String correctChunk(String text, Language language, int options) {
    long startedAt = System.nanoTime();
    log.debug("Yandex Speller request language={} textLength={} options={}",
        language, text.length(), options);

    SpellError[] errors = requestCorrections(text, language, options);
    validateCorrections(errors, text.length());
    String correctedText = applyCorrections(text, errors);

    log.debug("Yandex Speller response corrections={} durationMs={}", errors.length,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    return correctedText;
  }

  private SpellError[] requestCorrections(String text, Language language, int options) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("text", text);
    form.add("lang", language.name().toLowerCase());
    form.add("options", Integer.toString(options));

    SpellError[][] response;
    try {
      response = yandexRestClient.post().uri("/checkTexts")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form).retrieve().body(SpellError[][].class);
    } catch (RestClientException exception) {
      throw new TextCorrectionException("Yandex Speller request failed", exception);
    }
    if (response == null || response.length != 1 || response[0] == null) {
      throw new TextCorrectionException("Invalid Yandex Speller response");
    }
    return response[0];
  }

  private void validateCorrections(SpellError[] errors, int textLength) {
    for (SpellError error : errors) {
      if (error == null || error.pos() == null || error.len() == null) {
        throw new TextCorrectionException("Invalid Yandex Speller error position");
      }
      int start = error.pos();
      int length = error.len();
      if (start < 0 || length <= 0 || start > textLength || length > textLength - start) {
        throw new TextCorrectionException("Invalid Yandex Speller error position");
      }
      if (error.s() != null && error.s().stream().anyMatch(suggestion -> suggestion == null)) {
        throw new TextCorrectionException("Invalid Yandex Speller suggestion");
      }
    }
  }

  private String applyCorrections(String text, SpellError[] errors) {
    // Replace from right to left so earlier positions remain valid.
    Arrays.sort(errors, Comparator.comparingInt(SpellError::pos).reversed());
    StringBuilder result = new StringBuilder(text);
    int previousStart = text.length();

    for (SpellError error : errors) {
      int start = error.pos();
      int end = start + error.len();
      if (end > previousStart) {
        throw new TextCorrectionException("Overlapping Yandex Speller error positions");
      }
      previousStart = start;
      if (error.s() != null && !error.s().isEmpty()) {
        result.replace(start, end, error.s().get(0));
      }
    }
    return result.toString();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SpellError(Integer pos, Integer len, List<String> s) {
  }
}
