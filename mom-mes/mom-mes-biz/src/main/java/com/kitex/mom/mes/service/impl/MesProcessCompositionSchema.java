package com.kitex.mom.mes.service.impl;

import com.kitex.mom.common.process.ProcessCompositionDefinition;
import com.kitex.mom.common.process.ProcessCompositionSchema;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;

import java.util.List;

/**
 * MES可执行职责在业务适配器声明；公共层不认识工单、工序或派工。
 */
public final class MesProcessCompositionSchema {

    private static final ProcessCompositionSchema SCHEMA = new ProcessCompositionSchema(
            ProcessCompositionSchema.CONTRACT_VERSION, "MES_DISCRETE_V1", 2, 4,
            List.of(new ProcessCompositionSchema.Role("ORDER", "生产订单"),
                    new ProcessCompositionSchema.Role("TASK", "生产工单"),
                    new ProcessCompositionSchema.Role("OPERATION", "工艺活动"),
                    new ProcessCompositionSchema.Role("ASSIGNMENT", "派工作业")),
            List.of(List.of("TASK", "OPERATION", "ASSIGNMENT"),
                    List.of("ORDER", "TASK", "OPERATION", "ASSIGNMENT")),
            List.of(List.of("OPERATION", "ASSIGNMENT")));

    private MesProcessCompositionSchema() {
    }

    public static ProcessCompositionSchema schema() {
        return SCHEMA;
    }

    /**
     * 显式转换保持原JSON字段不变，坏定义由公共校验返回字段问题。
     */
    public static ProcessCompositionDefinition definition(MesProcessCompositionDTO.Definition source) {
        if (source == null) {
            return null;
        }
        var levels = source.getLevels() == null ? null : source.getLevels().stream()
                .map(level -> level == null ? null : new ProcessCompositionDefinition.Level(
                        level.getKey(), level.getLabel(), level.getBindings()))
                .toList();
        return new ProcessCompositionDefinition(levels);
    }
}
