package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;

/**
 * 质检任务生成规则
 *
 * @author kitex
 * @since 2024-02-06
 */
public interface MesInspectionRuleService extends IService<MesInspectionRuleEntity> {

    /**
     * 保存
     *
     * @param rule
     */
    void saveData(MesInspectionRuleEntity rule);

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    MesInspectionRuleEntity detailById(Long id);

    /**
     * 根据物料查询质检任务规则
     *
     * @param matCode
     * @return
     */
    MesInspectionRuleEntity getRuleByMatCode(String matCode);
} 