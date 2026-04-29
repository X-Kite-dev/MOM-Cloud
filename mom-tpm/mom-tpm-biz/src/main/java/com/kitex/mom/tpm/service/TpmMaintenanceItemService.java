package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.entity.TpmMaintenanceItemEntity;
import com.kitex.mom.tpm.vo.TpmMaintenanceItemVo;
import org.springframework.validation.BindingResult;

import java.util.ArrayList;
import java.util.List;

public interface TpmMaintenanceItemService extends IService<TpmMaintenanceItemEntity> {

    Boolean removeItemById(ArrayList<Long> toList);

    R importExcel(List<TpmMaintenanceItemVo> tpmMaintenanceItemVoList, BindingResult bindingResult, String factoryCode);
}