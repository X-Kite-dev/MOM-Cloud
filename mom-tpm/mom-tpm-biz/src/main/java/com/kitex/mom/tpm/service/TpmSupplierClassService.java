package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSupplierClassEntity;

import java.util.ArrayList;

public interface TpmSupplierClassService extends IService<TpmSupplierClassEntity> {

    Boolean removeClass(ArrayList<Long> idList);

    Boolean updateClassById(TpmSupplierClassEntity tpmSupplierClass);
}