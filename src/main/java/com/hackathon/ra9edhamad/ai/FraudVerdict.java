package com.hackathon.ra9edhamad.ai;

/** The AI fraud judge's verdict. {@code assessment} is an Arabic, victim-readable explanation. */
public record FraudVerdict(
        double fraudLikelihood,        // 0..1
        String assessment,             // Arabic
        Recommendation recommendation
) {
    public enum Recommendation {ALLOW, CHALLENGE, BLOCK}

    /** Safe default used when the model output cannot be parsed. */
    public static FraudVerdict safeDefault() {
        return new FraudVerdict(0.5,
                "تعذّر التحقق الكامل؛ يُنصح بالتحقق الإضافي قبل المتابعة.",
                Recommendation.CHALLENGE);
    }
}
