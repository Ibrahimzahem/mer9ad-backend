package com.hackathon.ra9edhamad.domain;

/**
 * The three terminal/holding states of the Shield pipeline.
 * GREEN = let it proceed, RED = block + report, ORANGE = open the intervention dialogue.
 */
public enum Decision {
    GREEN,
    ORANGE,
    RED
}
