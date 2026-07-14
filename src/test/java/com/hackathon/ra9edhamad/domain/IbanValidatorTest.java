package com.hackathon.ra9edhamad.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanValidatorTest {

    @Test
    void acceptsWellFormedSaudiIban() {
        assertThat(IbanValidator.isValid("SA0000000000000000003333")).isTrue();  // 24 chars: SA + 22 digits
        assertThat(IbanValidator.isValid("SA0000000000000000001111")).isTrue();
    }

    @Test
    void rejectsTypos() {
        assertThat(IbanValidator.isValid("SA0000000000000003333")).isFalse();    // too short (15 zeros)
        assertThat(IbanValidator.isValid("SA000000000000000000033333")).isFalse(); // too long
        assertThat(IbanValidator.isValid("sa0000000000000000003333")).isFalse();  // lowercase letters
        assertThat(IbanValidator.isValid("SA00000000000000000033A3")).isFalse();  // non-digit in body
        assertThat(IbanValidator.isValid("US0000000000000000003333")).isFalse();  // wrong country
        assertThat(IbanValidator.isValid("SA 0000 0000 0000 0000 3333")).isFalse(); // spaces
        assertThat(IbanValidator.isValid("")).isFalse();
        assertThat(IbanValidator.isValid(null)).isFalse();
    }
}
