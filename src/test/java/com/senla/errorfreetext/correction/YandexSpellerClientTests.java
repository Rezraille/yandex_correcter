package com.senla.errorfreetext.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.senla.errorfreetext.exception.app.TextCorrectionException;
import com.senla.errorfreetext.db.enums.Language;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class YandexSpellerClientTests {

  private static final String BASE_URL = "https://speller.yandex.net/services/spellservice.json";

  private MockRestServiceServer server;
  private YandexSpellerClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new YandexSpellerClient(builder.build());
  }

  @Test
  void appliesFirstSuggestionsWithoutShiftingOtherErrorPositions() {
    expectChunk("helo wrld", "en", 0, """
        [[
          {"code":1,"pos":0,"len":4,"word":"helo","s":["hello","help"]},
          {"code":1,"pos":5,"len":4,"word":"wrld","s":["world"]}
        ]]
        """);

    assertEquals("hello world", client.correct("helo wrld", Language.EN));
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({
      "Привет, 0", "Привет 123, 2", "Привет ١٢٣, 2",
      "Привет https://example.com, 4", "Привет www.example.com, 4",
      "Привет example.com, 4", "Привет FTP://example.com, 4",
      "Привет 123 https://example.com, 6"
  })
  void setsOnlyRequiredOptions(String text, int options) {
    expectChunk(text, "ru", options, "[[]]");

    assertEquals(text, client.correct(text, Language.RU));
    server.verify();
  }

  @Test
  void keepsWordWhenNoSuggestionIsAvailable() {
    expectChunk("helo", "en", 0, "[[{\"pos\":0,\"len\":4,\"s\":[]}]]");

    assertEquals("helo", client.correct("helo", Language.EN));
    server.verify();
  }

  @Test
  void calculatesOptionsSeparatelyForEachChunk() {
    expectChunk("hello world", "en", 0, "[[]]");
    expectChunk("hello 123 https://example.com", "en", 6, "[[]]");

    assertEquals("hello world", client.correct("hello world", Language.EN));
    assertEquals("hello 123 https://example.com",
        client.correct("hello 123 https://example.com", Language.EN));
    server.verify();
  }

  @Test
  void acceptsChunkAtLimit() {
    String text = "a".repeat(10_000);
    expectChunk(text, "en", 0, "[[]]");

    assertEquals(text, client.correct(text, Language.EN));
    server.verify();
  }

  @Test
  void rejectsOversizedChunkWithoutSendingRequest() {
    assertThrows(IllegalArgumentException.class,
        () -> client.correct("a".repeat(10_001), Language.EN));
    server.verify();
  }

  @Test
  void reportsConnectionTimeout() {
    server.expect(requestTo(BASE_URL + "/checkTexts"))
        .andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThrows(TextCorrectionException.class, () -> client.correct("hello", Language.EN));
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "[]", "[null]", "[[],[]]", "{}", "not-json", "[[null]]",
      "[[{}]]", "[[{\"pos\":0,\"s\":[\"hi\"]}]]",
      "[[{\"pos\":0,\"len\":5,\"s\":[null]}]]",
      "[[{\"pos\":0,\"len\":0,\"s\":[\"hi\"]}]]",
      "[[{\"pos\":0,\"len\":4,\"s\":[\"hi\"]},{\"pos\":2,\"len\":3,\"s\":[\"lo\"]}]]"})
  void rejectsInvalidApiResponse(String response) {
    expectChunk("hello", "en", 0, response);

    assertThrows(TextCorrectionException.class, () -> client.correct("hello", Language.EN));
    server.verify();
  }

  @Test
  void rejectsInvalidErrorPosition() {
    expectChunk("hello", "en", 0, "[[{\"pos\":99,\"len\":5,\"s\":[\"hello\"]}]]");

    assertThrows(TextCorrectionException.class, () -> client.correct("hello", Language.EN));
    server.verify();
  }

  private void expectChunk(String text, String language, int options, String response) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("text", text);
    form.add("lang", language);
    form.add("options", Integer.toString(options));
    server.expect(requestTo(BASE_URL + "/checkTexts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().formData(form))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
  }
}
