package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;

import java.util.ArrayList;
import java.util.List;

public interface MdmDeviceClassService extends IService<MdmDeviceClassEntity> {

    /**
     * 获取设备分类树形结构
     */
    List<MdmDeviceClassEntity> tree(MdmDeviceClassEntity mdmDeviceClass);

    Boolean removeDeviceClassById(ArrayList<Long> toList);

}