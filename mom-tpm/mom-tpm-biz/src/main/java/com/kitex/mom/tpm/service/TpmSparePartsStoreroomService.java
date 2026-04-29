package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSparePartsStoreroomEntity;

import java.util.ArrayList;

public interface TpmSparePartsStoreroomService extends IService<TpmSparePartsStoreroomEntity> {

    Boolean removeStoreroomByIds(ArrayList<Long> toList);
}