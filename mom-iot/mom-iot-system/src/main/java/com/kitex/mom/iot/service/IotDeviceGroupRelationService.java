package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotDeviceGroupRelationDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDeviceGroupRelationEntity;

/**
 * IoT设备分组关联服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDeviceGroupRelationService extends IService<IotDeviceGroupRelationEntity> {

    /**
     * 分页查询分组下的设备
     *
     * @param page    分页参数
     * @param groupId 分组ID
     * @param device  查询条件
     * @return 分页结果
     */
    IPage<IotDeviceEntity> getDevicesByGroupId(Page<IotDeviceEntity> page, Long groupId, IotDeviceEntity device);

    /**
     * 添加设备到分组
     *
     * @param relationDTO 关联信息
     * @return 操作结果
     */
    Boolean addDevicesToGroup(IotDeviceGroupRelationDTO relationDTO);

    /**
     * 从分组中移除设备
     *
     * @param relationDTO 关联信息
     * @return 操作结果
     */
    Boolean removeDevicesFromGroup(IotDeviceGroupRelationDTO relationDTO);

    /**
     * 移除设备的所有分组关联
     *
     * @param deviceId 设备ID
     * @return 操作结果
     */
    Boolean removeAllGroupsByDeviceId(Long deviceId);

    /**
     * 移除分组的所有设备关联
     *
     * @param groupId 分组ID
     * @return 操作结果
     */
    Boolean removeAllDevicesByGroupId(Long groupId);
} 