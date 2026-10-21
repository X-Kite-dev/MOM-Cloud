package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;

public interface MesAutoReportRuleService extends IService<MesAutoReportRuleEntity> {
    IPage<MesAutoReportRuleEntity> queryPage(Page<MesAutoReportRuleEntity> page, MesAutoReportRuleEntity query);

    MesAutoReportRuleEntity saveRule(MesAutoReportRuleEntity rule);

    MesAutoReportRuleEntity changeStatus(Long id, boolean enabled);

    void deleteRule(Long id);
}
