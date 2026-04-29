package com.kitex.mom.mdm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.UserFactoryModelBindDTO;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmUserFactoryModelBindService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户-工厂建模权限关联 Service 实现类
 *
 * @author claude
 * @date 2025-07-30
 */
@Service
@RequiredArgsConstructor
public class MdmUserFactoryModelBindServiceImpl extends ServiceImpl<MdmUserFactoryModelBindMapper, MdmUserFactoryModelBindEntity> implements MdmUserFactoryModelBindService {

    private final MdmFactoryModelService mdmFactoryModelService;

    private final RemoteUserService remoteUserService;

    @Override
    public List<MdmUserFactoryModelBindEntity> getByUserId(Long userId) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, userId)
                .eq(MdmUserFactoryModelBindEntity::getStatus, true)
                .orderByAsc(MdmUserFactoryModelBindEntity::getNodeLevel)
                .orderByAsc(MdmUserFactoryModelBindEntity::getFactoryModelCode);
        return list(wrapper);
    }

    @Override
    public List<MdmUserFactoryModelBindEntity> getByUserIdAndNodeType(Long userId, String nodeType) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, userId)
                .eq(MdmUserFactoryModelBindEntity::getNodeType, nodeType)
                .eq(MdmUserFactoryModelBindEntity::getStatus, true)
                .orderByAsc(MdmUserFactoryModelBindEntity::getFactoryModelCode);
        return list(wrapper);
    }

    /**
     * 批量绑定用户权限
     *
     * @param userFactoryModelBindDTO 用户工厂建模权限绑定DTO
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchBindUser(UserFactoryModelBindDTO userFactoryModelBindDTO) {
        // 参数与空集合校验
        List<UserFactoryModelBindDTO.UserDTO> users = userFactoryModelBindDTO.getUsers();
        if (users == null || users.isEmpty()) {
            throw new CheckedException("请选择用户");
        }

        List<UserFactoryModelBindDTO.FactoryModelDTO> factoryModels = userFactoryModelBindDTO.getFactoryModels();
        if (factoryModels == null || factoryModels.isEmpty()) {
            throw new CheckedException("请选择工厂");
        }

        // 删除用户权限（仅当有 userId 时）
        List<Long> userIds = users.stream()
                .map(UserFactoryModelBindDTO.UserDTO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (!userIds.isEmpty()) {
            this.remove(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>()
                    .in(MdmUserFactoryModelBindEntity::getUserId, userIds));
        }

        // 保存用户权限（预分配容量，避免中间集合）
        int expectedSize = users.size() * factoryModels.size();
        List<MdmUserFactoryModelBindEntity> entities = new ArrayList<>(expectedSize);
        for (UserFactoryModelBindDTO.UserDTO user : users) {
            for (UserFactoryModelBindDTO.FactoryModelDTO factoryModel : factoryModels) {
                MdmUserFactoryModelBindEntity binding = new MdmUserFactoryModelBindEntity();
                binding.setUserId(user.getUserId());
                binding.setUserName(user.getUsername());
                binding.setName(user.getName());
                binding.setFactoryModelId(factoryModel.getId());
                binding.setFactoryModelCode(factoryModel.getCode());
                binding.setFactoryModelName(factoryModel.getName());
                binding.setNodeLevel(factoryModel.getLevel());
                binding.setNodeType(factoryModel.getType());
                binding.setPermissionType(FactoryModelPermissionEnum.ADMIN.getCode());
                binding.setStatus(Boolean.TRUE);
                entities.add(binding);
            }
        }

        if (entities.isEmpty()) {
            return true;
        }
        // 分批写入，降低内存与 SQL 包大小压力
        return this.saveBatch(entities, 1000);
    }

    /**
     * 解绑权限
     *
     * @param ids 主键ids
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unbindUser(Long[] ids) {
        return this.removeBatchByIds(Arrays.asList(ids));
    }

    @Override
    public String getUserPermission(Long userId, Long factoryModelId) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, userId)
                .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, factoryModelId)
                .eq(MdmUserFactoryModelBindEntity::getStatus, 1)
                .last("LIMIT 1");

        MdmUserFactoryModelBindEntity binding = getOne(wrapper);
        return binding != null ? binding.getPermissionType() : null;
    }

    @Override
    public boolean hasPermission(Long userId, Long factoryModelId, String permissionType) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, userId)
                .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, factoryModelId)
                .eq(MdmUserFactoryModelBindEntity::getPermissionType, permissionType)
                .eq(MdmUserFactoryModelBindEntity::getStatus, 1);

        return count(wrapper) > 0;
    }
}