package com.jiejie.exam.exam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机与枚举的迁移规则。
 *
 * <p>这些测试看起来琐碎，但"枚举顺序变了导致历史数据错位"这类事故
 * 就是靠它们挡住的：库里存的是 code 字符串，不是序号。
 */
class StateMachineTest {

    @Test
    void 题型按code存取不是按序号() {
        assertEquals("single", QuestionType.SINGLE.code());
        assertEquals("multiple", QuestionType.MULTIPLE.code());
        assertEquals("judge", QuestionType.JUDGE.code());
        assertEquals(QuestionType.MULTIPLE, QuestionType.of("multiple"));
    }

    @Test
    void 未知题型直接抛错而不是默默用默认值() {
        assertThrows(IllegalArgumentException.class, () -> QuestionType.of("fill-blank"));
    }

    @Test
    void 考试状态只能单向推进() {
        assertTrue(ExamStatus.DRAFT.canTransferTo(ExamStatus.PUBLISHED));
        assertTrue(ExamStatus.PUBLISHED.canTransferTo(ExamStatus.CLOSED));
        assertFalse(ExamStatus.PUBLISHED.canTransferTo(ExamStatus.DRAFT));
        assertFalse(ExamStatus.CLOSED.canTransferTo(ExamStatus.PUBLISHED));
    }

    @Test
    void 答卷状态不能从已判分回退() {
        assertTrue(RecordStatus.ONGOING.canTransferTo(RecordStatus.SUBMITTED));
        assertTrue(RecordStatus.SUBMITTED.canTransferTo(RecordStatus.GRADED));
        assertFalse(RecordStatus.SUBMITTED.canTransferTo(RecordStatus.ONGOING));
        assertFalse(RecordStatus.GRADED.canTransferTo(RecordStatus.SUBMITTED));
        assertFalse(RecordStatus.ONGOING.canTransferTo(RecordStatus.GRADED));
    }

    @Test
    void 只有已发布的考试允许学生进入() {
        assertTrue(ExamStatus.ENTERABLE.contains(ExamStatus.PUBLISHED.code()));
        assertFalse(ExamStatus.ENTERABLE.contains(ExamStatus.DRAFT.code()));
        assertFalse(ExamStatus.ENTERABLE.contains(ExamStatus.CLOSED.code()));
    }
}
