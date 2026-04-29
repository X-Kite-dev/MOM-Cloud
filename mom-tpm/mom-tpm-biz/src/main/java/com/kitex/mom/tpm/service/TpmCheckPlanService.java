package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.tpm.entity.TpmCheckPlanEntity;

import java.util.List;
import java.util.Map;

public interface TpmCheckPlanService extends IService<TpmCheckPlanEntity> {

    List<MdmDeviceClassEntity> getDevice();

    Page<TpmCheckPlanEntity> pageList(Page toMpPage, TpmCheckPlanEntity tpmCheckPlan);

    Map<String, Long> getCount(TpmCheckPlanEntity tpmCheckPlan);


    Boolean savePlan(TpmCheckPlanEntity tpmCheckPlan);

}