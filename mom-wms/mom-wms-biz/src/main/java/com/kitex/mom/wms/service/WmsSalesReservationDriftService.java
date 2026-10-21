package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.wms.dto.WmsSalesReservationDriftDTO;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftCaseEntity;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftOperationEntity;

import java.util.List;
import java.util.Map;

public interface WmsSalesReservationDriftService {
    Map<String, Integer> scan(WmsSalesReservationDriftDTO.Scan command);

    Page<WmsSalesReservationDriftCaseEntity> page(SearchPage page, WmsSalesReservationDriftDTO.Query query);

    WmsSalesReservationDriftCaseEntity detail(Long caseId);

    Map<String, Long> overview();

    WmsSalesReservationDriftCaseEntity submit(Long caseId, WmsSalesReservationDriftDTO.Submit command);

    WmsSalesReservationDriftCaseEntity review(Long caseId, WmsSalesReservationDriftDTO.Review command);

    List<WmsSalesReservationDriftOperationEntity> operations(Long caseId);
}
