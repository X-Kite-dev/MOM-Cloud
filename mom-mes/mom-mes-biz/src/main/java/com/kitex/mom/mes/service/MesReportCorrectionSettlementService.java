package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesReportCorrectionSettlementDTO;
import com.kitex.mom.mes.entity.MesReportCorrectionSettlementEntity;

/**
 * 聚合报工部分纠正后的差异结案、异岗复核和永久履历服务。
 */
public interface MesReportCorrectionSettlementService extends IService<MesReportCorrectionSettlementEntity> {
    IPage<MesReportCorrectionSettlementEntity> pageSettlements(Page<MesReportCorrectionSettlementEntity> page,
                                                               MesReportCorrectionSettlementEntity query);

    MesReportCorrectionSettlementDTO.Evidence preview(Long reverseRequestId);

    MesReportCorrectionSettlementDTO.Detail detail(Long settlementId);

    MesReportCorrectionSettlementEntity submit(MesReportCorrectionSettlementDTO.SubmitCommand command);

    MesReportCorrectionSettlementEntity review(MesReportCorrectionSettlementDTO.ReviewCommand command);

    MesReportCorrectionSettlementEntity withdraw(MesReportCorrectionSettlementDTO.WithdrawCommand command);
}
