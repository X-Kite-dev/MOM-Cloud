package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmRepairRemindEntity;

import java.util.ArrayList;

public interface TpmRepairRemindService extends IService<TpmRepairRemindEntity> {

    Page<TpmRepairRemindEntity> pageList(Page toMpPage, TpmRepairRemindEntity tpmRepairRemind);

    TpmRepairRemindEntity getRemindById(Long id);

    Boolean saveRemind(TpmRepairRemindEntity tpmRepairRemind);

    Boolean updateRemindById(TpmRepairRemindEntity tpmRepairRemind);

    Boolean removeRemindById(ArrayList<Long> toList);
}