package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanDemandChangeDTO;
import com.kitex.mom.plan.entity.PlnProductDemandChangeEntity;
import com.kitex.mom.plan.entity.PlnProductDemandChangeOperationEntity;

import java.util.List;

public interface PlnProductDemandChangeService {
    PlanDemandChangeDTO.Preview preview(PlanDemandChangeDTO.SubmitCommand command);

    PlnProductDemandChangeEntity submit(PlanDemandChangeDTO.SubmitCommand command);

    PlnProductDemandChangeEntity review(PlanDemandChangeDTO.ReviewCommand command);

    PlnProductDemandChangeEntity cancel(PlanDemandChangeDTO.CancelCommand command);

    PlnProductDemandChangeEntity detail(Long id);

    List<PlnProductDemandChangeOperationEntity> operations(Long id);

    Page<PlnProductDemandChangeEntity> page(SearchPage page, Long demandId, String status);
}
