package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.dto.DeviceMetadataQueryDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;

import java.util.List;

/**
 * 设备元数据服务接口
 *
 * @author system
 */
public interface DeviceMetadataService {

    /**
     * 查询设备元数据
     *
     * @param page     分页参数
     * @param queryDTO 查询条件
     * @return 设备属性数据分页结果
     */
    IPage<IotDevicePropertyEntity> queryMetadata(Page<IotDevicePropertyEntity> page, DeviceMetadataQueryDTO queryDTO);

    /**
     * 获取产品物模型列表
     *
     * @param productId 产品ID
     * @return 物模型列表
     */
    List<IotProductModelEntity> getProductModels(Long productId);

    /**
     * 获取产品设备列表
     *
     * @param productId 产品ID
     * @return 设备列表
     */
    List<IotDeviceEntity> getProductDevices(Long productId);
} 