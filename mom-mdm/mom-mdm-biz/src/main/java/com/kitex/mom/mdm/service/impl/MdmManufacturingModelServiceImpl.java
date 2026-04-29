package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmManufacturingModelEntity;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmManufacturingModelMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmManufacturingModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 制造模型服务实现
 *
 * @author claude
 * @date 2025-08-11
 */
@Service
@RequiredArgsConstructor
public class MdmManufacturingModelServiceImpl extends ServiceImpl<MdmManufacturingModelMapper, MdmManufacturingModelEntity> implements MdmManufacturingModelService {

    private final MdmFactoryModelService mdmFactoryModelService;

    /**
     * 根据车间编码获取制造模型
     *
     * @param workshopCode
     * @return
     */
    @Override
    public MdmManufacturingModelEntity getByWorkshop(String workshopCode) {
        MdmManufacturingModelEntity mdmManufacturingModelEntity = baseMapper.selectOne(Wrappers.<MdmManufacturingModelEntity>lambdaQuery()
                .eq(MdmManufacturingModelEntity::getWorkshopCode, workshopCode));
        if (mdmManufacturingModelEntity != null) {
            return mdmManufacturingModelEntity;
        } else {
            throw new CheckedException("制造模型不存在或者未配置");
        }

    }

    /**
     * 获取制造模型列表
     *
     * @return 制造模型列表
     */
    @Override
    public List<MdmManufacturingModelEntity> getManufacturingModelList(MdmManufacturingModelEntity query) {
        // 获取车间
        List<MdmFactoryModelEntity> factoryModelList = mdmFactoryModelService.list(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode())
                .like(StrUtil.isNotBlank(query.getWorkshopCode()), MdmFactoryModelEntity::getCode, query.getWorkshopCode())
                .like(StrUtil.isNotBlank(query.getWorkshopName()), MdmFactoryModelEntity::getName, query.getWorkshopName()));
        if (factoryModelList.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> workshopCodes = factoryModelList.stream().map(MdmFactoryModelEntity::getCode).collect(Collectors.toList());
        // 构造制造模型map
        Map<String, MdmManufacturingModelEntity> manufacturingModelMap = this.list(new LambdaQueryWrapper<MdmManufacturingModelEntity>()
                        .in(MdmManufacturingModelEntity::getWorkshopCode, workshopCodes))
                .stream()
                .collect(Collectors.toMap(MdmManufacturingModelEntity::getWorkshopCode, Function.identity()));
        // 构造制造模型返回列表
        List<MdmManufacturingModelEntity> returnList = new ArrayList<>(factoryModelList.size());
        for (MdmFactoryModelEntity workshop : factoryModelList) {
            MdmManufacturingModelEntity model = manufacturingModelMap.get(workshop.getCode());
            if (model == null) {
                model = new MdmManufacturingModelEntity();
                model.setWorkshopCode(workshop.getCode());
                model.setWorkshopName(workshop.getName());
            }
            returnList.add(model);
        }
        return returnList;
    }

    /**
     * 更新制造模型
     *
     * @param manufacturingModelEntityList 制造模型信息
     * @return 更新结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateBatchManufacturingModel(List<MdmManufacturingModelEntity> manufacturingModelEntityList) {
        return this.saveOrUpdateBatch(manufacturingModelEntityList);
    }
}