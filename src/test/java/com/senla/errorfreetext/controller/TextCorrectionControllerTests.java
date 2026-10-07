package com.senla.errorfreetext.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.senla.errorfreetext.dto.TextCorrectionResponse;
import com.senla.errorfreetext.config.JacksonConfiguration;
import com.senla.errorfreetext.exception.GlobalExceptionHandler;
import com.senla.errorfreetext.exception.app.TextCorrectionNotFoundException;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.service.TextCorrectionService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class TextCorrectionControllerTests {

  @Mock
  private TextCorrectionService service;

  private MockMvc mvc;
  private LocalValidatorFactoryBean validator;

  @BeforeEach
  void setUp() {
    Jackson2ObjectMapperBuilder mapperBuilder = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    new JacksonConfiguration().strictStringValues().customize(mapperBuilder);
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(new TextCorrectionController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapperBuilder.build()))
        .setValidator(validator)
        .build();
  }

  @AfterEach
  void tearDown() {
    validator.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"RU", "EN"})
  void createsCorrection(String language) throws Exception {
    UUID id = UUID.randomUUID();
    when(service.createTextCorrection(any())).thenReturn(id);

    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"Превет\",\"language\":\"" + language + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/tasks/" + id))
        .andExpect(jsonPath("$.id").value(id.toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}", "{\"text\":null,\"language\":\"RU\"}",
      "{\"text\":\"\",\"language\":\"RU\"}",
      "{\"text\":\"ab\",\"language\":\"EN\"}",
      "{\"text\":\"123 !@#\",\"language\":\"RU\"}",
      "{\"text\":\"   \",\"language\":\"RU\"}",
      "{\"text\":\"hello\"}", "{\"text\":\"hello\",\"language\":null}",
      "{\"text\":\"hello\",\"language\":\"DE\"}",
      "{\"text\":\"hello\",\"language\":\"ru\"}",
      "{\"text\":\"hello\",\"language\":0}",
      "{\"text\":\"hello\",\"language\":1}", "{", ""
  })
  void rejectsInvalidRequest(String body) throws Exception {
    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorMessage").isNotEmpty())
        .andExpect(jsonPath("$.errorCode").value(40001))
        .andExpect(jsonPath("$.timestamp").isString())
        .andExpect(jsonPath("$.path").value("/tasks"));

    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @EnumSource(value = CorrectionStatus.class, names = {"NEW", "PROCESSING"})
  void returnsOnlyStatusForPendingCorrection(CorrectionStatus correctionStatus) throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getTextCorrection(id)).thenReturn(new TextCorrectionResponse(correctionStatus, null, null));

    mvc.perform(get("/tasks/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(correctionStatus.name()))
        .andExpect(jsonPath("$.correctedText").doesNotExist())
        .andExpect(jsonPath("$.errorMessage").doesNotExist());
  }

  @Test
  void returnsCorrectedTextForCompletedCorrection() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getTextCorrection(id)).thenReturn(new TextCorrectionResponse(CorrectionStatus.COMPLETED, "Привет", null));

    mvc.perform(get("/tasks/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.correctedText").value("Привет"))
        .andExpect(jsonPath("$.errorMessage").doesNotExist());
  }

  @Test
  void returnsErrorForFailedCorrection() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getTextCorrection(id)).thenReturn(new TextCorrectionResponse(CorrectionStatus.ERROR, null, "Yandex unavailable"));

    mvc.perform(get("/tasks/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.errorMessage").value("Yandex unavailable"))
        .andExpect(jsonPath("$.correctedText").doesNotExist());
  }

  @Test
  void returnsNotFoundInRequiredFormat() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getTextCorrection(id)).thenThrow(new TextCorrectionNotFoundException(id));

    mvc.perform(get("/tasks/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorMessage").value("Text correction with id: " + id + " not found"))
        .andExpect(jsonPath("$.errorCode").value(40401))
        .andExpect(jsonPath("$.timestamp").isString())
        .andExpect(jsonPath("$.path").value("/tasks/" + id));
  }

  @Test
  void rejectsInvalidCorrectionId() throws Exception {
    mvc.perform(get("/tasks/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(40001))
        .andExpect(jsonPath("$.errorMessage").value("id: must be a valid UUID"));

    verifyNoInteractions(service);
  }

  @Test
  void returnsRequiredFormatForUnexpectedError() throws Exception {
    when(service.createTextCorrection(any())).thenThrow(new IllegalStateException("Database unavailable"));

    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"hello\",\"language\":\"EN\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorMessage").value("Internal server error"))
        .andExpect(jsonPath("$.errorCode").value(50001));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"text\":true,\"language\":\"EN\"}",
      "{\"text\":123,\"language\":\"EN\"}",
      "{\"text\":1.5,\"language\":\"EN\"}",
      "{\"text\":\"hello\",\"language\":\"EN\"} {}",
      "{\"text\":\"a😀\",\"language\":\"EN\"}"
  })
  void rejectsInvalidTextTypesTrailingJsonAndShortUnicodeText(String body) throws Exception {
    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(40001));
    verifyNoInteractions(service);
  }

  @Test
  void explainsMalformedJsonWithoutBlamingLanguage() throws Exception {
    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorMessage").value("Invalid or missing JSON request body"));
  }

  @Test
  void explainsInvalidLanguage() throws Exception {
    mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"hello\",\"language\":\"DE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorMessage").value("language: must be EN or RU"));
  }

  @Test
  void preservesAllowHeaderForUnsupportedMethod() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/tasks"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"))
        .andExpect(jsonPath("$.errorCode").value(40501));
  }

  @Test
  void returnsUnifiedErrorForUnsupportedContentType() throws Exception {
    mvc.perform(post("/tasks").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.errorCode").value(41501));
  }

  @Test
  void returnsUnifiedErrorForUnknownRoute() throws Exception {
    mvc.perform(get("/unknown"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(40401));
  }
}
