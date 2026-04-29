package com.kitex.mom.mdm.utils;

import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmUserFactoryModelBindService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工厂建模权限工具类
 * 支持层级权限检查和管理
 *
 * @author claude
 * @date 2025-07-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactoryModelPermissionUtil {

    private final MdmUserFactoryModelBindService userFactoryModelBindService;
    private final MdmFactoryModelService factoryModelService;

    /**
     * 检查用户是否有指定节点的权限（支持层级权限继承）
     *
     * @param userId             用户ID
     * @param factoryModelId     工厂建模节点ID
     * @param requiredPermission 需要的权限类型
     * @return 是否有权限
     */
    public boolean hasPermission(Long userId, Long factoryModelId, FactoryModelPermissionEnum requiredPermission) {
        if (userId == null || factoryModelId == null || requiredPermission == null) {
            return false;
        }

        // 获取用户的所有权限绑定
        List<MdmUserFactoryModelBindEntity> userBindings = userFactoryModelBindService.getByUserId(userId);
        if (userBindings.isEmpty()) {
            return false;
        }

        // 构建权限映射
        Map<Long, List<MdmUserFactoryModelBindEntity>> permissionMap = userBindings.stream()
                .collect(Collectors.groupingBy(MdmUserFactoryModelBindEntity::getFactoryModelId));

        // 1. 直接权限检查
        List<MdmUserFactoryModelBindEntity> directBindings = permissionMap.get(factoryModelId);
        if (directBindings != null) {
            for (MdmUserFactoryModelBindEntity binding : directBindings) {
                FactoryModelPermissionEnum userPermission = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
                if (userPermission != null && userPermission.hasPermission(requiredPermission)) {
                    return true;
                }
            }
        }

        // 2. 父级权限继承检查（如果父级有include_children=true的权限）
        return hasInheritedPermission(userId, factoryModelId, requiredPermission, userBindings);
    }

    /**
     * 检查继承权限
     */
    private boolean hasInheritedPermission(Long userId, Long factoryModelId,
                                           FactoryModelPermissionEnum requiredPermission,
                                           List<MdmUserFactoryModelBindEntity> userBindings) {
        try {
            // 获取当前节点信息
            MdmFactoryModelEntity currentNode = factoryModelService.getById(factoryModelId);
            if (currentNode == null || currentNode.getParentId() == null) {
                return false;
            }

            // 检查父级节点是否有包含子节点的权限
            List<MdmUserFactoryModelBindEntity> parentBindings = userBindings.stream()
                    .filter(binding -> binding.getFactoryModelId().equals(currentNode.getParentId()))
                    .collect(Collectors.toList());

            for (MdmUserFactoryModelBindEntity parentBinding : parentBindings) {
                FactoryModelPermissionEnum parentPermission = FactoryModelPermissionEnum.getByCode(parentBinding.getPermissionType());
                if (parentPermission != null && parentPermission.hasPermission(requiredPermission)) {
                    return true;
                }
            }

            // 递归检查更上级的父节点
            return hasInheritedPermission(userId, currentNode.getParentId(), requiredPermission, userBindings);
        } catch (Exception e) {
            log.error("检查继承权限时发生错误: userId={}, factoryModelId={}", userId, factoryModelId, e);
            return false;
        }
    }

    /**
     * 获取用户在指定层级的所有有权限的节点
     *
     * @param userId         用户ID
     * @param nodeType       节点类型
     * @param permissionType 权限类型
     * @return 有权限的节点ID列表
     */
    public List<Long> getAccessibleNodeIds(Long userId, FactoryModelTypeEnum nodeType, FactoryModelPermissionEnum permissionType) {
        List<MdmUserFactoryModelBindEntity> userBindings = userFactoryModelBindService.getByUserIdAndNodeType(userId, nodeType.getCode());

        return userBindings.stream()
                .filter(binding -> {
                    FactoryModelPermissionEnum userPermission = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
                    return userPermission != null && userPermission.hasPermission(permissionType);
                })
                .map(MdmUserFactoryModelBindEntity::getFactoryModelId)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户可访问的所有节点（包括继承权限）
     *
     * @param userId         用户ID
     * @param permissionType 权限类型
     * @return 可访问的节点ID集合
     */
    public Set<Long> getAllAccessibleNodeIds(Long userId, FactoryModelPermissionEnum permissionType) {
        List<MdmUserFactoryModelBindEntity> userBindings = userFactoryModelBindService.getByUserId(userId);
        Set<Long> accessibleIds = userBindings.stream()
                .filter(binding -> {
                    FactoryModelPermissionEnum userPermission = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
                    return userPermission != null && userPermission.hasPermission(permissionType);
                })
                .map(MdmUserFactoryModelBindEntity::getFactoryModelId)
                .collect(Collectors.toSet());

        // 添加通过include_children继承得到的权限节点
        List<MdmUserFactoryModelBindEntity> parentBindings = userBindings.stream()
                .filter(binding -> {
                    FactoryModelPermissionEnum userPermission = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
                    return userPermission != null && userPermission.hasPermission(permissionType);
                })
                .collect(Collectors.toList());

        for (MdmUserFactoryModelBindEntity parentBinding : parentBindings) {
            Set<Long> childrenIds = getChildrenNodeIds(parentBinding.getFactoryModelId());
            accessibleIds.addAll(childrenIds);
        }

        return accessibleIds;
    }

    /**
     * 获取指定节点的所有子节点ID
     */
    private Set<Long> getChildrenNodeIds(Long parentId) {
        // TODO: 这里需要实现获取子节点的逻辑，可以通过递归查询实现
        // 为了性能考虑，建议在factoryModelService中实现缓存机制
        return factoryModelService.getChildrenIds(parentId);
    }

    /**
     * 批量检查权限
     *
     * @param userId             用户ID
     * @param factoryModelIds    节点ID列表
     * @param requiredPermission 需要的权限
     * @return 权限检查结果映射
     */
    public Map<Long, Boolean> batchCheckPermission(Long userId, List<Long> factoryModelIds, FactoryModelPermissionEnum requiredPermission) {
        return factoryModelIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> hasPermission(userId, id, requiredPermission)
                ));
    }

    /**
     * 获取用户在不同层级的权限摘要
     *
     * @param userId 用户ID
     * @return 权限摘要
     */
    public UserPermissionSummary getUserPermissionSummary(Long userId) {
        List<MdmUserFactoryModelBindEntity> userBindings = userFactoryModelBindService.getByUserId(userId);

        UserPermissionSummary summary = new UserPermissionSummary();
        summary.setUserId(userId);
        summary.setTotalBindings(userBindings.size());

        // 按层级统计权限
        Map<Integer, Long> levelCounts = userBindings.stream()
                .collect(Collectors.groupingBy(MdmUserFactoryModelBindEntity::getNodeLevel, Collectors.counting()));
        summary.setLevelCounts(levelCounts);

        // 按权限类型统计
        Map<String, Long> permissionCounts = userBindings.stream()
                .collect(Collectors.groupingBy(MdmUserFactoryModelBindEntity::getPermissionType, Collectors.counting()));
        summary.setPermissionCounts(permissionCounts);

        return summary;
    }

    /**
     * 用户权限摘要
     */
    public static class UserPermissionSummary {
        private Long userId;
        private Integer totalBindings;
        private Map<Integer, Long> levelCounts;
        private Map<String, Long> permissionCounts;

        // getters and setters
        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public Integer getTotalBindings() {
            return totalBindings;
        }

        public void setTotalBindings(Integer totalBindings) {
            this.totalBindings = totalBindings;
        }

        public Map<Integer, Long> getLevelCounts() {
            return levelCounts;
        }

        public void setLevelCounts(Map<Integer, Long> levelCounts) {
            this.levelCounts = levelCounts;
        }

        public Map<String, Long> getPermissionCounts() {
            return permissionCounts;
        }

        public void setPermissionCounts(Map<String, Long> permissionCounts) {
            this.permissionCounts = permissionCounts;
        }
    }
}