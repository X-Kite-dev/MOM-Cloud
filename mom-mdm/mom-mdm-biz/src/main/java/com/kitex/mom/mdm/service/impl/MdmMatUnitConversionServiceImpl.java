package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;
import com.kitex.mom.mdm.mapper.MdmMatUnitConversionMapper;
import com.kitex.mom.mdm.mapper.MdmMaterialMapper;
import com.kitex.mom.mdm.mapper.MdmPhysicalTransformStandardMapper;
import com.kitex.mom.mdm.service.MdmMatUnitConversionService;
import com.kitex.mom.mdm.vo.excel.MdmMatUnitConversionExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 物料单位
 *
 * @author kitex
 * @date 2024-12-12 10:59:24
 */
@Service
@AllArgsConstructor
public class MdmMatUnitConversionServiceImpl extends ServiceImpl<MdmMatUnitConversionMapper, MdmMatUnitConversionEntity> implements MdmMatUnitConversionService {

    private final MdmMaterialMapper materials;
    private final MdmPhysicalTransformStandardMapper standards;
    private final MdmUnitMaintenanceLock unitLock;

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveMaintained(MdmMatUnitConversionEntity row) {
        unitLock.acquire();
        return saveChecked(row);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMaintained(MdmMatUnitConversionEntity row) {
        unitLock.acquire();
        require(row != null && row.getId() != null, "请选择原换算记录");
        MdmMatUnitConversionEntity original = getOne(Wrappers.<MdmMatUnitConversionEntity>lambdaQuery()
                .eq(MdmMatUnitConversionEntity::getId, row.getId())
                .eq(MdmMatUnitConversionEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmMatUnitConversionEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .last("FOR UPDATE"));
        require(original != null, "换算不存在或不属于当前租户工厂");
        validate(row);
        require(Objects.equals(original.getMatCode(), row.getMatCode())
                && Objects.equals(original.getNonStandardUnit(), row.getNonStandardUnit())
                && Objects.equals(original.getStandardUnit(), row.getStandardUnit()), "已有换算的物料及单位不能改绑，请新建换算");
        if (!sameRatio(row.getRatio(), original.getRatio())) {
            require(standards.selectCount(Wrappers.<MdmPhysicalTransformStandardEntity>lambdaQuery()
                    .eq(MdmPhysicalTransformStandardEntity::getTenantId, row.getTenantId())
                    .eq(MdmPhysicalTransformStandardEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmPhysicalTransformStandardEntity::getUnitConversionId, row.getId())) == 0,
                    "该换算已被实物转换标准引用，请先通过原工程变更流程处理");
        }
        require(super.updateById(row), "换算未更新，请刷新后核对原记录");
        return true;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean importMaintained(List<MdmMatUnitConversionEntity> rows) {
        unitLock.acquire();
        require(rows != null && !rows.isEmpty(), "导入内容不能为空");
        for (int index = 0; index < rows.size(); index++) {
            try {
                saveChecked(rows.get(index));
            } catch (CheckedException error) {
                throw new CheckedException("导入第" + (index + 1) + "条数据：" + error.getMessage());
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMaintained(List<Long> ids) {
        unitLock.acquire();
        require(ids != null && !ids.isEmpty(), "请选择待删除换算");
        for (Long id : ids.stream().distinct().toList()) {
            require(id != null, "换算ID不能为空");
            MdmMatUnitConversionEntity row = getOne(Wrappers.<MdmMatUnitConversionEntity>lambdaQuery()
                    .eq(MdmMatUnitConversionEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MdmMatUnitConversionEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                    .eq(MdmMatUnitConversionEntity::getId, id).last("FOR UPDATE"));
            require(row != null, "待删除换算不存在或不属于当前组织");
            require(standards.selectCount(Wrappers.<MdmPhysicalTransformStandardEntity>lambdaQuery()
                    .eq(MdmPhysicalTransformStandardEntity::getTenantId, row.getTenantId())
                    .eq(MdmPhysicalTransformStandardEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmPhysicalTransformStandardEntity::getUnitConversionId, id)) == 0,
                    "换算已被实物转换标准引用，不能删除");
            require(remove(Wrappers.<MdmMatUnitConversionEntity>lambdaQuery()
                    .eq(MdmMatUnitConversionEntity::getId, id)
                    .eq(MdmMatUnitConversionEntity::getTenantId, row.getTenantId())
                    .eq(MdmMatUnitConversionEntity::getFactoryCode, row.getFactoryCode())), "换算删除失败");
        }
        return true;
    }

    /** 在同一事务中校验并新增，不接受导入表格指定历史主键。 */
    private boolean saveChecked(MdmMatUnitConversionEntity row) {
        require(row != null && row.getId() == null, "新增换算不能携带历史主键");
        validate(row);
        require(count(Wrappers.<MdmMatUnitConversionEntity>lambdaQuery()
                .eq(MdmMatUnitConversionEntity::getTenantId, row.getTenantId())
                .eq(MdmMatUnitConversionEntity::getFactoryCode, row.getFactoryCode())
                .eq(MdmMatUnitConversionEntity::getMatCode, row.getMatCode())
                .eq(MdmMatUnitConversionEntity::getNonStandardUnit, row.getNonStandardUnit())
                .eq(MdmMatUnitConversionEntity::getStandardUnit, row.getStandardUnit())) == 0, "同物料单位对已有换算，请维护原记录");
        require(super.save(row), "换算未保存");
        return true;
    }

    /** 正向语义为非标准数量乘比例；只接受普通正十进制文本，避免浮点及科学记数精度变化。 */
    private void validate(MdmMatUnitConversionEntity row) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(row.getTenantId() == null || tenant.equals(row.getTenantId()), "换算租户不一致");
        require(row.getFactoryCode() == null || factory.equals(row.getFactoryCode()), "换算工厂不一致");
        row.setTenantId(tenant);
        row.setFactoryCode(factory);
        require(row.getMatCode() != null && !row.getMatCode().isBlank(), "请选择物料");
        require(row.getNonStandardUnit() != null && !row.getNonStandardUnit().isBlank()
                && row.getStandardUnit() != null && !row.getStandardUnit().isBlank(), "请选择完整单位对");
        require(!row.getNonStandardUnit().equals(row.getStandardUnit()), "同单位无需配置换算");
        require(row.getRatio() != null && row.getRatio().matches("[0-9]+(?:\\.[0-9]+)?")
                && new BigDecimal(row.getRatio()).signum() > 0, "换算比率必须为大于0的普通十进制数");
        List<MdmMaterialEntity> matches = materials.selectList(Wrappers.<MdmMaterialEntity>lambdaQuery()
                .eq(MdmMaterialEntity::getTenantId, tenant).eq(MdmMaterialEntity::getFactoryCode, factory)
                .eq(MdmMaterialEntity::getMaterialCode, row.getMatCode()).last("LIMIT 2"));
        require(matches.size() == 1, "物料未唯一归属当前工厂");
        require(Objects.equals(matches.get(0).getBasicUnit(), row.getStandardUnit()), "标准单位必须为物料的基本单位");
        row.setMatName(matches.get(0).getMaterialName());
        row.setMatSpec(matches.get(0).getSpecificationModel());
    }

    /** 旧非法比例可以修复，但仍需经过工程引用保护，不将旧值猜成0。 */
    private static boolean sameRatio(String current, String previous) {
        try {
            return previous != null && new BigDecimal(current).compareTo(new BigDecimal(previous)) == 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMatUnit 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmMatUnitConversionExcelVO> exportExcel(MdmMatUnitConversionEntity mdmMatUnit, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(mdmMatUnit).in(ArrayUtil.isNotEmpty(ids), MdmMatUnitConversionEntity::getId, ids))
                .stream()
                .map(matUnit -> {
                    MdmMatUnitConversionExcelVO mdmMatUnitConversionExcelVO = new MdmMatUnitConversionExcelVO();
                    BeanUtils.copyProperties(matUnit, mdmMatUnitConversionExcelVO);
                    return mdmMatUnitConversionExcelVO;
                }).collect(Collectors.toList());
    }
}
