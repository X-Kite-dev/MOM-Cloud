package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogQueryDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverRuleActionDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmChangeoverRuleService;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmSchedulingFamilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MdmChangeoverRuleServiceImpl extends ServiceImpl<MdmChangeoverRuleMapper, MdmChangeoverRuleEntity>
        implements MdmChangeoverRuleService {
    private static final Set<String> SCOPES = Set.of("LINE", "STATION", "DEVICE", "TOOLING");
    private final MdmSchedulingFamilyService familyService;
    private final MdmMaterialService materialService;
    private final MdmStationSettingMapper stationMapper;
    private final MdmDeviceMapper deviceMapper;
    private final MdmToolingMapper toolingMapper;
    private final MdmFactoryModelMapper factoryModelMapper;
    private final MdmProcedureMapper procedureMapper;
    private final MdmChangeoverRuleLogMapper logMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(MdmChangeoverRuleEntity entity) {
        if (entity == null) throw new CheckedException("换型规则不能为空");
        entity.setFactoryCode(requireCurrentFactory());
        normalizeAndValidate(entity);
        assertRuleNoUnique(entity);
        entity.setId(null);
        entity.setStatus("DRAFT");
        entity.setVersionNo(entity.getVersionNo() == null ? 1 : entity.getVersionNo());
        clearLifecycle(entity);
        save(entity);
        log(entity, "CREATE", null, "DRAFT", null, null, JSONUtil.toJsonStr(entity));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDraft(MdmChangeoverRuleEntity entity) {
        if (entity == null || entity.getId() == null) throw new CheckedException("换型规则ID不能为空");
        MdmChangeoverRuleEntity current = getById(entity.getId());
        if (current == null || !"DRAFT".equals(current.getStatus())) {
            throw new CheckedException("只有草稿状态的换型规则可以修改");
        }
        entity.setFactoryCode(current.getFactoryCode());
        normalizeAndValidate(entity);
        assertRuleNoUnique(entity);
        entity.setStatus("DRAFT");
        clearLifecycle(entity);
        boolean updated = updateById(entity);
        log(entity, "UPDATE", "DRAFT", "DRAFT", null, JSONUtil.toJsonStr(current), JSONUtil.toJsonStr(entity));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeStatus(MdmChangeoverRuleActionDTO action) {
        if (action == null || action.getId() == null || StrUtil.isBlank(action.getAction())) {
            throw new CheckedException("换型规则状态操作缺少ID或动作");
        }
        MdmChangeoverRuleEntity current = getById(action.getId());
        if (current == null) throw new CheckedException("换型规则不存在");
        MomUser user = SecurityUtils.getUser();
        String operator = user.getUsername();
        String target = action.getAction().trim().toUpperCase(Locale.ROOT);
        String before = JSONUtil.toJsonStr(current);
        String from = current.getStatus();
        LocalDateTime now = LocalDateTime.now();
        if ("ACTIVATE".equals(target)) {
            if (!Set.of("DRAFT", "SUSPENDED").contains(from)) throw new CheckedException("只有草稿或暂停规则可以启用");
            if (StrUtil.isNotBlank(current.getCreateBy()) && Objects.equals(current.getCreateBy(), operator)) {
                throw new CheckedException("换型规则编制人与启用人必须岗位分离");
            }
            normalizeAndValidate(current);
            if (current.getValidEndTime() != null && !current.getValidEndTime().isAfter(now)) {
                throw new CheckedException("换型规则已超过有效期，不能启用");
            }
            assertNoActiveOverlap(current);
            current.setStatus("ACTIVE");
            current.setApprovedBy(operator);
            current.setApprovedByName(user.getName());
            current.setApprovedTime(now);
            current.setCloseReason(null);
            current.setClosedBy(null);
            current.setClosedByName(null);
            current.setClosedTime(null);
        } else if ("SUSPEND".equals(target) || "RETIRE".equals(target)) {
            if (!"ACTIVE".equals(from)) throw new CheckedException("只有启用规则可以暂停或退役");
            if (StrUtil.isBlank(action.getReason())) throw new CheckedException("暂停或退役必须填写原因");
            current.setStatus("SUSPEND".equals(target) ? "SUSPENDED" : "RETIRED");
            current.setCloseReason(StrUtil.sub(action.getReason().trim(), 0, 500));
            current.setClosedBy(operator);
            current.setClosedByName(user.getName());
            current.setClosedTime(now);
        } else {
            throw new CheckedException("不支持的换型规则动作：" + target);
        }
        updateById(current);
        log(current, target, from, current.getStatus(), action.getReason(), before, JSONUtil.toJsonStr(current));
        return true;
    }

    @Override
    public MdmChangeoverCatalogDTO catalog(MdmChangeoverCatalogQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())
                || CollUtil.isEmpty(query.getProductCodes()) || query.getStartTime() == null
                || query.getEndTime() == null || !query.getEndTime().isAfter(query.getStartTime())) {
            throw new CheckedException("换型目录查询的工厂、产品和时间范围不完整");
        }
        String factoryCode = query.getFactoryCode().trim();
        List<String> products = query.getProductCodes().stream().map(StrUtil::trim)
                .filter(StrUtil::isNotBlank).distinct().toList();
        List<MdmMaterialEntity> materials = materialService.list(new LambdaQueryWrapper<MdmMaterialEntity>()
                .in(MdmMaterialEntity::getMaterialCode, products)
                .eq(MdmMaterialEntity::getFactoryCode, factoryCode)
                .eq(MdmMaterialEntity::getStatus, true));
        Map<String, String> familyByProduct = materials.stream().filter(item -> StrUtil.isNotBlank(item.getSchedulingFamilyCode()))
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getSchedulingFamilyCode,
                        (a, b) -> a, LinkedHashMap::new));
        Set<String> familyCodes = new LinkedHashSet<>(familyByProduct.values());
        Map<String, String> familyNames = familyCodes.isEmpty() ? Map.of() : familyService.list(
                new LambdaQueryWrapper<MdmSchedulingFamilyEntity>().in(MdmSchedulingFamilyEntity::getFamilyCode, familyCodes)
                        .eq(MdmSchedulingFamilyEntity::getFactoryCode, factoryCode)
                        .eq(MdmSchedulingFamilyEntity::getEnabled, true)).stream().collect(Collectors.toMap(
                MdmSchedulingFamilyEntity::getFamilyCode, MdmSchedulingFamilyEntity::getFamilyName, (a, b) -> a,
                LinkedHashMap::new));
        List<MdmChangeoverRuleEntity> rules = list(new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                .eq(MdmChangeoverRuleEntity::getStatus, "ACTIVE")
                .eq(MdmChangeoverRuleEntity::getFactoryCode, factoryCode)
                .lt(MdmChangeoverRuleEntity::getValidStartTime, query.getEndTime())
                .and(w -> w.isNull(MdmChangeoverRuleEntity::getValidEndTime)
                        .or().gt(MdmChangeoverRuleEntity::getValidEndTime, query.getStartTime()))
                .orderByAsc(MdmChangeoverRuleEntity::getPriority)
                .orderByDesc(MdmChangeoverRuleEntity::getVersionNo));
        List<String> missing = products.stream().filter(code -> !familyByProduct.containsKey(code)).toList();
        return new MdmChangeoverCatalogDTO().setFamilyByProduct(familyByProduct).setFamilyNames(familyNames)
                .setRules(rules).setProductsWithoutFamily(missing).setGovernanceRequired(!rules.isEmpty());
    }

    private void normalizeAndValidate(MdmChangeoverRuleEntity entity) {
        if (entity == null) throw new CheckedException("换型规则不能为空");
        entity.setRuleNo(StrUtil.trim(entity.getRuleNo()));
        entity.setRuleName(StrUtil.trim(entity.getRuleName()));
        entity.setScopeType(StrUtil.blankToDefault(entity.getScopeType(), "").trim().toUpperCase(Locale.ROOT));
        entity.setResourceCode(StrUtil.trim(entity.getResourceCode()));
        String processCode = StrUtil.trim(entity.getProcessCode());
        entity.setProcessCode(StrUtil.isBlank(processCode) ? null : processCode);
        entity.setFromFamilyCode(StrUtil.trim(entity.getFromFamilyCode()));
        entity.setToFamilyCode(StrUtil.trim(entity.getToFamilyCode()));
        if (StrUtil.hasBlank(entity.getRuleNo(), entity.getRuleName(), entity.getScopeType(), entity.getResourceCode(),
                entity.getFromFamilyCode(), entity.getToFamilyCode()) || entity.getValidStartTime() == null) {
            throw new CheckedException("规则编号、名称、范围资源、起止产品族和生效时间不能为空");
        }
        if (!SCOPES.contains(entity.getScopeType())) {
            throw new CheckedException("换型规则范围仅支持产线、工位、设备或工装");
        }
        if ("LINE".equals(entity.getScopeType()) && StrUtil.isNotBlank(entity.getProcessCode())) {
            throw new CheckedException("产线级换型规则用于MPS粗能力评估，不能限定单一工序");
        }
        if (entity.getValidEndTime() != null && !entity.getValidEndTime().isAfter(entity.getValidStartTime())) {
            throw new CheckedException("规则失效时间必须晚于生效时间");
        }
        entity.setCleaningMinutes(entity.getCleaningMinutes() == null ? 0L : entity.getCleaningMinutes());
        entity.setSetupMinutes(entity.getSetupMinutes() == null ? 0L : entity.getSetupMinutes());
        entity.setVersionNo(entity.getVersionNo() == null ? 1 : entity.getVersionNo());
        entity.setPriority(entity.getPriority() == null ? 100 : entity.getPriority());
        entity.setForbidden(Boolean.TRUE.equals(entity.getForbidden()));
        if (entity.getCleaningMinutes() < 0 || entity.getSetupMinutes() < 0 || entity.getPriority() < 0
                || entity.getVersionNo() <= 0) {
            throw new CheckedException("清洁、准备时间和优先级不能小于0，规则版本必须大于0");
        }
        if (!entity.getForbidden() && entity.getTotalMinutes() <= 0) {
            throw new CheckedException("非禁排规则的清洁与准备时间合计必须大于0");
        }
        resolveFamily(entity, true);
        resolveFamily(entity, false);
        resolveProcess(entity);
        resolveResource(entity);
    }

    private void resolveFamily(MdmChangeoverRuleEntity entity, boolean from) {
        String code = from ? entity.getFromFamilyCode() : entity.getToFamilyCode();
        MdmSchedulingFamilyEntity family = familyService.getOne(new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .eq(MdmSchedulingFamilyEntity::getFamilyCode, code)
                .eq(MdmSchedulingFamilyEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmSchedulingFamilyEntity::getEnabled, true), false);
        if (family == null) throw new CheckedException("排产产品族[" + code + "]不存在或未启用");
        if (from) entity.setFromFamilyName(family.getFamilyName());
        else entity.setToFamilyName(family.getFamilyName());
    }

    private void resolveProcess(MdmChangeoverRuleEntity entity) {
        if (StrUtil.isBlank(entity.getProcessCode())) {
            entity.setProcessName(null);
            return;
        }
        MdmProcedureEntity procedure = procedureMapper.selectOne(new LambdaQueryWrapper<MdmProcedureEntity>()
                .eq(MdmProcedureEntity::getProcessCode, entity.getProcessCode())
                .eq(MdmProcedureEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmProcedureEntity::getStatus, true)
                .last("limit 1"));
        if (procedure == null) throw new CheckedException("工序[" + entity.getProcessCode() + "]不存在或未启用");
        entity.setProcessName(procedure.getProcessName());
    }

    private void resolveResource(MdmChangeoverRuleEntity entity) {
        if ("LINE".equals(entity.getScopeType())) {
            List<MdmFactoryModelEntity> lines = factoryModelMapper.selectList(
                    new LambdaQueryWrapper<MdmFactoryModelEntity>()
                            .eq(MdmFactoryModelEntity::getCode, entity.getResourceCode())
                            .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.LINE.getCode())
                            .eq(MdmFactoryModelEntity::getStatus, true));
            List<MdmFactoryModelEntity> currentFactoryLines = lines.stream().filter(line ->
                    factoryModelMapper.selectAncestors(line.getId()).stream().anyMatch(parent ->
                            FactoryModelTypeEnum.FACTORY.getCode().equals(parent.getType())
                                    && Objects.equals(entity.getFactoryCode(), parent.getCode())
                                    && Boolean.TRUE.equals(parent.getStatus()))).toList();
            if (currentFactoryLines.isEmpty()) {
                throw new CheckedException("产线[" + entity.getResourceCode() + "]不存在、未启用或不属于当前工厂");
            }
            if (currentFactoryLines.size() > 1) {
                throw new CheckedException("当前工厂存在重复的启用产线编码[" + entity.getResourceCode() + "]，请先治理主数据");
            }
            entity.setResourceName(currentFactoryLines.get(0).getName());
        } else if ("STATION".equals(entity.getScopeType())) {
            MdmStationSettingEntity station = stationMapper.selectOne(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getStationCode, entity.getResourceCode())
                    .eq(MdmStationSettingEntity::getFactoryCode, entity.getFactoryCode())
                    .eq(MdmStationSettingEntity::getStatus, true).last("limit 1"));
            if (station == null) throw new CheckedException("工位[" + entity.getResourceCode() + "]不存在或未启用");
            entity.setResourceName(station.getStationName());
        } else if ("DEVICE".equals(entity.getScopeType())) {
            MdmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getDeviceCode, entity.getResourceCode())
                    .eq(MdmDeviceEntity::getFactoryCode, entity.getFactoryCode())
                    .eq(MdmDeviceEntity::getStatus, true)
                    .last("limit 1"));
            if (device == null) throw new CheckedException("设备[" + entity.getResourceCode() + "]不存在或未启用");
            entity.setResourceName(device.getDeviceName());
        } else {
            MdmToolingEntity tooling = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                    .eq(MdmToolingEntity::getToolingCode, entity.getResourceCode())
                    .eq(MdmToolingEntity::getFactoryCode, entity.getFactoryCode())
                    .eq(MdmToolingEntity::getStatus, true)
                    .last("limit 1"));
            if (tooling == null) throw new CheckedException("工装[" + entity.getResourceCode() + "]不存在或未启用");
            entity.setResourceName(tooling.getToolingName());
        }
    }

    private void assertRuleNoUnique(MdmChangeoverRuleEntity entity) {
        long count = count(new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                .eq(MdmChangeoverRuleEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmChangeoverRuleEntity::getRuleNo, entity.getRuleNo())
                .eq(MdmChangeoverRuleEntity::getVersionNo, entity.getVersionNo())
                .ne(entity.getId() != null, MdmChangeoverRuleEntity::getId, entity.getId()));
        if (count > 0) throw new CheckedException("换型规则[" + entity.getRuleNo() + "]版本V"
                + entity.getVersionNo() + "已存在");
    }

    private void assertNoActiveOverlap(MdmChangeoverRuleEntity entity) {
        List<MdmChangeoverRuleEntity> active = list(new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                .eq(MdmChangeoverRuleEntity::getStatus, "ACTIVE")
                .eq(MdmChangeoverRuleEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmChangeoverRuleEntity::getScopeType, entity.getScopeType())
                .eq(MdmChangeoverRuleEntity::getResourceCode, entity.getResourceCode())
                .eq(MdmChangeoverRuleEntity::getFromFamilyCode, entity.getFromFamilyCode())
                .eq(MdmChangeoverRuleEntity::getToFamilyCode, entity.getToFamilyCode())
                .ne(entity.getId() != null, MdmChangeoverRuleEntity::getId, entity.getId()));
        boolean overlap = active.stream().filter(item -> Objects.equals(StrUtil.emptyToNull(item.getProcessCode()),
                        StrUtil.emptyToNull(entity.getProcessCode())))
                .anyMatch(item -> item.getValidStartTime().isBefore(Optional.ofNullable(entity.getValidEndTime())
                        .orElse(LocalDateTime.MAX))
                        && entity.getValidStartTime().isBefore(Optional.ofNullable(item.getValidEndTime())
                        .orElse(LocalDateTime.MAX)));
        if (overlap) throw new CheckedException("同范围、资源、工序和产品族方向在该有效期内已有启用规则");
    }

    private void clearLifecycle(MdmChangeoverRuleEntity entity) {
        entity.setApprovedBy(null);
        entity.setApprovedByName(null);
        entity.setApprovedTime(null);
        entity.setCloseReason(null);
        entity.setClosedBy(null);
        entity.setClosedByName(null);
        entity.setClosedTime(null);
    }

    private void log(MdmChangeoverRuleEntity entity, String action, String from, String to, String reason,
                     String before, String after) {
        MomUser user = SecurityUtils.getUser();
        MdmChangeoverRuleLogEntity row = new MdmChangeoverRuleLogEntity();
        row.setRuleId(entity.getId());
        row.setAction(action);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason(reason);
        row.setOperatedBy(user.getUsername());
        row.setOperatedByName(user.getName());
        row.setOperatedTime(LocalDateTime.now());
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        row.setFactoryCode(entity.getFactoryCode());
        logMapper.insert(row);
    }

    private String requireCurrentFactory() {
        String factoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        if (StrUtil.isBlank(factoryCode)) throw new CheckedException("请先选择当前工厂");
        return factoryCode;
    }
}
