package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotDeviceGroupDTO;
import com.kitex.mom.iot.entity.IotDeviceGroupEntity;
import com.kitex.mom.iot.mapper.IotDeviceGroupMapper;
import com.kitex.mom.iot.service.IotDeviceGroupRelationService;
import com.kitex.mom.iot.service.IotDeviceGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * IoT设备分组服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceGroupServiceImpl extends ServiceImpl<IotDeviceGroupMapper, IotDeviceGroupEntity> implements IotDeviceGroupService {

    private final IotDeviceGroupRelationService deviceGroupRelationService;

    @Override
    public IPage<IotDeviceGroupEntity> getGroupPage(Page<IotDeviceGroupEntity> page, IotDeviceGroupEntity group) {
        LambdaQueryWrapper<IotDeviceGroupEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 设置查询条件
        if (group != null) {
            queryWrapper.like(StrUtil.isNotBlank(group.getGroupName()), IotDeviceGroupEntity::getGroupName, group.getGroupName())
                    .eq(StrUtil.isNotBlank(group.getGroupCode()), IotDeviceGroupEntity::getGroupCode, group.getGroupCode())
                    .eq(group.getParentId() != null, IotDeviceGroupEntity::getParentId, group.getParentId());
        }

        // 排序
        queryWrapper.orderByAsc(IotDeviceGroupEntity::getSort).orderByDesc(IotDeviceGroupEntity::getCreateTime);

        // 查询分页数据
        IPage<IotDeviceGroupEntity> pageResult = this.page(page, queryWrapper);

        // 查询设备数量
        if (CollUtil.isNotEmpty(pageResult.getRecords())) {
            for (IotDeviceGroupEntity record : pageResult.getRecords()) {
                Integer deviceCount = baseMapper.countDevicesByGroupId(record.getId());
                record.setDeviceCount(deviceCount);
            }
        }

        return pageResult;
    }

    @Override
    public List<IotDeviceGroupEntity> getGroupTree(Long parentId) {
        // 如果parentId为null，则查询顶级分组
        Long pid = parentId == null ? 0L : parentId;

        // 递归查询分组树
        return getChildrenGroups(pid);
    }

    /**
     * 递归查询子分组
     *
     * @param parentId 父分组ID
     * @return 子分组列表
     */
    private List<IotDeviceGroupEntity> getChildrenGroups(Long parentId) {
        // 查询当前层级的分组
        List<IotDeviceGroupEntity> groups = baseMapper.selectGroupTree(parentId);

        if (CollUtil.isNotEmpty(groups)) {
            for (IotDeviceGroupEntity group : groups) {
                // 递归查询子分组
                List<IotDeviceGroupEntity> children = getChildrenGroups(group.getId());
                group.setChildren(children);
            }
        }

        return groups;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createGroup(IotDeviceGroupDTO groupDTO) {
        // 检查分组编码是否已存在
        LambdaQueryWrapper<IotDeviceGroupEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceGroupEntity::getGroupCode, groupDTO.getGroupCode());
        if (this.count(queryWrapper) > 0) {
            throw new RuntimeException("分组编码已存在");
        }

        // 转换DTO为实体
        IotDeviceGroupEntity group = new IotDeviceGroupEntity();
        BeanUtil.copyProperties(groupDTO, group);

        // 设置默认值
        if (group.getParentId() == null) {
            group.setParentId(0L);
        }
        if (group.getSort() == null) {
            group.setSort(0);
        }

        // 保存分组
        return this.save(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateGroup(IotDeviceGroupDTO groupDTO) {
        // 检查分组是否存在
        IotDeviceGroupEntity existingGroup = this.getById(groupDTO.getId());
        if (existingGroup == null) {
            throw new RuntimeException("分组不存在");
        }

        // 检查分组编码是否已被其他分组使用
        if (!existingGroup.getGroupCode().equals(groupDTO.getGroupCode())) {
            LambdaQueryWrapper<IotDeviceGroupEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotDeviceGroupEntity::getGroupCode, groupDTO.getGroupCode());
            if (this.count(queryWrapper) > 0) {
                throw new RuntimeException("分组编码已存在");
            }
        }

        // 转换DTO为实体
        IotDeviceGroupEntity group = new IotDeviceGroupEntity();
        BeanUtil.copyProperties(groupDTO, group);

        // 更新分组
        return this.updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeGroup(Long id) {
        // 检查分组是否存在
        IotDeviceGroupEntity group = this.getById(id);
        if (group == null) {
            throw new RuntimeException("分组不存在");
        }

        // 检查是否有子分组
        LambdaQueryWrapper<IotDeviceGroupEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceGroupEntity::getParentId, id);
        if (this.count(queryWrapper) > 0) {
            throw new RuntimeException("请先删除子分组");
        }

        // 删除分组与设备的关联
        deviceGroupRelationService.removeAllDevicesByGroupId(id);

        // 删除分组
        return this.removeById(id);
    }

    @Override
    public List<IotDeviceGroupEntity> getGroupsByDeviceId(Long deviceId) {
        return baseMapper.selectList(
                new LambdaQueryWrapper<IotDeviceGroupEntity>()
                        .inSql(IotDeviceGroupEntity::getId, "SELECT group_id FROM iot_device_group_relation WHERE device_id = " + deviceId));
    }
} 