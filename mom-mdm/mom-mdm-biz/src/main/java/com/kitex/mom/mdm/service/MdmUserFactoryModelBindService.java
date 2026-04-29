package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.UserFactoryModelBindDTO;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;

import java.util.List;

/**
 * 用户-工厂建模权限关联 Service 接口
 *
 * @author claude
 * @date 2025-07-30
 */
public interface MdmUserFactoryModelBindService extends IService<MdmUserFactoryModelBindEntity> {

    /**
     * 根据用户ID获取权限绑定列表
     *
     * @param userId 用户ID
     * @return 权限绑定列表
     */
    List<MdmUserFactoryModelBindEntity> getByUserId(Long userId);

    /**
     * 根据用户ID和节点类型获取权限绑定列表
     *
     * @param userId   用户ID
     * @param nodeType 节点类型
     * @return 权限绑定列表
     */
    List<MdmUserFactoryModelBindEntity> getByUserIdAndNodeType(Long userId, String nodeType);

    /**
     * 批量绑定用户权限
     *
     * @param userFactoryModelBindDTO 用户工厂建模权限绑定DTO
     * @return 操作结果
     */
    boolean batchBindUser(UserFactoryModelBindDTO userFactoryModelBindDTO);

    /**
     * 解绑权限
     *
     * @param ids 主键ids
     * @return 操作结果
     */
    boolean unbindUser(Long[] ids);

    /**
     * 获取用户在指定节点的权限类型
     *
     * @param userId         用户ID
     * @param factoryModelId 工厂建模节点ID
     * @return 权限类型
     */
    String getUserPermission(Long userId, Long factoryModelId);

    /**
     * 检查用户是否有指定节点的权限
     *
     * @param userId         用户ID
     * @param factoryModelId 工厂建模节点ID
     * @param permissionType 权限类型
     * @return 是否有权限
     */
    boolean hasPermission(Long userId, Long factoryModelId, String permissionType);
}