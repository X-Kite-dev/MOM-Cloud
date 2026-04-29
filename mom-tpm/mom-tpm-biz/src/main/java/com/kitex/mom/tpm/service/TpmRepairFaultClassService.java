package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmRepairFaultClassEntity;

import java.util.ArrayList;

public interface TpmRepairFaultClassService extends IService<TpmRepairFaultClassEntity> {

    Boolean removeClassById(ArrayList<Long> toList);
}