package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialCompensationCommandDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileCompensationEntity;

import java.util.List;

public interface MesMaterialReconcileCompensationService extends IService<MesMaterialReconcileCompensationEntity> {
    List<MesMaterialReconcileCompensationEntity> listByIssue(Long issueId);

    MesMaterialReconcileCompensationEntity prepare(MesMaterialCompensationCommandDTO command);

    MesMaterialReconcileCompensationEntity approve(MesMaterialCompensationCommandDTO command);

    MesMaterialReconcileCompensationEntity execute(MesMaterialCompensationCommandDTO command);

    MesMaterialReconcileCompensationEntity cancel(MesMaterialCompensationCommandDTO command);
}
