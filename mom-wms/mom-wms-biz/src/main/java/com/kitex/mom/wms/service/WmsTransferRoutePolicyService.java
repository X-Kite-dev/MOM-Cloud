package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsTransferRoutePolicyDTO;
import com.kitex.mom.wms.entity.WmsTransferRoutePolicyEntity;

import java.util.List;
import java.util.Map;

public interface WmsTransferRoutePolicyService extends IService<WmsTransferRoutePolicyEntity> {

    WmsTransferRoutePolicyEntity savePolicy(WmsTransferRoutePolicyEntity policy);

    WmsTransferRoutePolicyEntity getPolicy(Long id);

    Boolean removePolicies(List<Long> ids);

    Map<String, WmsTransferRoutePolicyDTO.RoutePlan> resolveBatch(WmsTransferRoutePolicyDTO.BatchQuery query);

    WmsTransferRoutePolicyDTO.RoutePlan preview(WmsTransferRoutePolicyDTO.PreviewQuery query);

    String routeKey(String sourceWarehouseCode, String targetWarehouseCode, String urgencyLevel);
}
