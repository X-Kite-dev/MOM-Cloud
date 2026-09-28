package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmBomDTO;
import com.kitex.mom.mdm.dto.MdmBomSubstituteOptionDTO;
import com.kitex.mom.mdm.dto.MdmSubstituteAuthorizationSnapshotDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.enums.SubstituteStrategyEnum;
import com.kitex.mom.mdm.mapper.MdmBomChildMapper;
import com.kitex.mom.mdm.mapper.MdmBomEcnBindingMapper;
import com.kitex.mom.mdm.mapper.MdmBomEcnMapper;
import com.kitex.mom.mdm.mapper.MdmBomMapper;
import com.kitex.mom.mdm.service.MdmBomService;
import com.kitex.mom.mdm.service.MdmMaterialAuthorizationService;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.mdm.vo.MdmBomTreeVO;
import com.kitex.mom.mdm.vo.MdmBomVersionVO;
import com.kitex.mom.mdm.vo.excel.MdmBomExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BOM
 *
 * @author kitex
 * @date 2024-10-31 09:14:59
 */
@Service
@RequiredArgsConstructor
public class MdmBomServiceImpl extends ServiceImpl<MdmBomMapper, MdmBomEntity> implements MdmBomService {

    private static final Set<String> SUBSTITUTE_QUALITY_RESULTS = Set.of(
            "QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE");

    private final MdmBomChildMapper mdmBomChildMapper;

    private final MdmBomEcnBindingMapper mdmBomEcnBindingMapper;

    private final MdmBomEcnMapper mdmBomEcnMapper;

    private final MdmMaterialService mdmMaterialService;

    private final MdmMaterialAuthorizationService materialAuthorizationService;

    private final MdmBomGovernance bomGovernance;

    /**
     * 新增bom信息
     *
     * @param mdmBomDTO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MdmBomDTO mdmBomDTO) {
        MdmBomGovernance.require(mdmBomDTO != null && StrUtil.isNotBlank(mdmBomDTO.getProductCode())
                        && StrUtil.isNotBlank(mdmBomDTO.getVersion()) && mdmBomDTO.getMdmBomChildList() != null,
                "BOM草案须提供产品、版本及明细集合");
        String factory = bomGovernance.factory(mdmBomDTO.getFactoryCode());
        bomGovernance.access(factory, "write", true);
        MdmBomGovernance.require(!Boolean.TRUE.equals(mdmBomDTO.getEnabled()) && !Boolean.TRUE.equals(mdmBomDTO.getIsDefault()),
                "新增BOM只能保存未启用草案，启用及默认切换须走工程变更审批");
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtil.copyProperties(mdmBomDTO, mdmBom);
        mdmBom.setId(null);
        mdmBom.setFactoryCode(factory);
        mdmBom.setEnabled(false);
        mdmBom.setIsDefault(false);
        mdmBom.setReleaseState("DRAFT");
        // 校验是否存在相同的版本号
        LambdaQueryWrapper<MdmBomEntity> wrapper = Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, mdmBomDTO.getProductCode())
                .eq(MdmBomEntity::getFactoryCode, factory)
                .eq(MdmBomEntity::getVersion, mdmBomDTO.getVersion());
        if (!Objects.isNull(this.getOne(wrapper, false))) {
            throw new RuntimeException("当前产品的BOM版本号已存在");
        }
        validateBomEffectivePeriod(mdmBomDTO.getStartDate(), mdmBomDTO.getEndDate());
        normalizeAndValidateSubstitutes(mdmBomDTO.getMdmBomChildList(),
                mdmBomDTO.getStartDate(), mdmBomDTO.getEndDate(), factory);
        baseMapper.insert(mdmBom);
        DynamicRefreshUtils.refreshMdm(mdmBom);
        for (MdmBomChildEntity mdmBomChild : mdmBomDTO.getMdmBomChildList()) {
            mdmBomChild.setId(null);
            mdmBomChild.setTenantId(null);
            mdmBomChild.setFactoryCode(factory);
            mdmBomChild.setBomId(mdmBom.getId());
            // TODO 查询当前这个物料是否有bom信息
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return Boolean.TRUE;
    }

    /**
     * 修改bom信息
     *
     * @param mdmBomDTO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmBomDTO mdmBomDTO) {
        MdmBomGovernance.require(mdmBomDTO != null && mdmBomDTO.getId() != null && mdmBomDTO.getMdmBomChildList() != null,
                "修改BOM草案须提供版本ID及完整明细集合");
        MdmBomEntity old = bomGovernance.editable(mdmBomDTO.getId());
        MdmBomGovernance.require(Objects.equals(old.getProductCode(), mdmBomDTO.getProductCode())
                        && Objects.equals(old.getVersion(), mdmBomDTO.getVersion())
                        && Objects.equals(old.getFactoryCode(), bomGovernance.factory(mdmBomDTO.getFactoryCode())),
                "已有版本不可改产品、版本号或工厂，请新增草案");
        MdmBomGovernance.require(!Boolean.TRUE.equals(mdmBomDTO.getEnabled()) && !Boolean.TRUE.equals(mdmBomDTO.getIsDefault()),
                "草案不能直接启用或设默认，请走工程变更审批");
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtil.copyProperties(mdmBomDTO, mdmBom);
        mdmBom.setFactoryCode(old.getFactoryCode());
        mdmBom.setEnabled(false);
        mdmBom.setIsDefault(false);
        mdmBom.setReleaseState("DRAFT");
        validateBomEffectivePeriod(mdmBomDTO.getStartDate(), mdmBomDTO.getEndDate());
        normalizeAndValidateSubstitutes(mdmBomDTO.getMdmBomChildList(),
                mdmBomDTO.getStartDate(), mdmBomDTO.getEndDate(), old.getFactoryCode());
        baseMapper.updateById(mdmBom);
        DynamicRefreshUtils.refreshMdm(mdmBom);
        // 删除旧数据
        mdmBomChildMapper
                .delete(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBom.getId()));
        for (MdmBomChildEntity mdmBomChild : mdmBomDTO.getMdmBomChildList()) {
            mdmBomChild.setId(null);
            mdmBomChild.setTenantId(null);
            mdmBomChild.setFactoryCode(old.getFactoryCode());
            mdmBomChild.setBomId(mdmBom.getId());
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return Boolean.TRUE;
    }

    /**
     * 替代策略随 BOM 版本冻结。这里统一校验比例、重复关系和物料有效性，避免无效策略进入工单快照。
     */
    private void normalizeAndValidateSubstitutes(List<MdmBomChildEntity> children,
                                                 LocalDate bomStart, LocalDate bomEnd, String factory) {
        if (CollUtil.isEmpty(children)) {
            return;
        }
        Map<MdmBomChildEntity, List<MdmBomSubstituteOptionDTO>> optionMap = new LinkedHashMap<>();
        Set<String> substituteCodes = new HashSet<>();
        for (MdmBomChildEntity child : children) {
            if (StrUtil.isBlank(child.getSubstituteStrategy())) {
                child.setSubstituteStrategy(SubstituteStrategyEnum.MAIN_FIRST.name());
            } else {
                try {
                    SubstituteStrategyEnum.valueOf(child.getSubstituteStrategy());
                } catch (IllegalArgumentException ex) {
                    throw new CheckedException("物料[" + child.getMaterialCode() + "]替代料选用策略无效");
                }
            }
            child.setSubstituteMixAllowed(Boolean.TRUE.equals(child.getSubstituteMixAllowed()));
            if (StrUtil.isBlank(child.getSubstitutePolicy()) && StrUtil.isBlank(child.getReplaceCodes())) {
                child.setReplaceCodes(null);
                continue;
            }
            List<MdmBomSubstituteOptionDTO> options;
            if (StrUtil.isBlank(child.getSubstitutePolicy())) {
                options = new ArrayList<>();
                int priority = 1;
                for (String code : child.getReplaceCodes().split(",")) {
                    if (StrUtil.isBlank(code)) {
                        continue;
                    }
                    MdmBomSubstituteOptionDTO option = new MdmBomSubstituteOptionDTO();
                    option.setSubstituteCode(code.trim());
                    option.setSubstituteRatio(BigDecimal.ONE);
                    option.setPriority(priority++);
                    option.setEnabled(Boolean.TRUE);
                    options.add(option);
                }
            } else {
                try {
                    options = JSONUtil.toList(child.getSubstitutePolicy(), MdmBomSubstituteOptionDTO.class);
                } catch (Exception ex) {
                    throw new CheckedException("物料[" + child.getMaterialCode() + "]替代策略格式错误");
                }
            }
            Set<String> rowCodes = new HashSet<>();
            int defaultPriority = 1;
            for (MdmBomSubstituteOptionDTO option : options) {
                option.setSubstituteCode(StrUtil.trim(option.getSubstituteCode()));
                if (StrUtil.isBlank(option.getSubstituteCode())) {
                    throw new CheckedException("物料[" + child.getMaterialCode() + "]存在未选择物料的替代策略");
                }
                if (Objects.equals(child.getMaterialCode(), option.getSubstituteCode())) {
                    throw new CheckedException("物料[" + child.getMaterialCode() + "]不能替代自身");
                }
                if (!rowCodes.add(option.getSubstituteCode())) {
                    throw new CheckedException("物料[" + child.getMaterialCode() + "]存在重复替代料[" + option.getSubstituteCode() + "]");
                }
                if (option.getSubstituteRatio() == null || option.getSubstituteRatio().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的替代比例必须大于0");
                }
                if (option.getEffectiveStart() != null && option.getEffectiveEnd() != null
                        && option.getEffectiveStart().isAfter(option.getEffectiveEnd())) {
                    throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的生效日期不能晚于失效日期");
                }
                option.setApplicableCustomerCodes(normalizeCodes(option.getApplicableCustomerCodes(), false));
                option.setCertifiedSupplierCodes(normalizeCodes(option.getCertifiedSupplierCodes(), false));
                option.setAllowedQualityResults(normalizeCodes(option.getAllowedQualityResults(), true));
                if (!SUBSTITUTE_QUALITY_RESULTS.containsAll(option.getAllowedQualityResults())) {
                    throw new CheckedException("替代料[" + option.getSubstituteCode() + "]包含无效的质量放行结论");
                }
                option.setSupplierCertificateRef(StrUtil.trim(option.getSupplierCertificateRef()));
                if (CollUtil.isEmpty(option.getCertifiedSupplierCodes())) {
                    option.setSupplierCertificateRef(null);
                    option.setSupplierCertificateValidEnd(null);
                }
                option.setComplianceMode(StrUtil.blankToDefault(option.getComplianceMode(), "STANDARD").toUpperCase(Locale.ROOT));
                if (!Set.of("STANDARD", "DEVIATION").contains(option.getComplianceMode())) {
                    throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的合规方式无效");
                }
                option.setDeviationPermitNo(StrUtil.trim(option.getDeviationPermitNo()));
                option.setDeviationReason(StrUtil.trim(option.getDeviationReason()));
                if ("DEVIATION".equals(option.getComplianceMode())) {
                    if (StrUtil.isBlank(option.getDeviationPermitNo()) || StrUtil.isBlank(option.getDeviationReason())
                            || option.getDeviationValidEnd() == null) {
                        throw new CheckedException("替代料[" + option.getSubstituteCode() + "]采用偏离许可时必须填写许可编号、原因和有效期");
                    }
                    if (option.getDeviationValidEnd().isBefore(LocalDate.now())) {
                        throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的偏离许可已经失效");
                    }
                } else {
                    option.setDeviationPermitNo(null);
                    option.setDeviationReason(null);
                    option.setDeviationValidEnd(null);
                }
                option.setPriority(Objects.requireNonNullElse(option.getPriority(), defaultPriority));
                option.setEnabled(!Boolean.FALSE.equals(option.getEnabled()));
                defaultPriority++;
                substituteCodes.add(option.getSubstituteCode());
            }
            optionMap.put(child, options);
        }
        Map<String, MdmMaterialEntity> materialMap = substituteCodes.isEmpty()
                ? Collections.emptyMap()
                : mdmMaterialService.list(
                        Wrappers.<MdmMaterialEntity>lambdaQuery().eq(MdmMaterialEntity::getFactoryCode, factory)
                                .in(MdmMaterialEntity::getMaterialCode, substituteCodes))
                .stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (left, right) -> left));
        for (Map.Entry<MdmBomChildEntity, List<MdmBomSubstituteOptionDTO>> entry : optionMap.entrySet()) {
            List<String> enabledCodes = new ArrayList<>();
            for (MdmBomSubstituteOptionDTO option : entry.getValue()) {
                MdmMaterialEntity material = materialMap.get(option.getSubstituteCode());
                if (material == null || !Boolean.TRUE.equals(material.getStatus())) {
                    throw new CheckedException("替代料[" + option.getSubstituteCode() + "]不存在或未启用");
                }
                option.setSubstituteName(material.getMaterialName());
                option.setSubstituteSpec(material.getSpecificationModel());
                option.setSubstituteUnit(material.getBasicUnit());
                freezeAuthorizations(option, bomStart, bomEnd);
                if (Boolean.TRUE.equals(option.getEnabled())) {
                    enabledCodes.add(option.getSubstituteCode());
                }
            }
            entry.getKey().setSubstitutePolicy(JSONUtil.toJsonStr(entry.getValue()));
            entry.getKey().setReplaceCodes(String.join(",", enabledCodes));
        }
    }

    /**
     * 标准替代必须引用独立权威台账，禁止继续把人工录入的编号当成有效认证。
     * 偏离替代由偏离许可单独兜底，仍保留其完整原因与有效期快照。
     */
    private void freezeAuthorizations(MdmBomSubstituteOptionDTO option, LocalDate bomStart, LocalDate bomEnd) {
        if (!Boolean.TRUE.equals(option.getEnabled())) {
            option.setSupplierCertifications(List.of());
            option.setCustomerApprovals(List.of());
            option.setInspectionLevel(null);
            option.setReferenceUnitPrice(null);
            option.setCurrency(null);
            return;
        }
        LocalDate start = latestDate(bomStart, option.getEffectiveStart());
        if (start == null) {
            start = LocalDate.now();
        }
        LocalDate coverageEnd = earliestDate(bomEnd, option.getEffectiveEnd());
        if (coverageEnd == null) {
            coverageEnd = start;
        }
        if (coverageEnd.isBefore(start)) {
            throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的生效期与BOM有效期没有交集");
        }
        if ("DEVIATION".equals(option.getComplianceMode())) {
            if (option.getDeviationValidEnd().isBefore(coverageEnd)) {
                throw new CheckedException("替代料[" + option.getSubstituteCode() + "]的偏离许可未覆盖替代策略有效期");
            }
            option.setSupplierCertifications(List.of());
            option.setCustomerApprovals(List.of());
            option.setInspectionLevel(null);
            option.setReferenceUnitPrice(null);
            option.setCurrency(null);
            return;
        }
        List<MdmSubstituteAuthorizationSnapshotDTO> supplierSnapshots = new ArrayList<>();
        for (String supplierCode : option.getCertifiedSupplierCodes()) {
            MdmMaterialAuthorizationEntity authorization = coveringAuthorization(
                    MdmMaterialAuthorizationServiceImpl.SUPPLIER, option.getSubstituteCode(), supplierCode, start, coverageEnd);
            supplierSnapshots.add(toSnapshot(authorization));
        }
        List<MdmSubstituteAuthorizationSnapshotDTO> customerSnapshots = new ArrayList<>();
        for (String customerCode : option.getApplicableCustomerCodes()) {
            MdmMaterialAuthorizationEntity authorization = coveringAuthorization(
                    MdmMaterialAuthorizationServiceImpl.CUSTOMER, option.getSubstituteCode(), customerCode, start, coverageEnd);
            customerSnapshots.add(toSnapshot(authorization));
        }
        option.setSupplierCertifications(supplierSnapshots);
        option.setCustomerApprovals(customerSnapshots);
        option.setInspectionLevel(supplierSnapshots.stream().anyMatch(item -> "TIGHTENED".equals(item.getInspectionLevel()))
                ? "TIGHTENED" : "NORMAL");
        option.setReferenceUnitPrice(supplierSnapshots.stream().map(MdmSubstituteAuthorizationSnapshotDTO::getReferenceUnitPrice)
                .filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null));
        option.setCurrency(supplierSnapshots.stream().map(MdmSubstituteAuthorizationSnapshotDTO::getCurrency)
                .filter(StrUtil::isNotBlank).findFirst().orElse(null));
        if (!supplierSnapshots.isEmpty()) {
            option.setSupplierCertificateRef(supplierSnapshots.stream()
                    .map(MdmSubstituteAuthorizationSnapshotDTO::getAuthorizationNo).collect(Collectors.joining(",")));
            option.setSupplierCertificateValidEnd(supplierSnapshots.stream()
                    .map(MdmSubstituteAuthorizationSnapshotDTO::getValidEnd).min(LocalDate::compareTo).orElse(null));
        } else {
            option.setSupplierCertificateRef(null);
            option.setSupplierCertificateValidEnd(null);
        }
    }

    @Override
    public void validateEngineeringSubstitutes(MdmBomEntity bom, List<MdmBomChildEntity> children) {
        List<MdmBomChildEntity> policies = children.stream().filter(c -> StrUtil.isNotBlank(c.getSubstitutePolicy())
                || StrUtil.isNotBlank(c.getReplaceCodes())).toList();
        List<MdmBomChildEntity> checked = BeanUtil.copyToList(policies, MdmBomChildEntity.class);
        normalizeAndValidateSubstitutes(checked, bom.getStartDate(), bom.getEndDate(), bom.getFactoryCode());
        for (int i = 0; i < policies.size(); i++) {
            MdmBomChildEntity old = policies.get(i), live = checked.get(i);
            MdmBomGovernance.require(Objects.equals(old.getSubstitutePolicy(), live.getSubstitutePolicy())
                            && Objects.equals(old.getReplaceCodes(), live.getReplaceCodes())
                            && Objects.equals(old.getSubstituteMixAllowed(), live.getSubstituteMixAllowed())
                            && Objects.equals(old.getSubstituteStrategy(), live.getSubstituteStrategy()),
                    "BOM[" + bom.getId() + "]物料[" + old.getMaterialCode() + "]替代策略或权威认证已变化，须在可编辑新版本重新保存核实，不覆盖冻结旧版");
        }
    }

    private LocalDate latestDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private void validateBomEffectivePeriod(LocalDate start, LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new CheckedException("BOM开始日期不能晚于截止日期");
        }
    }

    private LocalDate earliestDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private MdmMaterialAuthorizationEntity coveringAuthorization(String type, String materialCode, String partnerCode,
                                                                 LocalDate start, LocalDate coverageEnd) {
        return materialAuthorizationService.active(type, materialCode, partnerCode, start).stream()
                .filter(item -> !item.getValidEnd().isBefore(coverageEnd))
                .findFirst().orElseThrow(() -> new CheckedException("替代料[" + materialCode + "]合作方[" + partnerCode
                        + "]没有覆盖替代策略有效期的启用认证/批准"));
    }

    private MdmSubstituteAuthorizationSnapshotDTO toSnapshot(MdmMaterialAuthorizationEntity source) {
        MdmSubstituteAuthorizationSnapshotDTO target = new MdmSubstituteAuthorizationSnapshotDTO();
        target.setAuthorizationId(source.getId());
        target.setAuthorizationType(source.getAuthorizationType());
        target.setPartnerCode(source.getPartnerCode());
        target.setPartnerName(source.getPartnerName());
        target.setAuthorizationNo(source.getAuthorizationNo());
        target.setValidStart(source.getValidStart());
        target.setValidEnd(source.getValidEnd());
        target.setInspectionLevel(source.getInspectionLevel());
        target.setQualityStandardId(source.getQualityStandardId());
        target.setQualityStandardName(source.getQualityStandardName());
        target.setReferenceUnitPrice(source.getReferenceUnitPrice());
        target.setCurrency(source.getCurrency());
        return target;
    }

    private List<String> normalizeCodes(List<String> source, boolean uppercase) {
        if (CollUtil.isEmpty(source)) {
            return List.of();
        }
        return source.stream().filter(StrUtil::isNotBlank).map(StrUtil::trim)
                .map(code -> uppercase ? code.toUpperCase(Locale.ROOT) : code)
                .distinct().toList();
    }

    /**
     * 删除bom信息
     *
     * @param ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        MdmBomGovernance.require(ids != null && ids.length > 0, "请选择BOM草案");
        for (Long id : Arrays.stream(ids).sorted().toList()) bomGovernance.editable(id);
        baseMapper.deleteByIds(CollUtil.toList(ids));
        mdmBomChildMapper.delete(Wrappers.<MdmBomChildEntity>lambdaQuery().in(MdmBomChildEntity::getBomId, ids));
        return Boolean.TRUE;
    }

    /**
     * 删除bom的物料
     *
     * @param ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        MdmBomGovernance.require(ids != null && ids.length > 0, "请选择BOM草案明细");
        List<MdmBomChildEntity> lines = mdmBomChildMapper.selectBatchIds(Arrays.asList(ids));
        MdmBomGovernance.require(lines.size() == Arrays.stream(ids).distinct().count(), "BOM明细已不存在");
        for (Long id : lines.stream().map(MdmBomChildEntity::getBomId).distinct().sorted().toList())
            bomGovernance.editable(id);
        mdmBomChildMapper.deleteByIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 根据条件查询bom信息
     *
     * @param mdmBom 查询条件
     * @return 完整的bom信息 或者 null
     */
    @Override
    public MdmBomDTO getBomDeep(MdmBomEntity mdmBom) {
        MdmBomDTO mdmBomDTO = new MdmBomDTO();
        MdmBomEntity mdmBom1 = this.getOne(Wrappers.query(mdmBom), false);
        if (Objects.isNull(mdmBom1)) {
            return null;
        }
        BeanUtil.copyProperties(mdmBom1, mdmBomDTO);
        mdmBomDTO.setMdmBomChildList(mdmBomChildMapper
                .selectList(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBom1.getId())));
        return mdmBomDTO;
    }

    /**
     * 根据产品编码获取该产品所有子物料bom
     *
     * @param productCode 产品编码
     * @param bomId       bomId
     * @param version     版本
     * @return 子物料集合
     */
    @Override
    public List<MdmBomChildDTO> getChildMaterials(String productCode, Long bomId, String version) {
        MdmBomGovernance.require(bomId != null && bomId > 0, "业务BOM展开须指定精确版本ID");
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        List<MdmBomChildDTO> result = new ArrayList<>();
        MdmBomEntity bomEntity = this
                .getOne(Wrappers.<MdmBomEntity>lambdaQuery().eq(StrUtil.isNotBlank(productCode), MdmBomEntity::getProductCode, productCode)
                        .eq(MdmBomEntity::getId, bomId)
                        .eq(StrUtil.isNotBlank(version), MdmBomEntity::getVersion, version)
                        .orderByDesc(MdmBomEntity::getVersion).last("limit 1"));
        if (Objects.isNull(bomEntity)) {
            return null;
        }
        requireExecutable(bomEntity);
        getChildMaterials(allBomChild, result, bomEntity.getId());
        return result;
    }

    /**
     * 读取BOM维护视图。与生产执行展开接口不同，此处允许查看未提交草案和审批冻结版本，
     * 但仍强制校验当前租户及工厂访问边界。
     */
    @Override
    public List<MdmBomChildDTO> getMaintenanceStructure(Long bomId) {
        MdmBomGovernance.require(bomId != null && bomId > 0, "BOM维护预览须指定精确版本ID");
        MdmBomEntity root = this.getById(bomId);
        MdmBomGovernance.require(root != null, "BOM版本不存在");
        bomGovernance.access(root.getFactoryCode(), "read", false);
        MdmBomGovernance.require(Objects.equals(root.getFactoryCode(), bomGovernance.factory(null)), "BOM不属于当前工厂");
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(
                Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getFactoryCode, root.getFactoryCode()));
        List<MdmBomChildDTO> result = new ArrayList<>();
        getMaintenanceStructure(allBomChild, result, root.getId(), root.getFactoryCode(), new HashSet<>());
        return result;
    }

    /**
     * 根据产品编码列表获取该产品所有子物料bom信息包括自己
     *
     * @param productCodes 产品编码列表
     * @return 子物料集合, 按层次区分
     */
    @Override
    public Map<String, MdmBomChildDTO> getAllChildMaterials(List<String> productCodes) {
        // 构造返回体
        Map<String, MdmBomChildDTO> result = new HashMap<>();
        BigDecimal parentQuantity = new BigDecimal(1);
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        for (String productCode : productCodes) {
            List<MdmBomChildDTO> resultList = new ArrayList<>();
            // 查询产品的bom
            MdmBomEntity bomEntity = this.getOne(Wrappers.<MdmBomEntity>lambdaQuery().eq(MdmBomEntity::getProductCode, productCode)
                    .eq(MdmBomEntity::getFactoryCode, bomGovernance.factory(null))
                    .eq(MdmBomEntity::getEnabled, true).ne(MdmBomEntity::getReleaseState, "DRAFT")
                    .and(w -> w.isNull(MdmBomEntity::getStartDate).or().le(MdmBomEntity::getStartDate, LocalDate.now()))
                    .and(w -> w.isNull(MdmBomEntity::getEndDate).or().ge(MdmBomEntity::getEndDate, LocalDate.now()))
                    .orderByDesc(MdmBomEntity::getIsDefault).orderByDesc(MdmBomEntity::getVersion).last("limit 1"));
            // 添加当前产品信息并将子级bom添加
            MdmBomChildDTO bomChildDTO = new MdmBomChildDTO();
            bomChildDTO.setMaterialCode(productCode);
            if (Objects.isNull(bomEntity)) {
                MdmBomGovernance.require(this.count(Wrappers.<MdmBomEntity>lambdaQuery()
                                .eq(MdmBomEntity::getProductCode, productCode).eq(MdmBomEntity::getFactoryCode, bomGovernance.factory(null))) == 0,
                        "产品[" + productCode + "]已有BOM但没有当前可用发布版，不可把草案或过期版本当作无BOM继续运算");
                bomChildDTO.setChildren(resultList);
                result.put(productCode, bomChildDTO);
                continue;
            }
            getChildMaterials(allBomChild, resultList, bomEntity.getId());
            bomChildDTO.setMaterialName(bomEntity.getProductName());
            bomChildDTO.setQuantity(parentQuantity);
            bomChildDTO.setBomId(bomEntity.getId());
            bomChildDTO.setSubitemBomId(0L);
            bomChildDTO.setChildren(resultList);
            result.put(productCode, bomChildDTO);
        }

        return result;
    }

    /**
     * 导出excel 表格
     *
     * @param mdmBom 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmBomExcelVO> exportExcel(MdmBomEntity mdmBom, Long[] ids) {
        List<MdmBomEntity> list = this.list(Wrappers.lambdaQuery(mdmBom).in(ArrayUtil.isNotEmpty(ids), MdmBomEntity::getId, ids));
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        List<MdmBomExcelVO> result = new ArrayList<>();

        // 递归方法，组装层级
        class BomExportNode {
            void build(MdmBomEntity entity, String levelPrefix) {
                MdmBomExcelVO vo = new MdmBomExcelVO();
                // 复制主表属性到VO
                BeanUtils.copyProperties(entity, vo);
                vo.setLevel(levelPrefix);
                vo.setProductCode(entity.getProductCode());
                vo.setProductName(entity.getProductName());
                vo.setVersion(entity.getVersion());
                result.add(vo);

                // 递归处理子项
                List<MdmBomChildEntity> children = allBomChild.stream()
                        .filter(child -> child.getBomId().equals(entity.getId()))
                        .toList();
                int idx = 1;
                for (MdmBomChildEntity child : children) {
                    buildChild(child, levelPrefix + "." + idx, 1, entity.getVersion());
                    idx++;
                }
            }

            void buildChild(MdmBomChildEntity child, String level, int depth, String parentVersion) {
                MdmBomExcelVO vo = new MdmBomExcelVO();
                BeanUtils.copyProperties(child, vo);
                vo.setLevel(level);
                vo.setProductCode(child.getMaterialCode());
                vo.setProductName(child.getMaterialName());
                vo.setVersion(parentVersion); // 取父级version
                result.add(vo);
                // 递归处理子项
                List<MdmBomChildEntity> children = allBomChild.stream()
                        .filter(c -> c.getBomId().equals(child.getSubitemBomId()))
                        .toList();
                int idx = 1;
                for (MdmBomChildEntity sub : children) {
                    buildChild(sub, level + "." + idx, depth + 1, parentVersion);
                    idx++;
                }
            }
        }

        BomExportNode node = new BomExportNode();
        int rootIdx = 1;
        for (MdmBomEntity entity : list) {
            node.build(entity, String.valueOf(rootIdx));
            rootIdx++;
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean bomCopy(Long id) {
        MdmBomEntity bomEntity = this.getById(id);
        MdmBomGovernance.require(bomEntity != null, "BOM不存在");
        bomGovernance.access(bomEntity.getFactoryCode(), "write", true);
        bomEntity = this.getById(id);

        MdmBomEntity copyBomEntity = new MdmBomEntity();
        BeanUtils.copyProperties(bomEntity, copyBomEntity, "id");

        // 设置版本号，如果已存在则递增
        String baseVersion = bomEntity.getVersion() + "-复制-";
        String newVersion = baseVersion + "01";
        int count = 1;

        // 查询是否存在相同版本号
        while (this.count(Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, bomEntity.getProductCode())
                .eq(MdmBomEntity::getVersion, newVersion)) > 0) {
            count++;
            newVersion = baseVersion + String.format("%02d", count);
        }
        copyBomEntity.setVersion(newVersion);
        copyBomEntity.setEnabled(false);
        copyBomEntity.setIsDefault(false);
        copyBomEntity.setReleaseState("DRAFT");

        this.save(copyBomEntity);

        // 获取子集bom
        List<MdmBomChildEntity> bomChild = mdmBomChildMapper.selectList(new LambdaQueryWrapper<MdmBomChildEntity>()
                .eq(MdmBomChildEntity::getBomId, bomEntity.getId()));

        // 复制子集数据
        for (MdmBomChildEntity child : bomChild) {
            MdmBomChildEntity copyChild = new MdmBomChildEntity();
            BeanUtils.copyProperties(child, copyChild, "id");
            copyChild.setBomId(copyBomEntity.getId());
            mdmBomChildMapper.insert(copyChild);
        }

        return Boolean.TRUE;
    }

    /**
     * bom树查询
     *
     * @param queryWrapper 查询条件
     * @return
     */
    @Override
    public List<MdmBomTreeVO> tree(QueryWrapper<MdmBomEntity> queryWrapper) {
        // 构建返回体VO
        List<MdmBomTreeVO> treeList = new ArrayList<>();
        // 获取bom列表
        List<MdmBomEntity> list = this.list(queryWrapper);

        Map<Long, MdmBomEcnEntity> frozenEcnByBomId = loadFrozenEcn(list);

        // 按照productCode分组
        Map<String, List<MdmBomEntity>> groupedByProduct = list.stream()
                .collect(Collectors.groupingBy(
                        MdmBomEntity::getProductCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        // 处理每个产品分组
        groupedByProduct.forEach((productCode, boms) -> {
            if (!boms.isEmpty()) {
                MdmBomEntity firstRoute = boms.get(0);
                MdmBomTreeVO parentNode = new MdmBomTreeVO();
                // 产品节点不能复用首个版本ID，否则el-tree会产生重复node-key并误选父节点。
                parentNode.setId("PRODUCT:" + firstRoute.getFactoryCode() + ":" + productCode);
                parentNode.setTitle(String.format("%s(%s)", firstRoute.getProductName(), productCode));
                parentNode.setProductCode(productCode);
                parentNode.setProductName(firstRoute.getProductName());
                parentNode.setProductSpec(firstRoute.getProductSpec());
                parentNode.setProductUnit(firstRoute.getUnit());

                // 创建子节点列表
                List<MdmBomEntity> children = java.util.stream.Stream.concat(
                        boms.stream().filter(route -> Boolean.TRUE.equals(route.getIsDefault())),
                        boms.stream().filter(route -> !Boolean.TRUE.equals(route.getIsDefault()))
                ).collect(Collectors.toList());

                parentNode.setChildren(children.stream().map(bom -> toVersionNode(bom, frozenEcnByBomId.get(bom.getId())))
                        .collect(Collectors.toList()));
                treeList.add(parentNode);
            }
        });
        return treeList;
    }

    /**
     * 批量读取会冻结BOM版本的工程变更，避免版本树逐行查询产生N+1问题。
     */
    private Map<Long, MdmBomEcnEntity> loadFrozenEcn(List<MdmBomEntity> boms) {
        if (CollUtil.isEmpty(boms)) {
            return Collections.emptyMap();
        }
        List<MdmBomEcnBindingEntity> bindings = mdmBomEcnBindingMapper.selectList(
                Wrappers.<MdmBomEcnBindingEntity>lambdaQuery()
                        .in(MdmBomEcnBindingEntity::getBomId, boms.stream().map(MdmBomEntity::getId).toList()));
        if (CollUtil.isEmpty(bindings)) {
            return Collections.emptyMap();
        }
        List<MdmBomEcnEntity> ecns = mdmBomEcnMapper.selectList(
                Wrappers.<MdmBomEcnEntity>lambdaQuery()
                        .select(MdmBomEcnEntity::getId, MdmBomEcnEntity::getStatus)
                        .in(MdmBomEcnEntity::getId, bindings.stream().map(MdmBomEcnBindingEntity::getEcnId).distinct().toList())
                        .in(MdmBomEcnEntity::getStatus, "PENDING", "PUBLISHED"));
        Map<Long, MdmBomEcnEntity> ecnById = ecns.stream()
                .collect(Collectors.toMap(MdmBomEcnEntity::getId, Function.identity(), (left, right) -> left));
        Map<Long, MdmBomEcnEntity> result = new HashMap<>();
        for (MdmBomEcnBindingEntity binding : bindings) {
            MdmBomEcnEntity ecn = ecnById.get(binding.getEcnId());
            if (ecn == null) {
                continue;
            }
            result.merge(binding.getBomId(), ecn,
                    (left, right) -> "PENDING".equals(right.getStatus()) ? right : left);
        }
        return result;
    }

    /**
     * 生成带工程治理状态的版本节点。
     */
    private MdmBomVersionVO toVersionNode(MdmBomEntity bom, MdmBomEcnEntity frozenEcn) {
        MdmBomVersionVO node = MdmBomVersionVO.from(bom);
        boolean frozen = frozenEcn != null;
        node.setFrozen(frozen);
        node.setEditable(!Boolean.TRUE.equals(bom.getEnabled())
                && !Boolean.TRUE.equals(bom.getIsDefault())
                && !"RELEASED".equals(bom.getReleaseState())
                && !frozen);
        if (frozen) {
            node.setEcnId(frozenEcn.getId());
            node.setEcnStatus(frozenEcn.getStatus());
        }
        return node;
    }

    /**
     * 根据id设置默认bom
     *
     * @param id id
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean bomDefault(Long id) {
        throw new CheckedException("默认BOM切换须走工程变更影响分析及异岗审批，不能直接切换");
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmBomExcelVO> excelVOList, BindingResult bindingResult) {
        String factory = bomGovernance.factory(null);
        bomGovernance.access(factory, "write", true);
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        Map<String, MdmMaterialEntity> materialMap = mdmMaterialService.list(Wrappers.<MdmMaterialEntity>lambdaQuery()
                        .eq(MdmMaterialEntity::getFactoryCode, factory))
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity()));
        // 查询bom数据
        Map<String, Long> bomMap = this.list(Wrappers.<MdmBomEntity>lambdaQuery().eq(MdmBomEntity::getFactoryCode, factory)).stream().collect(Collectors.toMap(
                bom -> bom.getProductCode() + "#" + bom.getVersion(),
                MdmBomEntity::getId,
                (id1, id2) -> id1 // 若有重复，取第一个
        ));
        // bom主表存在产品对应的版本准备删除的数据
        List<Long> delListIds = new ArrayList<>();
        // 准备插入到bom主表的数据
        List<MdmBomEntity> insertList = new ArrayList<>();
        // 准备插入到bom子表的数据
        List<MdmBomChildEntity> insertChildList = new ArrayList<>();
        Snowflake snowflake = IdUtil.getSnowflake();

        // 新增：层级关系缓存
        Map<String, Object> levelMap = new HashMap<>(); // 层级->实体
        Map<String, List<String>> childrenMap = new HashMap<>(); // 父层级->子层级
        Map<String, Long> levelIdMap = new HashMap<>(); // 层级->ID

        // 先统计所有层级的父子关系
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel(); // 假设有getLevel方法
            String parentLevel = level.contains(".") ? level.substring(0, level.lastIndexOf(".")) : null;
            if (parentLevel != null) {
                childrenMap.computeIfAbsent(parentLevel, k -> new ArrayList<>()).add(level);
            }
        }

        // 校验和数据准备
        for (MdmBomExcelVO mdmBomExcelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 查询数据是否存在
            String bomKey = mdmBomExcelVO.getProductCode() + "#" + mdmBomExcelVO.getVersion();
            Long id = bomMap.get(bomKey);
            if (id != null) {
                errorMsg.add("已有产品版本不可通过导入替换，请使用新版本号创建草案");
            }
            MdmMaterialEntity matEntity = materialMap.get(mdmBomExcelVO.getProductCode());
            if (matEntity == null) {
                errorMsg.add(mdmBomExcelVO.getProductCode() + "产品编码不存在");
            } else {
                mdmBomExcelVO.setProductName(matEntity.getMaterialName());
                mdmBomExcelVO.setUnit(matEntity.getBasicUnit());
                mdmBomExcelVO.setMaterialAttribute(matEntity.getMaterialAttribute());
            }
            if (mdmBomExcelVO.getStartDate() != null && mdmBomExcelVO.getEndDate() != null) {
                if (mdmBomExcelVO.getStartDate().isAfter(mdmBomExcelVO.getEndDate())) {
                    errorMsg.add("开始日期不能大于截止日期");
                }
            }
            if (CollUtil.isNotEmpty(errorMsg)) {
                errorMessageList.add(new ErrorMessage(mdmBomExcelVO.getLineNum() + 1, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }

        // 构建层级结构并分配ID
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel();
            Long id = snowflake.nextId();
            levelIdMap.put(level, id);
        }

        // 第一遍：插入数据，构建levelMap
        Map<String, MdmBomChildEntity> childLevelMap = new HashMap<>(); // 只存子层级
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel();
            String parentLevel = level.contains(".") ? level.substring(0, level.lastIndexOf(".")) : null;
            Long id = levelIdMap.get(level);

            if (parentLevel == null) {
                // 根层级，只插入insertList
                MdmBomEntity entity = insertExcel(vo, snowflake);
                entity.setId(id);
                insertList.add(entity);
                levelMap.put(level, entity);
            } else {
                // 子层级，插入insertChildList
                MdmBomChildEntity child = new MdmBomChildEntity();
                BeanUtils.copyProperties(vo, child);
                child.setMaterialName(vo.getProductName());
                child.setMaterialCode(vo.getProductCode());
                child.setId(id);
                // 设置父级bomId
                Object parentObj = levelMap.get(parentLevel);
                Long parentId = null;
                if (parentObj instanceof MdmBomEntity) {
                    parentId = ((MdmBomEntity) parentObj).getId();
                } else if (parentObj instanceof MdmBomChildEntity) {
                    parentId = ((MdmBomChildEntity) parentObj).getId();
                }
                child.setBomId(parentId);
                // 不设置subitemBomId，后续统一处理
                insertChildList.add(child);
                levelMap.put(level, child);
                childLevelMap.put(level, child);

                // 如果有子集，还要插入insertList
                if (childrenMap.containsKey(level)) {
                    MdmBomEntity entity = insertExcel(vo, snowflake);
                    entity.setId(id);
                    insertList.add(entity);
                    levelMap.put(level, entity);
                }
            }
        }

        // 第二遍：为有子集的子层级设置subitemBomId
        for (Map.Entry<String, MdmBomChildEntity> entry : childLevelMap.entrySet()) {
            String level = entry.getKey();
            MdmBomChildEntity child = entry.getValue();
            if (childrenMap.containsKey(level)) {
                String firstSubLevel = childrenMap.get(level).get(0);
                MdmBomChildEntity subChild = childLevelMap.get(firstSubLevel);
                if (subChild != null) {
                    child.setSubitemBomId(subChild.getBomId());
                }
            }
        }
        // 先删除存在重复的数据
        if (delListIds.size() > 0) {
            this.removeBatchByIds(delListIds);
        }
        // 再插入bom数据
        if (insertList.size() > 0) {
            insertList.forEach(b -> {
                b.setFactoryCode(factory);
                b.setEnabled(false);
                b.setIsDefault(false);
                b.setReleaseState("DRAFT");
            });
            this.saveBatch(insertList);
        }
        for (MdmBomChildEntity mdmBomChild : insertChildList) {
            mdmBomChild.setFactoryCode(factory);
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return R.ok(null, "BOM草案导入成功，须工程变更审批后启用");
    }

    /**
     * 根据产品编码获取该产品子物料bom信息(一层)
     *
     * @param productCode 产品编码
     */
    @Override
    public List<MdmBomChildEntity> getBomChild(String productCode, String bomVersion) {
        LambdaQueryWrapper<MdmBomEntity> wrapper = Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, productCode)
                .eq(MdmBomEntity::getFactoryCode, bomGovernance.factory(null))
                .eq(MdmBomEntity::getEnabled, true).ne(MdmBomEntity::getReleaseState, "DRAFT");
        if (StrUtil.isNotBlank(bomVersion)) {
            wrapper.eq(MdmBomEntity::getVersion, bomVersion);
        } else {
            wrapper.and(w -> w.isNull(MdmBomEntity::getStartDate).or().le(MdmBomEntity::getStartDate, LocalDate.now()))
                    .and(w -> w.isNull(MdmBomEntity::getEndDate).or().ge(MdmBomEntity::getEndDate, LocalDate.now()))
                    .orderByDesc(MdmBomEntity::getIsDefault).orderByDesc(MdmBomEntity::getVersion).last("limit 1");
        }
        MdmBomEntity mdmBomEntity = this.getOne(wrapper);
        if (Objects.isNull(mdmBomEntity)) {
            return null;
        }
        return mdmBomChildMapper.selectList(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBomEntity.getId()));
    }

    private MdmBomEntity insertExcel(MdmBomExcelVO excelVO, Snowflake snowflake) {
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtils.copyProperties(excelVO, mdmBom);
        mdmBom.setId(snowflake.nextId());
        mdmBom.setEnabled(Boolean.FALSE);
        mdmBom.setIsDefault(false);
        mdmBom.setReleaseState("DRAFT");
        return mdmBom;
    }

    /**
     * 递归获取子物料
     */
    private void getChildMaterials(List<MdmBomChildEntity> allBomChild, List<MdmBomChildDTO> result, Long bomId) {
        getChildMaterials(allBomChild, result, bomId, new HashSet<>());
    }

    private void requireExecutable(MdmBomEntity bom) {
        MdmBomGovernance.require(bom != null && Boolean.TRUE.equals(bom.getEnabled()) && !"DRAFT".equals(bom.getReleaseState()),
                "BOM未启用或尚未通过工程发布，不可用于业务展开");
        MdmBomGovernance.require(Objects.equals(bom.getFactoryCode(), bomGovernance.factory(null)), "BOM不属于当前工厂");
    }

    private void getChildMaterials(List<MdmBomChildEntity> allBomChild, List<MdmBomChildDTO> result, Long bomId, Set<Long> path) {
        MdmBomGovernance.require(path.size() < 64 && path.add(bomId), "BOM循环引用或超过64层，不可业务展开");
        requireExecutable(baseMapper.selectById(bomId));
        List<MdmBomChildDTO> childEntities = allBomChild.stream()
                .filter(item -> item.getBomId().equals(bomId))
                .toList()
                .stream()
                .map(item -> {
                    MdmBomChildDTO mdmBomChildDTO = new MdmBomChildDTO();
                    BeanUtil.copyProperties(item, mdmBomChildDTO);
                    return mdmBomChildDTO;
                })
                .toList();
        result.addAll(childEntities);
        for (MdmBomChildDTO childEntity : childEntities) {
            if (childEntity.getSubitemBomId() != null && childEntity.getSubitemBomId() != 0) {
                childEntity.setChildren(new ArrayList<>());
                getChildMaterials(allBomChild, childEntity.getChildren(), childEntity.getSubitemBomId(), path);
            }
        }
        path.remove(bomId);
    }

    /**
     * 递归组装维护态BOM。维护预览不要求版本已发布，但每一层都必须属于当前工厂，
     * 且仍执行循环引用与最大层级保护。
     */
    private void getMaintenanceStructure(List<MdmBomChildEntity> allBomChild, List<MdmBomChildDTO> result,
                                         Long bomId, String factoryCode, Set<Long> path) {
        MdmBomGovernance.require(path.size() < 64 && path.add(bomId), "BOM循环引用或超过64层，不可预览");
        MdmBomEntity bom = baseMapper.selectById(bomId);
        MdmBomGovernance.require(bom != null && Objects.equals(factoryCode, bom.getFactoryCode()),
                "BOM子装配版本不存在或不属于当前工厂");
        List<MdmBomChildDTO> children = allBomChild.stream()
                .filter(item -> Objects.equals(item.getBomId(), bomId))
                .map(item -> {
                    MdmBomChildDTO target = new MdmBomChildDTO();
                    BeanUtil.copyProperties(item, target);
                    return target;
                }).toList();
        result.addAll(children);
        for (MdmBomChildDTO child : children) {
            if (child.getSubitemBomId() != null && child.getSubitemBomId() != 0) {
                child.setChildren(new ArrayList<>());
                getMaintenanceStructure(allBomChild, child.getChildren(), child.getSubitemBomId(), factoryCode, path);
            }
        }
        path.remove(bomId);
    }

}
