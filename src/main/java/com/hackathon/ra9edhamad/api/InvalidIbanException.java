package com.hackathon.ra9edhamad.api;

/** Thrown when the transfer payload carries a malformed beneficiary IBAN. */
public class InvalidIbanException extends RuntimeException {

    private final String beneficiaryIban;

    public InvalidIbanException(String beneficiaryIban) {
        super("Invalid Saudi IBAN: it must be 'SA' followed by exactly 22 digits (24 characters total).");
        this.beneficiaryIban = beneficiaryIban;
    }

    public String beneficiaryIban() {
        return beneficiaryIban;
    }
}
