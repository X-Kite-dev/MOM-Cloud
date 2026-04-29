package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotGatewayEntity;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;

import java.util.List;
import java.util.Map;

/**
 * IoT网关管理服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotGatewayService extends IService<IotGatewayEntity> {

    /**
     * 分页查询网关列表
     *
     * @param page    分页参数
     * @param gateway 查询条件
     * @return 分页结果
     */
    IPage<IotGatewayEntity> queryPage(Page<IotGatewayEntity> page, IotGatewayEntity gateway);

    /**
     * 创建网关
     *
     * @param gateway 网关信息
     * @return 是否成功
     */
    boolean createGateway(IotGatewayEntity gateway);

    /**
     * 更新网关
     *
     * @param gateway 网关信息
     * @return 是否成功
     */
    boolean updateGateway(IotGatewayEntity gateway);

    /**
     * 删除网关
     *
     * @param id 网关ID
     * @return 是否成功
     */
    boolean removeGateway(List<String> ids);

    /**
     * 更新网关状态
     *
     * @param id     网关ID
     * @param status 状态
     * @return 是否成功
     */
    boolean updateStatus(Long id, String status);

    /**
     * 根据网络组件ID获取网关列表
     *
     * @param networkComponentId 网络组件ID
     * @return 网关列表
     */
    List<IotGatewayEntity> getByNetworkComponentId(Long networkComponentId);

    /**
     * 启动网关
     *
     * @param id 网关ID
     * @return 是否成功
     */
    void startGateway(Long id);

    /**
     * 停止网关
     *
     * @param id 网关ID
     * @return 是否成功
     */
    void stopGateway(Long id);

    /**
     * 获取网关运行状态
     *
     * @param id 网关ID
     * @return 是否运行中
     */
    boolean isGatewayRunning(Long id);

    /**
     * 根据网络组件ID获取网络组件信息
     *
     * @param networkComponentId 网络组件ID
     * @return 网络组件信息
     */
    IotNetworkComponentEntity getNetworkComponentById(Long networkComponentId);

    Map<String, Boolean> getAllGatewayStatus();

    List<IotGatewayEntity> queryList(IotGatewayEntity gateway);
}