package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsTransferVarianceCaseEntity;
import com.kitex.mom.wms.vo.WmsTransferVarianceCaseVO;

public interface WmsTransferVarianceService {
    WmsTransferReceiptDTO.Result receive(WmsTransferReceiptDTO.Command command);

    Page<WmsTransferVarianceCaseEntity> queryCases(Page<WmsTransferVarianceCaseEntity> page,
                                                   WmsTransferVarianceDTO.CaseQuery query);

    WmsTransferVarianceCaseVO detail(Long caseId);

    void warehouseReview(WmsTransferVarianceDTO.ReviewCommand command);

    void planReview(WmsTransferVarianceDTO.ReviewCommand command);

    void qualityReview(WmsTransferVarianceDTO.ReviewCommand command);

    void resubmit(WmsTransferVarianceDTO.ResubmitCommand command);

    void dispose(WmsTransferVarianceDTO.DispositionCommand command);
}
