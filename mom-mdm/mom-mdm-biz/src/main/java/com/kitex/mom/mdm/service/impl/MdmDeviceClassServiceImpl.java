package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.mapper.MdmDeviceClassMapper;
import com.kitex.mom.mdm.mapper.MdmDeviceMapper;
import com.kitex.mom.mdm.service.MdmDeviceClassService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工装分类表（设备）
 *
 * @author kitex
 * @date 2025-02-05 10:28:48
 */
@Service
@AllArgsConstructor
public class MdmDeviceClassServiceImpl extends ServiceImpl<MdmDeviceClassMapper, MdmDeviceClassEntity> implements MdmDeviceClassService {

    public static final String RootNode = "-1";
    private final MdmDeviceMapper mdmDeviceMapper;

    @Override
    public List<MdmDeviceClassEntity> tree(MdmDeviceClassEntity mdmDeviceClass) {
        if (StrUtil.isNotBlank(mdmDeviceClass.getCode()) || StrUtil.isNotBlank(mdmDeviceClass.getName())) {
            return this.list(new LambdaQueryWrapper<MdmDeviceClassEntity>()
                    .like(StrUtil.isNotBlank(mdmDeviceClass.getCode()), MdmDeviceClassEntity::getCode, mdmDeviceClass.getCode())
                    .like(StrUtil.isNotBlank(mdmDeviceClass.getName()), MdmDeviceClassEntity::getName, mdmDeviceClass.getName())
                    .orderByAsc(MdmDeviceClassEntity::getSortOrder).orderByDesc(MdmDeviceClassEntity::getCreateTime));
        }
        String parent = StrUtil.isNotBlank(mdmDeviceClass.getParentCode()) ? mdmDeviceClass.getParentCode() : RootNode;
        List<MdmDeviceClassEntity> allList = this.list(new LambdaQueryWrapper<MdmDeviceClassEntity>()
                .orderByAsc(MdmDeviceClassEntity::getSortOrder).orderByDesc(MdmDeviceClassEntity::getCreateTime));
        List<MdmDeviceClassEntity> fatherList = allList.stream().filter(item -> parent.equals(item.getParentCode())).toList();
        return buildTree(fatherList, allList);
    }

    private List<MdmDeviceClassEntity> buildTree(List<MdmDeviceClassEntity> fatherList, List<MdmDeviceClassEntity> allList) {
        for (MdmDeviceClassEntity father : fatherList) {
            List<MdmDeviceClassEntity> children = allList.stream().filter(item -> item.getParentCode().equals(father.getCode())).toList();
            if (CollUtil.isNotEmpty(children)) {
                father.setChildren(children);
                buildTree(father.getChildren(), allList);
            }
        }
        return fatherList;
    }

    /**
     * 根据设备分类id删除设备分类
     */
    @Override
    public Boolean removeDeviceClassById(ArrayList<Long> toList) {
        List<MdmDeviceClassEntity> mdmDeviceClassList = this.listByIds(toList);
        List<String> classCodes = mdmDeviceClassList.stream().map(MdmDeviceClassEntity::getCode).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(classCodes)) {
            Long aLong = mdmDeviceMapper.selectCount(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .in(MdmDeviceEntity::getClassCode, classCodes));
            if (aLong.compareTo(0L) > 0) {
                throw new RuntimeException("删除的设备分类存在绑定中的设备，请先解绑数据！");
            }
        }
        return this.removeBatchByIds(toList);
    }


}