package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationActionDTO;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationSummaryDTO;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;

import java.time.LocalDate;
import java.util.List;

public interface MdmMaterialAuthorizationService extends IService<MdmMaterialAuthorizationEntity> {
    Long saveDraft(MdmMaterialAuthorizationEntity entity);

    Boolean updateDraft(MdmMaterialAuthorizationEntity entity);

    Boolean changeStatus(MdmMaterialAuthorizationActionDTO action);

    List<MdmMaterialAuthorizationEntity> active(String type, String materialCode, String partnerCode, LocalDate bizDate);

    MdmMaterialAuthorizationSummaryDTO summary(String type, String partnerCode, String materialCode);

    MdmMaterialAuthorizationEntity enrichValidity(MdmMaterialAuthorizationEntity entity);
}
