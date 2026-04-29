package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmCheckItemEntity;

import java.util.ArrayList;

public interface TpmCheckItemService extends IService<TpmCheckItemEntity> {

    Boolean removeItemById(ArrayList<Long> toList);
}