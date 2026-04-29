package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSparePartsEntity;

import java.util.ArrayList;

public interface TpmSparePartsService extends IService<TpmSparePartsEntity> {

    Boolean removeSparePartsById(ArrayList<Long> toList);
}