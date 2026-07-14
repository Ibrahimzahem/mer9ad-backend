package com.hackathon.ra9edhamad.ai;

import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.ServerFacts;

/**
 * Deterministic, offline-safe AI. Returns canned Arabic responses keyed off the grounded facts
 * so the live demo works with no network and no API key. Auto-selected when OPENROUTER_API_KEY
 * is absent (see {@code ShieldAiConfig}).
 */
public class StubShieldAi implements ShieldAi {

    @Override
    public FraudVerdict judge(GroundedContext context) {
        ServerFacts f = context.serverFacts();

        if (f.muleWatchlistHit()) {
            return new FraudVerdict(0.92,
                    "الحساب المستفيد مُدرج ضمن قائمة حسابات تمرير الأموال (mule). التحويل يحمل مؤشرات احتيال عالية ويجب إيقافه.",
                    FraudVerdict.Recommendation.BLOCK);
        }
        if (f.accountAgeDays() <= 30 && f.transitVelocity() >= 0.4) {
            return new FraudVerdict(0.6,
                    "الحساب المستفيد حديث جدًا وتتسارع الأموال خارجه بشكل غير طبيعي، مع غياب أي سجل تعامل سابق. هذه أنماط تتوافق مع الاحتيال بالهندسة الاجتماعية.",
                    FraudVerdict.Recommendation.CHALLENGE);
        }
        if (context.coercion().anyPresent()) {
            return new FraudVerdict(0.55,
                    "تظهر مؤشرات إكراه أثناء العملية (مثل مكالمة نشطة أو لصق رقم الآيبان أو تردد غير معتاد). يُنصح بالتحقق من العميل قبل المتابعة.",
                    FraudVerdict.Recommendation.CHALLENGE);
        }
        return new FraudVerdict(0.2,
                "لا توجد مؤشرات احتيال جوهرية بناءً على الحقائق المتحقق منها.",
                FraudVerdict.Recommendation.ALLOW);
    }

    @Override
    public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
        if (latestAnswer == null) {
            return InterventionTurn.ask(
                    "قبل إتمام التحويل، نودّ التأكد من سلامته لحمايتك. بعض التحويلات لحسابات جديدة تتم أحيانًا "
                    + "بتوجيه من طرف آخر عبر الهاتف. هل يمكنك توضيح الغرض من هذا التحويل، ولمن يعود هذا الحساب؟");
        }

        String ans = latestAnswer.trim();

        // Survival instinct first: if the customer's OWN words describe a classic scam (a promise
        // to double the money, a prince/inheritance/lottery, a stranger met online, an impersonated
        // authority, "guaranteed" crypto returns...), we do NOT clear it no matter how calmly it is
        // told. Hold the transfer and spread awareness. Judge the content, not the confidence.
        String scam = scamIndicator(ans);
        if (scam != null) {
            return new InterventionTurn(
                    InterventionTurn.Action.ESCALATE,
                    "نشكر وضوحك معنا. غير أن ما وصفته يحمل مؤشرات تتطابق مع أساليب احتيال معروفة — مثل الوعد "
                    + "بأرباح أو بمضاعفة المبلغ، أو طلب التحويل لجهة أو شخص لا تعرفه شخصيًا. هذه الأساليب نادرًا "
                    + "ما تكون حقيقية. حمايةً لك، لن نُكمل هذا التحويل الآن، ولم يُخصم أي مبلغ. وإن كان أحدهم "
                    + "يوجّهك عبر الهاتف الآن، فننصحك بإنهاء المكالمة والتواصل مع البنك على الرقم المدوّن خلف بطاقتك.",
                    true, scam);
        }

        // Assume good faith. Only treat an answer as coached when it is empty, near-empty, or a
        // verbatim repeat of the classic planted purpose — a genuine explanation clears easily.
        boolean scripted = ans.isBlank()
                || ans.length() < 5
                || ans.contains("مصاريف عائلية");

        if (scripted) {
            String pattern = "ضحية كرّرت غرضًا مُلقّنًا (\"مصاريف عائلية\") لحساب حديث لا علاقة له بسجل تحويلاتها";
            int asked = session.turnCount(); // Shield questions asked so far (opening = 1)

            // Give the customer room — up to four Shield messages before we hold the transfer.
            if (asked >= 3) {
                return new InterventionTurn(
                        InterventionTurn.Action.ESCALATE,
                        "نعتذر، لن نتمكّن من إتمام هذا التحويل الآن، وذلك حمايةً لك. الإجابات المقدَّمة تتطابق مع أنماط "
                        + "معروفة في عمليات الاحتيال. إن كان أحدهم يوجّهك عبر الهاتف حاليًا، ننصحك بإنهاء المكالمة "
                        + "والتواصل مع البنك على الرقم المدوّن خلف بطاقتك. نطمئنك أنه لم يُخصم أي مبلغ.",
                        true, pattern);
            }
            if (asked <= 1) {
                return new InterventionTurn(
                        InterventionTurn.Action.ASK,
                        "شكرًا لك. نلاحظ أن هذا الحساب حديث ولم يسبق لك التحويل إليه، والغرض «مصاريف عائلية» من الأغراض "
                        + "التي يُطلب من العملاء كتابتها أحيانًا دون علمهم. للتأكد فقط: هل هناك شخص يوجّهك في هذه العملية الآن؟ "
                        + "وما صلتك بصاحب الحساب؟",
                        true, pattern);
            }
            return new InterventionTurn(
                    InterventionTurn.Action.ASK,
                    "نقدّر صبرك معنا؛ سلامة حسابك تهمّنا. نحتاج توضيحًا أوضح قبل المتابعة: من هو صاحب هذا الحساب تحديدًا، "
                    + "ولماذا تُحوّل له هذا المبلغ الآن؟",
                    true, pattern);
        }

        return new InterventionTurn(
                InterventionTurn.Action.CLEAR,
                "شكرًا لتوضيحك. المعلومات كافية ولا نلاحظ ما يدعو للقلق، ويمكنك متابعة التحويل. "
                + "نذكّرك دائمًا بالحذر من أي شخص يطلب منك تحويلًا بشكل عاجل أو يُملي عليك تفاصيله.",
                false, null);
    }

    /**
     * Blatant social-engineering content in the customer's own words. Returns a short Arabic
     * pattern describing the scam (fed to the playbook) or null. Keywords are deliberately
     * high-precision, so a genuine explanation ("صديقي منذ سنوات أسدد له ثمن سيارة") never matches.
     */
    private static String scamIndicator(String answer) {
        String a = answer.toLowerCase();
        if (containsAny(a, "يضاعف", "سيضاعف", "أضاعف", "أضعاف", "مضاعفة", "الضعف", "ضعف المبلغ",
                "يعيدها", "يرجعها", "أرباح", "ربح مضمون", "عائد مضمون", "استثمار مضمون", "double", "profit")) {
            return "وعدٌ بمضاعفة المبلغ أو بعائد/أرباح سريعة مقابل التحويل (احتيال الدفع المسبق)";
        }
        if (containsAny(a, "أمير", "امير", "نيجير", "prince", "nigeria", "ميراث", "ورثة", "إرث",
                "جائزة", "يانصيب", "لوتري", "فزت", "ربحت", "lottery", "inheritance")) {
            return "قصة أمير/ميراث/جائزة/يانصيب مقابل تحويل رسوم (احتيال كلاسيكي)";
        }
        if (containsAny(a, "بيتكوين", "بتكوين", "عملة رقمية", "عملات رقمية", "كريبتو", "فوركس", "تداول",
                "crypto", "bitcoin", "forex")) {
            return "استثمار في عملات رقمية/فوركس بربح مؤكد عبر جهة مجهولة";
        }
        if (containsAny(a, "دعم البنك", "موظف البنك", "أمن البنك", "الشرطة", "جهة حكومية", "شركة شحن",
                "تحديث بيانات", "حسابك مخترق", "حسابك معلّق", "حسابك معلق")) {
            return "جهة تنتحل صفة رسمية (بنك/حكومة/شرطة) تطلب التحويل";
        }
        if (containsAny(a, "لم أقابله", "لم ألتقِ", "لم التقِ", "تعرفت عليه عبر", "عبر الإنترنت",
                "عبر الانترنت", "أونلاين", "اونلاين")) {
            return "توجيه بالتحويل من شخص لم يُقابَل وجهًا لوجه (تعرّف عبر الإنترنت)";
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
