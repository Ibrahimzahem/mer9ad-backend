package com.hackathon.ra9edhamad.domain;

import java.util.regex.Pattern;

/**
 * Saudi IBAN format check. A Saudi IBAN is 24 characters total: {@code SA} + 2 check digits +
 * 2-digit bank code + 18-digit account number (BBAN) — i.e. {@code SA} followed by exactly 22
 * digits, with letters only at the start. Anything else (wrong length, spaces, non-digits,
 * lowercase) is a typo and is rejected before the pipeline ever runs.
 */
public final class IbanValidator {

    private static final Pattern SAUDI_IBAN = Pattern.compile("^SA[0-9]{22}$");

    private IbanValidator() {
    }

    public static boolean isValid(String iban) {
        return iban != null && SAUDI_IBAN.matcher(iban).matches();
    }
}
