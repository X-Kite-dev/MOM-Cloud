package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsAllocationReturnExceptionDTO;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionLineEntity;

import java.util.List;

public interface WmsAllocationReturnExceptionService {
    Page<WmsAllocationReturnExceptionEntity> page(Page<WmsAllocationReturnExceptionEntity> page,
                                                    WmsAllocationReturnExceptionDTO.CaseQuery query);

    WmsAllocationReturnExceptionEntity report(WmsAllocationReturnExceptionDTO.Report command);

    WmsAllocationReturnExceptionEntity review(WmsAllocationReturnExceptionDTO.Review command);

    WmsAllocationReturnExceptionEntity detail(Long caseId);

    List<WmsAllocationReturnExceptionLineEntity> lines(Long caseId);
}
