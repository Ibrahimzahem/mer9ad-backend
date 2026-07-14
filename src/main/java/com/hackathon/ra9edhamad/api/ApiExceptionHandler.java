package com.hackathon.ra9edhamad.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps API-level validation failures to clean 400 responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String error, String message, String beneficiaryIban) {
    }

    @ExceptionHandler(InvalidIbanException.class)
    public ResponseEntity<ApiError> handleInvalidIban(InvalidIbanException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_IBAN", ex.getMessage(), ex.beneficiaryIban()));
    }
}
