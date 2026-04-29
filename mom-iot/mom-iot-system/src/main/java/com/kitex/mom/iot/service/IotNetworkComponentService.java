package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;

import java.util.List;

/**
 * IoT网络组件服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotNetworkComponentService extends IService<IotNetworkComponentEntity> {

    /**
     * 分页查询网络组件
     *
     * @param page             分页参数
     * @param networkComponent 查询条件
     * @return 分页结果
     */
    IPage<IotNetworkComponentEntity> queryPage(Page<IotNetworkComponentEntity> page, IotNetworkComponentEntity networkComponent);

    /**
     * 新增网络组件
     *
     * @param networkComponent 网络组件信息
     * @return 是否成功
     */
    boolean saveNetworkComponent(IotNetworkComponentEntity networkComponent);

    /**
     * 更新网络组件
     *
     * @param networkComponent 网络组件信息
     * @return 是否成功
     */
    boolean updateNetworkComponent(IotNetworkComponentEntity networkComponent);

    /**
     * 删除网络组件
     *
     * @param ids 组件ID列表
     * @return 是否成功
     */
    boolean deleteNetworkComponent(List<Long> ids);


    /**
     * 根据类型获取网络组件列表
     *
     * @param type 组件类型
     * @return 网络组件列表
     */
    List<IotNetworkComponentEntity> getNetworkComponentsByType(String type);

    /**
     * 根据ID获取网络组件详情
     *
     * @param id 组件ID
     * @return 网络组件详情
     */
    IotNetworkComponentEntity getNetworkComponentById(Long id);
} 