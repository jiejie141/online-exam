package com.jiejie.exam.domain.dto;

import java.util.List;

/**
 * 分页结果。
 *
 * <p>为什么不直接把 MyBatis-Plus 的 {@code IPage} 返回给前端：它带着
 * current / size / total / pages / records / orders / optimizeCountSql 等一堆字段，
 * 其中一半是给「再查一次」用的内部状态。接口契约只该包含前端真正要用的四个 ——
 * 多出来的字段一旦被前端依赖上，以后换分页实现就成了破坏性变更。
 *
 * <p>字段名用 items 而不是 records：records 在本项目里已经是「答卷」
 * （{@code ExamRecord}）的意思。两个 records 混在同一份 JSON 里，
 * 读的人得先分辨说的是哪个。
 *
 * @param items 当前页的数据
 * @param total 满足条件的总条数（不是总页数）
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, long page, long size) {
}
