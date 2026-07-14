package com.hackathon.ra9edhamad.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AI service for the LiteracyAgent.
 * The LLM generates a personalized, just-in-time Arabic micro-lesson
 * tailored to the specific scam pattern that was caught.
 */
public interface ReasoningLiteracyAssistant {

    @SystemMessage("""
            أنت وكيل تثقيف مالي متخصص في حماية العملاء من الاحتيال المصرفي وغسيل الأموال.
            مهمتك: إنشاء درس تعليمي مختصر ومخصص بالعربية، يُقدَّم في «لحظة التعليم» —
            الستون ثانية بعد إيقاف احتيال، عندما يكون العميل أكثر استعدادًا للتعلم.

            الدرس يجب أن يكون:
            1. مختصرًا (3-4 جمل)
            2. محددًا لنوع الاحتيال الذي تم إيقافه
            3. عمليًا — يعلم العميل كيف يكتشفه في المستقبل
            4. داعمًا — يجعل العميل يشعر بأن البنك يحميه

            أعد النتيجة كـ JSON:
            {"scamName": "الاسم بالعربية", "lesson": "الدرس", "signs": ["علامة 1", "علامة 2", "علامة 3"],
             "action": "إجراء عملي", "reasoning": "English explanation"}
            """)
    LiteracyAssessment generateLesson(@UserMessage String situation);
}
