package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.dto.MdmSchedulingFamilyAssignDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmSchedulingFamilyEntity;
import com.kitex.mom.mdm.mapper.MdmSchedulingFamilyMapper;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmSchedulingFamilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MdmSchedulingFamilyServiceImpl extends ServiceImpl<MdmSchedulingFamilyMapper, MdmSchedulingFamilyEntity>
        implements MdmSchedulingFamilyService {
    private final MdmMaterialService materialService;

    @Override
    public Boolean saveValidated(MdmSchedulingFamilyEntity entity) {
        if (entity == null) throw new CheckedException("排产产品族不能为空");
        entity.setFactoryCode(requireCurrentFactory());
        normalize(entity);
        assertUnique(entity);
        entity.setId(null);
        entity.setEnabled(entity.getEnabled() == null || entity.getEnabled());
        entity.setSortOrder(entity.getSortOrder() == null ? 100 : entity.getSortOrder());
        return save(entity);
    }

    @Override
    public Boolean updateValidated(MdmSchedulingFamilyEntity entity) {
        if (entity == null || entity.getId() == null) throw new CheckedException("排产产品族ID不能为空");
        MdmSchedulingFamilyEntity current = getById(entity.getId());
        if (current == null) throw new CheckedException("排产产品族不存在");
        entity.setFactoryCode(current.getFactoryCode());
        normalize(entity);
        if (!current.getFamilyCode().equals(entity.getFamilyCode())) {
            throw new CheckedException("产品族编码启用后不可修改，请新建版本并重新归属物料");
        }
        assertUnique(entity);
        if (Boolean.FALSE.equals(entity.getEnabled())) {
            long assigned = materialService.count(new LambdaQueryWrapper<MdmMaterialEntity>()
                    .eq(MdmMaterialEntity::getSchedulingFamilyCode, entity.getFamilyCode())
                    .eq(MdmMaterialEntity::getStatus, true));
            if (assigned > 0) throw new CheckedException("仍有" + assigned + "个启用物料归属该产品族，不能停用");
        }
        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean assignProducts(MdmSchedulingFamilyAssignDTO dto) {
        if (dto == null || StrUtil.isBlank(dto.getFamilyCode()) || CollUtil.isEmpty(dto.getProductCodes())) {
            throw new CheckedException("产品族和产品编码不能为空");
        }
        String factoryCode = requireCurrentFactory();
        MdmSchedulingFamilyEntity family = getOne(new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .eq(MdmSchedulingFamilyEntity::getFamilyCode, dto.getFamilyCode().trim())
                .eq(MdmSchedulingFamilyEntity::getFactoryCode, factoryCode)
                .eq(MdmSchedulingFamilyEntity::getEnabled, true), false);
        if (family == null) throw new CheckedException("排产产品族不存在或未启用");
        List<String> codes = dto.getProductCodes().stream().map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
        List<MdmMaterialEntity> materials = materialService.list(new LambdaQueryWrapper<MdmMaterialEntity>()
                .in(MdmMaterialEntity::getMaterialCode, codes)
                .eq(MdmMaterialEntity::getFactoryCode, factoryCode)
                .eq(MdmMaterialEntity::getStatus, true));
        if (materials.size() != codes.size())
            throw new CheckedException("部分产品不存在、重复或未启用，产品族归属未执行");
        return materialService.update(new LambdaUpdateWrapper<MdmMaterialEntity>()
                .in(MdmMaterialEntity::getMaterialCode, codes)
                .eq(MdmMaterialEntity::getFactoryCode, factoryCode)
                .set(MdmMaterialEntity::getSchedulingFamilyCode, family.getFamilyCode())
                .set(MdmMaterialEntity::getSchedulingFamilyName, family.getFamilyName()));
    }

    private void normalize(MdmSchedulingFamilyEntity entity) {
        if (entity == null) throw new CheckedException("排产产品族不能为空");
        entity.setFamilyCode(StrUtil.trim(entity.getFamilyCode()));
        entity.setFamilyName(StrUtil.trim(entity.getFamilyName()));
        if (StrUtil.hasBlank(entity.getFamilyCode(), entity.getFamilyName())) {
            throw new CheckedException("产品族编码和名称不能为空");
        }
    }

    private void assertUnique(MdmSchedulingFamilyEntity entity) {
        long count = count(new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .eq(MdmSchedulingFamilyEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmSchedulingFamilyEntity::getFamilyCode, entity.getFamilyCode())
                .ne(entity.getId() != null, MdmSchedulingFamilyEntity::getId, entity.getId()));
        if (count > 0) throw new CheckedException("产品族编码[" + entity.getFamilyCode() + "]已存在");
    }

    private String requireCurrentFactory() {
        String factoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        if (StrUtil.isBlank(factoryCode)) throw new CheckedException("请先选择当前工厂");
        return factoryCode;
    }
}
