package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialReconcileActionDTO;
import com.kitex.mom.mes.dto.MesMaterialReconcileScanResultDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;

public interface MesMaterialReconcileIssueService extends IService<MesMaterialReconcileIssueEntity> {
    IPage<MesMaterialReconcileIssueEntity> queryPage(Page<MesMaterialReconcileIssueEntity> page,
                                                     MesMaterialReconcileIssueEntity query);

    MesMaterialReconcileScanResultDTO scan();

    MesMaterialReconcileIssueEntity action(MesMaterialReconcileActionDTO command);
}
