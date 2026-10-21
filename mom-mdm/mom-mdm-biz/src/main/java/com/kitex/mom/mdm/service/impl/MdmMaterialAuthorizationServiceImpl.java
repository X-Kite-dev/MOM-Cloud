package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationActionDTO;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationSummaryDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.MdmMaterialAuthorizationLogMapper;
import com.kitex.mom.mdm.mapper.MdmMaterialAuthorizationMapper;
import com.kitex.mom.mdm.service.MdmCustomerService;
import com.kitex.mom.mdm.service.MdmMaterialAuthorizationService;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmSupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MdmMaterialAuthorizationServiceImpl
        extends ServiceImpl<MdmMaterialAuthorizationMapper, MdmMaterialAuthorizationEntity>
        implements MdmMaterialAuthorizationService {

    public static final String SUPPLIER = "SUPPLIER_CERTIFICATION";
    public static final String CUSTOMER = "CUSTOMER_APPROVAL";
    private static final Set<String> TYPES = Set.of(SUPPLIER, CUSTOMER);
    private static final Set<String> LEVELS = Set.of("NORMAL", "TIGHTENED");

    private final MdmMaterialService materialService;
    private final MdmSupplierService supplierService;
    private final MdmCustomerService customerService;
    private final MdmMaterialAuthorizationLogMapper authorizationLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(MdmMaterialAuthorizationEntity entity) {
        normalizeAndValidate(entity);
        assertAuthorizationNoUnique(entity);
        entity.setId(null);
        entity.setStatus("DRAFT");
        clearLifecycle(entity);
        save(entity);
        saveLog(entity.getId(), "CREATE", null, "DRAFT", null, null, JSONUtil.toJsonStr(entity), entity.getFactoryCode());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDraft(MdmMaterialAuthorizationEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw new CheckedException("物料授权ID不能为空");
        }
        MdmMaterialAuthorizationEntity current = getById(entity.getId());
        if (current == null || !"DRAFT".equals(current.getStatus())) {
            throw new CheckedException("只有草稿状态的物料授权可以修改");
        }
        normalizeAndValidate(entity);
        assertAuthorizationNoUnique(entity);
        entity.setStatus("DRAFT");
        clearLifecycle(entity);
        boolean updated = updateById(entity);
        saveLog(entity.getId(), "UPDATE", "DRAFT", "DRAFT", null,
                JSONUtil.toJsonStr(current), JSONUtil.toJsonStr(entity), entity.getFactoryCode());
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeStatus(MdmMaterialAuthorizationActionDTO action) {
        if (action == null || action.getId() == null || StrUtil.isBlank(action.getAction())) {
            throw new CheckedException("物料授权状态操作缺少ID或动作");
        }
        MdmMaterialAuthorizationEntity current = getById(action.getId());
        if (current == null) {
            throw new CheckedException("物料授权记录不存在");
        }
        String target = action.getAction().trim().toUpperCase(Locale.ROOT);
        String beforeSnapshot = JSONUtil.toJsonStr(current);
        String fromStatus = current.getStatus();
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        if ("ACTIVATE".equals(target)) {
            if (!Set.of("DRAFT", "SUSPENDED").contains(current.getStatus())) {
                throw new CheckedException("只有草稿或暂停状态的授权可以启用");
            }
            normalizeAndValidate(current);
            if (current.getValidEnd().isBefore(LocalDate.now())) {
                throw new CheckedException("授权已过期，不能启用");
            }
            assertNoActiveOverlap(current);
            current.setStatus("ACTIVE");
            current.setApprovedBy(user.getUsername());
            current.setApprovedByName(user.getName());
            current.setApprovedTime(now);
            current.setCloseReason(null);
            current.setClosedBy(null);
            current.setClosedByName(null);
            current.setClosedTime(null);
        } else if ("SUSPEND".equals(target) || "REVOKE".equals(target)) {
            if (!"ACTIVE".equals(current.getStatus())) {
                throw new CheckedException("只有启用状态的授权可以暂停或撤销");
            }
            if (StrUtil.isBlank(action.getReason())) {
                throw new CheckedException("暂停或撤销必须填写原因");
            }
            current.setStatus("SUSPEND".equals(target) ? "SUSPENDED" : "REVOKED");
            current.setCloseReason(StrUtil.sub(action.getReason().trim(), 0, 500));
            current.setClosedBy(user.getUsername());
            current.setClosedByName(user.getName());
            current.setClosedTime(now);
        } else {
            throw new CheckedException("不支持的物料授权动作：" + target);
        }
        boolean updated = updateById(current);
        saveLog(current.getId(), target, fromStatus, current.getStatus(), action.getReason(),
                beforeSnapshot, JSONUtil.toJsonStr(current), current.getFactoryCode());
        return updated;
    }

    @Override
    public List<MdmMaterialAuthorizationEntity> active(String type, String materialCode,
                                                       String partnerCode, LocalDate bizDate) {
        String normalizedType = normalizeType(type);
        if (StrUtil.isBlank(materialCode)) {
            throw new CheckedException("查询有效授权时物料编码不能为空");
        }
        LocalDate date = bizDate == null ? LocalDate.now() : bizDate;
        return list(new LambdaQueryWrapper<MdmMaterialAuthorizationEntity>()
                .eq(MdmMaterialAuthorizationEntity::getAuthorizationType, normalizedType)
                .eq(MdmMaterialAuthorizationEntity::getMaterialCode, materialCode.trim())
                .eq(StrUtil.isNotBlank(partnerCode), MdmMaterialAuthorizationEntity::getPartnerCode,
                        StrUtil.trim(partnerCode))
                .eq(MdmMaterialAuthorizationEntity::getStatus, "ACTIVE")
                .le(MdmMaterialAuthorizationEntity::getValidStart, date)
                .ge(MdmMaterialAuthorizationEntity::getValidEnd, date)
                .orderByAsc(MdmMaterialAuthorizationEntity::getValidEnd));
    }

    @Override
    public MdmMaterialAuthorizationSummaryDTO summary(String type, String partnerCode, String materialCode) {
        List<MdmMaterialAuthorizationEntity> records = list(new LambdaQueryWrapper<MdmMaterialAuthorizationEntity>()
                .eq(StrUtil.isNotBlank(type), MdmMaterialAuthorizationEntity::getAuthorizationType,
                        StrUtil.isBlank(type) ? null : normalizeType(type))
                .eq(StrUtil.isNotBlank(partnerCode), MdmMaterialAuthorizationEntity::getPartnerCode, partnerCode)
                .eq(StrUtil.isNotBlank(materialCode), MdmMaterialAuthorizationEntity::getMaterialCode, materialCode));
        MdmMaterialAuthorizationSummaryDTO result = new MdmMaterialAuthorizationSummaryDTO();
        for (MdmMaterialAuthorizationEntity record : records) {
            enrichValidity(record);
            switch (record.getStatus()) {
                case "DRAFT" -> result.setDraftCount(result.getDraftCount() + 1);
                case "SUSPENDED" -> result.setSuspendedCount(result.getSuspendedCount() + 1);
                case "REVOKED" -> result.setRevokedCount(result.getRevokedCount() + 1);
                case "ACTIVE" -> {
                    if ("EXPIRED".equals(record.getValidityState())) {
                        result.setExpiredCount(result.getExpiredCount() + 1);
                    } else {
                        result.setActiveCount(result.getActiveCount() + 1);
                        if ("EXPIRING".equals(record.getValidityState())) {
                            result.setExpiringCount(result.getExpiringCount() + 1);
                        }
                    }
                }
                default -> {
                }
            }
        }
        return result;
    }

    @Override
    public MdmMaterialAuthorizationEntity enrichValidity(MdmMaterialAuthorizationEntity entity) {
        if (entity == null || entity.getValidEnd() == null) {
            return entity;
        }
        LocalDate today = LocalDate.now();
        entity.setDaysToExpire(ChronoUnit.DAYS.between(today, entity.getValidEnd()));
        if (!"ACTIVE".equals(entity.getStatus())) {
            entity.setValidityState(Set.of("SUSPENDED", "REVOKED").contains(entity.getStatus()) ? "CLOSED" : "NOT_STARTED");
        } else if (entity.getValidStart() != null && entity.getValidStart().isAfter(today)) {
            entity.setValidityState("NOT_STARTED");
        } else if (entity.getValidEnd().isBefore(today)) {
            entity.setValidityState("EXPIRED");
        } else if (!entity.getValidEnd().isAfter(today.plusDays(30))) {
            entity.setValidityState("EXPIRING");
        } else {
            entity.setValidityState("VALID");
        }
        return entity;
    }

    private void normalizeAndValidate(MdmMaterialAuthorizationEntity entity) {
        if (entity == null) {
            throw new CheckedException("物料授权不能为空");
        }
        entity.setAuthorizationType(normalizeType(entity.getAuthorizationType()));
        entity.setPartnerCode(StrUtil.trim(entity.getPartnerCode()));
        entity.setMaterialCode(StrUtil.trim(entity.getMaterialCode()));
        entity.setAuthorizationNo(StrUtil.trim(entity.getAuthorizationNo()));
        if (StrUtil.hasBlank(entity.getPartnerCode(), entity.getMaterialCode(), entity.getAuthorizationNo())
                || entity.getValidStart() == null || entity.getValidEnd() == null) {
            throw new CheckedException("合作方、物料、授权编号及有效期不能为空");
        }
        if (entity.getValidStart().isAfter(entity.getValidEnd())) {
            throw new CheckedException("授权有效开始日期不能晚于截止日期");
        }
        MdmMaterialEntity material = materialService.getOne(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getMaterialCode, entity.getMaterialCode()), false);
        if (material == null || !Boolean.TRUE.equals(material.getStatus())) {
            throw new CheckedException("物料[" + entity.getMaterialCode() + "]不存在或未启用");
        }
        entity.setMaterialName(material.getMaterialName());
        if (SUPPLIER.equals(entity.getAuthorizationType())) {
            MdmSupplierEntity supplier = supplierService.getOne(new LambdaQueryWrapper<MdmSupplierEntity>()
                    .eq(MdmSupplierEntity::getSupplierCode, entity.getPartnerCode()), false);
            if (supplier == null || !Boolean.TRUE.equals(supplier.getStatus())
                    || !Boolean.TRUE.equals(supplier.getIsDeliver()) || !"1".equals(supplier.getCheckStatus())) {
                throw new CheckedException("供应商[" + entity.getPartnerCode() + "]不存在、未启用、未通过审核或不可供货");
            }
            entity.setPartnerName(supplier.getSupplierName());
            entity.setInspectionLevel(StrUtil.blankToDefault(entity.getInspectionLevel(), "NORMAL").toUpperCase(Locale.ROOT));
            if (!LEVELS.contains(entity.getInspectionLevel())) {
                throw new CheckedException("供应商认证检验等级仅支持NORMAL或TIGHTENED");
            }
            if (entity.getReferenceUnitPrice() != null && entity.getReferenceUnitPrice().signum() < 0) {
                throw new CheckedException("参考单价不能小于0");
            }
            entity.setCurrency(StrUtil.blankToDefault(entity.getCurrency(), "CNY").toUpperCase(Locale.ROOT));
        } else {
            MdmCustomerEntity customer = customerService.getOne(new LambdaQueryWrapper<MdmCustomerEntity>()
                    .eq(MdmCustomerEntity::getCustomerCode, entity.getPartnerCode()), false);
            if (customer == null || !Boolean.TRUE.equals(customer.getStatus())) {
                throw new CheckedException("客户[" + entity.getPartnerCode() + "]不存在或未启用");
            }
            entity.setPartnerName(customer.getCustomerName());
            entity.setInspectionLevel(null);
            entity.setQualityStandardId(null);
            entity.setQualityStandardName(null);
            entity.setReferenceUnitPrice(null);
            entity.setCurrency(null);
        }
    }

    private String normalizeType(String type) {
        String normalized = StrUtil.blankToDefault(type, "").trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw new CheckedException("授权类型仅支持供应商认证或客户批准");
        }
        return normalized;
    }

    private void assertAuthorizationNoUnique(MdmMaterialAuthorizationEntity entity) {
        long count = count(new LambdaQueryWrapper<MdmMaterialAuthorizationEntity>()
                .eq(MdmMaterialAuthorizationEntity::getAuthorizationType, entity.getAuthorizationType())
                .eq(MdmMaterialAuthorizationEntity::getAuthorizationNo, entity.getAuthorizationNo())
                .ne(entity.getId() != null, MdmMaterialAuthorizationEntity::getId, entity.getId()));
        if (count > 0) {
            throw new CheckedException("授权编号[" + entity.getAuthorizationNo() + "]已存在");
        }
    }

    private void assertNoActiveOverlap(MdmMaterialAuthorizationEntity entity) {
        List<MdmMaterialAuthorizationEntity> active = list(new LambdaQueryWrapper<MdmMaterialAuthorizationEntity>()
                .eq(MdmMaterialAuthorizationEntity::getAuthorizationType, entity.getAuthorizationType())
                .eq(MdmMaterialAuthorizationEntity::getPartnerCode, entity.getPartnerCode())
                .eq(MdmMaterialAuthorizationEntity::getMaterialCode, entity.getMaterialCode())
                .eq(MdmMaterialAuthorizationEntity::getStatus, "ACTIVE")
                .ne(entity.getId() != null, MdmMaterialAuthorizationEntity::getId, entity.getId()));
        boolean overlap = active.stream().anyMatch(item -> !item.getValidEnd().isBefore(entity.getValidStart())
                && !item.getValidStart().isAfter(entity.getValidEnd()));
        if (overlap) {
            throw new CheckedException("同一合作方与物料在该有效期内已有启用授权，请先暂停或撤销旧记录");
        }
    }

    private void clearLifecycle(MdmMaterialAuthorizationEntity entity) {
        entity.setApprovedBy(null);
        entity.setApprovedByName(null);
        entity.setApprovedTime(null);
        entity.setCloseReason(null);
        entity.setClosedBy(null);
        entity.setClosedByName(null);
        entity.setClosedTime(null);
    }

    private void saveLog(Long authorizationId, String action, String fromStatus, String toStatus, String reason,
                         String beforeSnapshot, String afterSnapshot, String factoryCode) {
        MomUser user = SecurityUtils.getUser();
        MdmMaterialAuthorizationLogEntity log = new MdmMaterialAuthorizationLogEntity();
        log.setAuthorizationId(authorizationId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setReason(reason);
        log.setOperatedBy(user.getUsername());
        log.setOperatedByName(user.getName());
        log.setOperatedTime(LocalDateTime.now());
        log.setBeforeSnapshot(beforeSnapshot);
        log.setAfterSnapshot(afterSnapshot);
        log.setFactoryCode(factoryCode);
        authorizationLogMapper.insert(log);
    }
}
