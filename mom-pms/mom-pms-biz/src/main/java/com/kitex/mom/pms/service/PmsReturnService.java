package com.kitex.mom.pms.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.entity.PmsReturnEntity;

public interface PmsReturnService extends MPJDeepService<PmsReturnEntity> {
    Boolean saveDeep(PmsReturnEntity pmsReturn);

    Boolean updateDeep(PmsReturnEntity pmsReturn);

    Boolean removeDeep(Long[] ids);
}