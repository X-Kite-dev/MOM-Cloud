package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesReportReverseRequestDTO;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;

/**
 * 报工冲销申请、异岗复核与永久履历服务。
 */
public interface MesReportReverseRequestService extends IService<MesReportReverseRequestEntity> {
    IPage<MesReportReverseRequestEntity> pageRequests(Page<MesReportReverseRequestEntity> page,
                                                      MesReportReverseRequestEntity query);

    MesReportReverseRequestDTO.Evidence preview(Long reportId, String correctionMode);

    MesReportReverseRequestDTO.Detail detail(Long requestId);

    MesReportReverseRequestEntity submit(MesReportReverseRequestDTO.SubmitCommand command);

    MesReportReverseRequestEntity review(MesReportReverseRequestDTO.ReviewCommand command);

    MesReportReverseRequestEntity withdraw(MesReportReverseRequestDTO.WithdrawCommand command);
}
