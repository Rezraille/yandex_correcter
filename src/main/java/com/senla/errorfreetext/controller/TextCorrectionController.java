package com.senla.errorfreetext.controller;

import com.senla.errorfreetext.dto.CreateTextCorrectionRequest;
import com.senla.errorfreetext.dto.CreateTextCorrectionResponse;
import com.senla.errorfreetext.dto.TextCorrectionResponse;
import com.senla.errorfreetext.service.TextCorrectionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TextCorrectionController {

  private final TextCorrectionService service;

  @PostMapping
  public ResponseEntity<CreateTextCorrectionResponse> createTextCorrection(
      @Valid @RequestBody CreateTextCorrectionRequest request
  ) {
    UUID id = service.createTextCorrection(request);

    return ResponseEntity.created(URI.create("/tasks/" + id))
        .body(new CreateTextCorrectionResponse(id));
  }

  @GetMapping("/{id}")
  public TextCorrectionResponse getTextCorrection(
      @PathVariable UUID id
  ) {
    return service.getTextCorrection(id);
  }
}
