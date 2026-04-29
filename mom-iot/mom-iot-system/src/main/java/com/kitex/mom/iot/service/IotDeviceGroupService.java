package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotDeviceGroupDTO;
import com.kitex.mom.iot.entity.IotDeviceGroupEntity;

import java.util.List;

/**
 * IoT设备分组服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDeviceGroupService extends IService<IotDeviceGroupEntity> {

    /**
     * 分页查询分组列表
     *
     * @param page  分页参数
     * @param group 查询条件
     * @return 分页结果
     */
    IPage<IotDeviceGroupEntity> getGroupPage(Page<IotDeviceGroupEntity> page, IotDeviceGroupEntity group);

    /**
     * 查询分组树
     *
     * @param parentId 父分组ID，为null时查询所有
     * @return 分组树
     */
    List<IotDeviceGroupEntity> getGroupTree(Long parentId);

    /**
     * 创建分组
     *
     * @param groupDTO 分组信息
     * @return 操作结果
     */
    Boolean createGroup(IotDeviceGroupDTO groupDTO);

    /**
     * 更新分组
     *
     * @param groupDTO 分组信息
     * @return 操作结果
     */
    Boolean updateGroup(IotDeviceGroupDTO groupDTO);

    /**
     * 删除分组
     *
     * @param id 分组ID
     * @return 操作结果
     */
    Boolean removeGroup(Long id);

    /**
     * 查询设备所属的分组
     *
     * @param deviceId 设备ID
     * @return 分组列表
     */
    List<IotDeviceGroupEntity> getGroupsByDeviceId(Long deviceId);
} 