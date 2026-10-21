package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsTransferVariancePolicyEntity;
import com.kitex.mom.wms.mapper.WmsTransferVariancePolicyMapper;
import com.kitex.mom.wms.service.WmsTransferVariancePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WmsTransferVariancePolicyServiceImpl
        extends ServiceImpl<WmsTransferVariancePolicyMapper, WmsTransferVariancePolicyEntity>
        implements WmsTransferVariancePolicyService {
    private static final String WILDCARD = "*";
    private final RemoteWarehouseService remoteWarehouseService;

    @Override
    public Page<WmsTransferVariancePolicyEntity> queryPage(Page<WmsTransferVariancePolicyEntity> page,
                                                           WmsTransferVarianceDTO.PolicyQuery query) {
        String factory = currentFactory();
        LambdaQueryWrapper<WmsTransferVariancePolicyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WmsTransferVariancePolicyEntity::getFactoryCode, factory)
                .like(query != null && StrUtil.isNotBlank(query.getPolicyCode()),
                        WmsTransferVariancePolicyEntity::getPolicyCode, query == null ? null : query.getPolicyCode())
                .eq(query != null && StrUtil.isNotBlank(query.getSourceWarehouseCode()),
                        WmsTransferVariancePolicyEntity::getSourceWarehouseCode,
                        query == null ? null : query.getSourceWarehouseCode())
                .eq(query != null && StrUtil.isNotBlank(query.getTargetWarehouseCode()),
                        WmsTransferVariancePolicyEntity::getTargetWarehouseCode,
                        query == null ? null : query.getTargetWarehouseCode())
                .eq(query != null && StrUtil.isNotBlank(query.getMaterialCode()),
                        WmsTransferVariancePolicyEntity::getMaterialCode,
                        query == null ? null : query.getMaterialCode())
                .eq(query != null && query.getStatus() != null,
                        WmsTransferVariancePolicyEntity::getStatus, query == null ? null : query.getStatus())
                .orderByDesc(WmsTransferVariancePolicyEntity::getStatus)
                .orderByAsc(WmsTransferVariancePolicyEntity::getPolicyPriority)
                .orderByAsc(WmsTransferVariancePolicyEntity::getPolicyCode);
        return page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePolicy(WmsTransferVarianceDTO.PolicyCommand command) {
        require(command != null, "差异容差策略不能为空");
        String factory = StrUtil.blankToDefault(FactoryContextHolder.getFactoryCode(), command.getFactoryCode());
        require(StrUtil.isNotBlank(factory), "当前工厂不能为空");
        String code = trimRequired(command.getPolicyCode(), "策略编码不能为空");
        String source = normalizeDimension(command.getSourceWarehouseCode());
        String target = normalizeDimension(command.getTargetWarehouseCode());
        String material = normalizeDimension(command.getMaterialCode());
        require(!Objects.equals(source, target) || WILDCARD.equals(source), "源仓和目标仓不能相同");
        validateWarehouse(source, factory);
        validateWarehouse(target, factory);
        validateNonNegative(command.getShortTolerancePercent(), "短收容差百分比");
        validateNonNegative(command.getShortToleranceQuantity(), "短收绝对容差");
        validateNonNegative(command.getOverTolerancePercent(), "溢收容差百分比");
        validateNonNegative(command.getOverToleranceQuantity(), "溢收绝对容差");
        require(command.getShortTolerancePercent().compareTo(BigDecimal.valueOf(100)) <= 0
                        && command.getOverTolerancePercent().compareTo(BigDecimal.valueOf(100)) <= 0,
                "短溢收容差百分比不能超过100");
        require(command.getEffectiveStartDate() != null, "策略生效日期不能为空");
        require(command.getEffectiveEndDate() == null
                        || !command.getEffectiveEndDate().isBefore(command.getEffectiveStartDate()),
                "策略失效日期不能早于生效日期");
        require(command.getPolicyPriority() != null && command.getPolicyPriority() >= 1
                && command.getPolicyPriority() <= 999, "策略优先级必须在1到999之间");
        baseMapper.lockPolicyDimension(factory + ":" + source + ":" + target + ":" + material);
        Long sameCode = count(new LambdaQueryWrapper<WmsTransferVariancePolicyEntity>()
                .eq(WmsTransferVariancePolicyEntity::getFactoryCode, factory)
                .eq(WmsTransferVariancePolicyEntity::getPolicyCode, code)
                .ne(command.getId() != null, WmsTransferVariancePolicyEntity::getId, command.getId()));
        require(sameCode == 0, "策略编码已存在");
        if (Boolean.TRUE.equals(command.getStatus())) {
            List<WmsTransferVariancePolicyEntity> sameDimension = list(new LambdaQueryWrapper<WmsTransferVariancePolicyEntity>()
                    .eq(WmsTransferVariancePolicyEntity::getFactoryCode, factory)
                    .eq(WmsTransferVariancePolicyEntity::getSourceWarehouseCode, source)
                    .eq(WmsTransferVariancePolicyEntity::getTargetWarehouseCode, target)
                    .eq(WmsTransferVariancePolicyEntity::getMaterialCode, material)
                    .eq(WmsTransferVariancePolicyEntity::getStatus, true)
                    .ne(command.getId() != null, WmsTransferVariancePolicyEntity::getId, command.getId()));
            require(sameDimension.stream().noneMatch(item -> overlaps(command.getEffectiveStartDate(),
                            command.getEffectiveEndDate(), item.getEffectiveStartDate(), item.getEffectiveEndDate())),
                    "同一适用维度的启用策略有效期不能重叠");
        }
        WmsTransferVariancePolicyEntity entity = command.getId() == null
                ? new WmsTransferVariancePolicyEntity() : getById(command.getId());
        require(entity != null, "差异容差策略不存在");
        BeanUtils.copyProperties(command, entity);
        entity.setPolicyCode(code).setPolicyName(trimRequired(command.getPolicyName(), "策略名称不能为空"))
                .setSourceWarehouseCode(source).setTargetWarehouseCode(target).setMaterialCode(material)
                .setFactoryCode(factory).setPlanReviewOutsideTolerance(Boolean.TRUE.equals(command.getPlanReviewOutsideTolerance()))
                .setQualityReviewForDamage(Boolean.TRUE.equals(command.getQualityReviewForDamage()))
                .setQualityReviewForOver(Boolean.TRUE.equals(command.getQualityReviewForOver()))
                .setStatus(Boolean.TRUE.equals(command.getStatus()));
        require(command.getId() == null ? save(entity) : updateById(entity), "差异容差策略保存失败");
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(Long id) {
        WmsTransferVariancePolicyEntity entity = getById(id);
        require(entity != null, "差异容差策略不存在");
        require(Objects.equals(currentFactory(), entity.getFactoryCode()), "策略超出当前工厂数据范围");
        require(!Boolean.TRUE.equals(entity.getStatus()), "启用策略不能删除，请先停用");
        require(removeById(id), "差异容差策略删除失败");
    }

    @Override
    public WmsTransferVariancePolicyEntity resolveRequired(String factoryCode, String sourceWarehouseCode,
                                                           String targetWarehouseCode, String materialCode,
                                                           LocalDate businessDate) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        List<WmsTransferVariancePolicyEntity> candidates = list(new LambdaQueryWrapper<WmsTransferVariancePolicyEntity>()
                .eq(WmsTransferVariancePolicyEntity::getFactoryCode, factoryCode)
                .eq(WmsTransferVariancePolicyEntity::getStatus, true)
                .in(WmsTransferVariancePolicyEntity::getSourceWarehouseCode, sourceWarehouseCode, WILDCARD)
                .in(WmsTransferVariancePolicyEntity::getTargetWarehouseCode, targetWarehouseCode, WILDCARD)
                .in(WmsTransferVariancePolicyEntity::getMaterialCode, materialCode, WILDCARD)
                .le(WmsTransferVariancePolicyEntity::getEffectiveStartDate, date)
                .and(w -> w.isNull(WmsTransferVariancePolicyEntity::getEffectiveEndDate)
                        .or().ge(WmsTransferVariancePolicyEntity::getEffectiveEndDate, date)));
        return candidates.stream().sorted(Comparator
                        .comparingInt((WmsTransferVariancePolicyEntity item) -> specificity(item,
                                sourceWarehouseCode, targetWarehouseCode, materialCode)).reversed()
                        .thenComparing(WmsTransferVariancePolicyEntity::getPolicyPriority)
                        .thenComparing(WmsTransferVariancePolicyEntity::getPolicyCode))
                .findFirst().orElseThrow(() -> new CheckedException("物料[" + materialCode
                        + "]未匹配有效短溢收容差策略，最终差异接收已故障关闭"));
    }

    private int specificity(WmsTransferVariancePolicyEntity item, String source, String target, String material) {
        return (Objects.equals(item.getSourceWarehouseCode(), source) ? 4 : 0)
                + (Objects.equals(item.getTargetWarehouseCode(), target) ? 2 : 0)
                + (Objects.equals(item.getMaterialCode(), material) ? 1 : 0);
    }

    private void validateWarehouse(String code, String factory) {
        if (WILDCARD.equals(code)) return;
        R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(code);
        MdmWarehouseEntity warehouse = response == null ? null : response.getData();
        require(response != null && response.isOk() && warehouse != null && Boolean.TRUE.equals(warehouse.getStatus()),
                "仓库[" + code + "]不存在或未启用");
        require(StrUtil.isBlank(warehouse.getFactoryCode()) || Objects.equals(factory, warehouse.getFactoryCode()),
                "仓库[" + code + "]不属于当前工厂");
    }

    private String normalizeDimension(String value) {
        String normalized = StrUtil.blankToDefault(StrUtil.trim(value), WILDCARD);
        require(!normalized.contains("?") && (WILDCARD.equals(normalized) || !normalized.contains("*")),
                "适用维度只能使用完整编码或单独的*通配符");
        return normalized;
    }

    private void validateNonNegative(BigDecimal value, String label) {
        require(value != null && value.compareTo(BigDecimal.ZERO) >= 0, label + "不能小于0");
    }

    private boolean overlaps(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        LocalDate max = LocalDate.of(9999, 12, 31);
        return !start1.isAfter(end2 == null ? max : end2) && !start2.isAfter(end1 == null ? max : end1);
    }

    private String currentFactory() {
        return trimRequired(FactoryContextHolder.getFactoryCode(), "当前工厂不能为空");
    }

    private String trimRequired(String value, String message) {
        require(StrUtil.isNotBlank(value), message);
        return value.trim();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
