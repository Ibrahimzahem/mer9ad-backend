package com.hackathon.ra9edhamad.domain;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The growing playbook of social-engineering manipulation patterns. Seeded with known Arabic
 * patterns and appended to whenever an intervention detects a fresh coaching pattern — this is
 * the feedback loop that makes Shield better at recognising the attacker's script over time.
 */
@Component
public class ScamPlaybookStore {

    private final CopyOnWriteArrayList<String> patterns = new CopyOnWriteArrayList<>(List.of(
            "تلقين الضحية كتابة \"مصاريف عائلية\" كغرض لتحويل إلى حساب جديد لا علاقة له بالعائلة",
            "إجراء التحويل أثناء مكالمة مباشرة مع محتال ينتحل صفة دعم البنك",
            "لصق رقم آيبان (IBAN) تم استلامه عبر واتساب من جهة غير معروفة"
    ));

    public List<String> patterns() {
        return List.copyOf(patterns);
    }

    /** Append a newly observed pattern (de-duplicated). */
    public void append(String pattern) {
        if (pattern != null && !pattern.isBlank() && !patterns.contains(pattern)) {
            patterns.add(pattern);
        }
    }
}
