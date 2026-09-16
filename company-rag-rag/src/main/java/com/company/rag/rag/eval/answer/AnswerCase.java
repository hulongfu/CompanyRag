package com.company.rag.rag.eval.answer;

/** 待评估的一条问答样本。 */
public record AnswerCase(String query, String context, String answer) {}