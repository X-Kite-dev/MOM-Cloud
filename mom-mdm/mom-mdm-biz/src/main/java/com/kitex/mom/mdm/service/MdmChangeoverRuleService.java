package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogQueryDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverRuleActionDTO;
import com.kitex.mom.mdm.entity.MdmChangeoverRuleEntity;

public interface MdmChangeoverRuleService extends IService<MdmChangeoverRuleEntity> {
    Long saveDraft(MdmChangeoverRuleEntity entity);

    Boolean updateDraft(MdmChangeoverRuleEntity entity);

    Boolean changeStatus(MdmChangeoverRuleActionDTO action);

    MdmChangeoverCatalogDTO catalog(MdmChangeoverCatalogQueryDTO query);
}
