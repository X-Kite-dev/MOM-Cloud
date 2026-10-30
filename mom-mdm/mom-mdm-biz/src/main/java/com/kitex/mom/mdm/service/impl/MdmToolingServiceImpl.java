package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import com.kitex.mom.mdm.service.MdmToolingService;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MdmToolingServiceImpl implements MdmToolingService {
    private static final int MAX_QUERY_DAYS = 730;
    private static final Set<String> TOOLING_TYPES = Set.of("MOLD", "TOOL", "FIXTURE", "GAUGE", "AUXILIARY");
    private static final Set<String> TOOLING_STATES = Set.of("AVAILABLE", "IN_USE", "MAINTENANCE",
            "CALIBRATION", "REPAIR", "LOCKED", "SCRAPPED");
    private static final Set<String> LIFE_UNITS = Set.of("CYCLE", "HOUR", "QUANTITY");
    private static final Set<String> WINDOW_TYPES = Set.of("MAINTENANCE", "CALIBRATION", "REPAIR", "LOCK");
    private static final Set<String> USAGE_MODES = Set.of("FIXED", "PER_QUANTITY", "PER_HOUR");
    private static final Set<String> SCHEDULABLE_STATES = Set.of("AVAILABLE", "IN_USE");
    private final MdmToolingMapper toolingMapper;
    private final MdmToolingDeviceCompatibilityMapper compatibilityMapper;
    private final MdmToolingUnavailableWindowMapper windowMapper;
    private final MdmProcedureToolingRequirementMapper requirementMapper;
    private final MdmToolingUsageLedgerMapper usageLedgerMapper;
    private final MdmDeviceMapper deviceMapper;
    private final MdmProcedureMapper procedureMapper;
    private final MdmProcessRouteMapper routeMapper;
    private final MdmRouteNodeResolver routeNodeResolver;
    private final MdmPlanResourceEventOutboxService resourceEventOutboxService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public List<MdmToolingEntity> listToolings(String toolingType, Boolean enabledOnly) {
        return toolingMapper.selectList(new LambdaQueryWrapper<MdmToolingEntity>()
                .eq(StrUtil.isNotBlank(toolingType), MdmToolingEntity::getToolingType,
                        StrUtil.blankToDefault(toolingType, "").toUpperCase(Locale.ROOT))
                .eq(Boolean.TRUE.equals(enabledOnly), MdmToolingEntity::getStatus, true)
                .orderByAsc(MdmToolingEntity::getToolingType)
                .orderByAsc(MdmToolingEntity::getToolingCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveTooling(MdmToolingEntity tooling) {
        MdmToolingEntity existing = tooling != null && tooling.getId() != null
                ? toolingMapper.selectById(tooling.getId()) : null;
        validateTooling(tooling);
        tooling.setToolingCode(tooling.getToolingCode().trim());
        tooling.setToolingName(tooling.getToolingName().trim());
        tooling.setToolingType(tooling.getToolingType().toUpperCase(Locale.ROOT));
        tooling.setToolingState(StrUtil.blankToDefault(tooling.getToolingState(), "AVAILABLE").toUpperCase(Locale.ROOT));
        tooling.setLifeUnit(StrUtil.blankToDefault(tooling.getLifeUnit(), "CYCLE").toUpperCase(Locale.ROOT));
        tooling.setUsedLife(Optional.ofNullable(tooling.getUsedLife()).orElse(BigDecimal.ZERO));
        tooling.setCalibrationRequired(Boolean.TRUE.equals(tooling.getCalibrationRequired()));
        tooling.setStatus(!Boolean.FALSE.equals(tooling.getStatus()));
        if (existing != null && StrUtil.isBlank(tooling.getFactoryCode())) {
            tooling.setFactoryCode(existing.getFactoryCode());
        }
        boolean unavailable = !Boolean.TRUE.equals(tooling.getStatus())
                || !SCHEDULABLE_STATES.contains(tooling.getToolingState());
        boolean sameUnavailableFact = existing != null && unavailable
                && (!Boolean.TRUE.equals(existing.getStatus())
                || !SCHEDULABLE_STATES.contains(StrUtil.blankToDefault(existing.getToolingState(), "")
                .toUpperCase(Locale.ROOT)))
                && Objects.equals(Boolean.TRUE.equals(tooling.getStatus()), Boolean.TRUE.equals(existing.getStatus()))
                && Objects.equals(tooling.getToolingState(),
                StrUtil.blankToDefault(existing.getToolingState(), "AVAILABLE").toUpperCase(Locale.ROOT));
        tooling.setUnavailableSince(unavailable
                ? (sameUnavailableFact && existing.getUnavailableSince() != null
                ? existing.getUnavailableSince() : LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS))
                : null);
        long duplicate = toolingMapper.selectCount(new LambdaQueryWrapper<MdmToolingEntity>()
                .eq(MdmToolingEntity::getToolingCode, tooling.getToolingCode())
                .ne(tooling.getId() != null, MdmToolingEntity::getId, tooling.getId()));
        if (duplicate > 0) {
            throw new CheckedException("工装编码[" + tooling.getToolingCode() + "]已存在");
        }
        if (tooling.getId() == null) {
            toolingMapper.insert(tooling);
        } else if (toolingMapper.updateById(tooling) != 1) {
            throw new CheckedException("工装不存在或已删除");
        }
        resourceEventOutboxService.synchronizeToolingMasterStatus(tooling);
        return tooling.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeTooling(Long id) {
        MdmToolingEntity tooling = id == null ? null : toolingMapper.selectById(id);
        if (tooling == null) {
            throw new CheckedException("工装不存在或已删除");
        }
        long refs = compatibilityMapper.selectCount(new LambdaQueryWrapper<MdmToolingDeviceCompatibilityEntity>()
                .eq(MdmToolingDeviceCompatibilityEntity::getToolingCode, tooling.getToolingCode()))
                + windowMapper.selectCount(new LambdaQueryWrapper<MdmToolingUnavailableWindowEntity>()
                .eq(MdmToolingUnavailableWindowEntity::getToolingCode, tooling.getToolingCode()))
                + requirementReferenceCount(tooling.getToolingCode());
        if (refs > 0) {
            throw new CheckedException("工装已有设备兼容、不可用窗口或工序要求引用，请先解除引用");
        }
        tooling.setStatus(true);
        tooling.setToolingState("AVAILABLE");
        tooling.setUnavailableSince(null);
        resourceEventOutboxService.synchronizeToolingMasterStatus(tooling);
        return toolingMapper.deleteById(id) == 1;
    }

    @Override
    public MdmToolingProfileDTO getProfile(String toolingCode) {
        MdmToolingEntity tooling = requireTooling(toolingCode);
        MdmToolingProfileDTO result = new MdmToolingProfileDTO();
        result.setTooling(tooling);
        result.setCompatibleDevices(compatibilityMapper.selectList(
                new LambdaQueryWrapper<MdmToolingDeviceCompatibilityEntity>()
                        .eq(MdmToolingDeviceCompatibilityEntity::getToolingCode, toolingCode)
                        .orderByAsc(MdmToolingDeviceCompatibilityEntity::getDeviceCode)));
        result.setUnavailableWindows(windowMapper.selectList(new LambdaQueryWrapper<MdmToolingUnavailableWindowEntity>()
                .eq(MdmToolingUnavailableWindowEntity::getToolingCode, toolingCode)
                .orderByAsc(MdmToolingUnavailableWindowEntity::getStartTime)));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveProfile(String toolingCode, MdmToolingProfileDTO profile) {
        MdmToolingEntity tooling = requireTooling(toolingCode);
        List<MdmToolingDeviceCompatibilityEntity> devices = profile == null || profile.getCompatibleDevices() == null
                ? List.of() : profile.getCompatibleDevices();
        List<MdmToolingUnavailableWindowEntity> windows = profile == null || profile.getUnavailableWindows() == null
                ? List.of() : profile.getUnavailableWindows();
        validateCompatibleDevices(devices);
        validateWindows(windows);
        Map<String, MdmToolingUnavailableWindowEntity> existingWindows = windowMapper.selectList(
                        new LambdaQueryWrapper<MdmToolingUnavailableWindowEntity>()
                                .eq(MdmToolingUnavailableWindowEntity::getToolingCode, toolingCode)).stream()
                .collect(Collectors.toMap(this::toolingWindowKey, Function.identity(), (left, right) -> left));
        compatibilityMapper.delete(new LambdaQueryWrapper<MdmToolingDeviceCompatibilityEntity>()
                .eq(MdmToolingDeviceCompatibilityEntity::getToolingCode, toolingCode));
        for (MdmToolingDeviceCompatibilityEntity item : devices) {
            MdmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getDeviceCode, item.getDeviceCode()));
            item.setId(null);
            item.setToolingCode(tooling.getToolingCode());
            item.setToolingName(tooling.getToolingName());
            item.setDeviceName(device.getDeviceName());
            item.setStatus(!Boolean.FALSE.equals(item.getStatus()));
            compatibilityMapper.insert(item);
        }
        for (MdmToolingUnavailableWindowEntity item : windows) {
            item.setToolingCode(tooling.getToolingCode());
            item.setToolingName(tooling.getToolingName());
            item.setWindowType(item.getWindowType().toUpperCase(Locale.ROOT));
            item.setStatus(!Boolean.FALSE.equals(item.getStatus()));
            item.setReason(StrUtil.trim(item.getReason()));
            item.setFactoryCode(tooling.getFactoryCode());
            MdmToolingUnavailableWindowEntity existing = existingWindows.remove(toolingWindowKey(item));
            if (existing == null) {
                item.setId(null);
                require(windowMapper.insert(item) == 1, "工装不可用窗口保存失败");
            } else {
                item.setId(existing.getId());
                require(windowMapper.updateById(item) == 1, "工装不可用窗口更新失败");
            }
        }
        for (MdmToolingUnavailableWindowEntity removed : existingWindows.values()) {
            require(windowMapper.deleteById(removed.getId()) == 1, "旧工装不可用窗口删除失败");
        }
        resourceEventOutboxService.synchronizeToolingWindows(tooling, windows);
        return true;
    }

    @Override
    public List<MdmProcedureToolingRequirementEntity> getRequirements(String routeCode, String processCode) {
        return nodeRequirements(requireRouteAndProcedure(routeCode, null, processCode, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean saveRequirements(String routeCode, String processCode,
                                    List<MdmProcedureToolingRequirementEntity> requirements) {
        return saveNodeRequirements(requireRouteAndProcedure(routeCode, null, processCode, true), requirements);
    }

    @Override
    public List<MdmProcedureToolingRequirementEntity> getNodeRequirements(String routeCode, String routeNodeCode) {
        require(StrUtil.isNotBlank(routeNodeCode), "工装要求必须指定路线节点编号");
        return nodeRequirements(requireRouteAndProcedure(routeCode, routeNodeCode, null, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean saveNodeRequirements(String routeCode, String routeNodeCode,
                                        List<MdmProcedureToolingRequirementEntity> requirements) {
        require(StrUtil.isNotBlank(routeNodeCode), "工装要求必须指定路线节点编号");
        return saveNodeRequirements(requireRouteAndProcedure(routeCode, routeNodeCode, null, true), requirements);
    }

    private LambdaQueryWrapper<MdmProcedureToolingRequirementEntity> nodeRequirementQuery(
            MdmProcessRouteWorkingEntity node) {
        return new LambdaQueryWrapper<MdmProcedureToolingRequirementEntity>()
                .eq(MdmProcedureToolingRequirementEntity::getTenantId, node.getTenantId())
                .eq(MdmProcedureToolingRequirementEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmProcedureToolingRequirementEntity::getRouteCode, node.getRouteCode())
                .eq(MdmProcedureToolingRequirementEntity::getRouteNodeCode, node.getRouteNodeCode());
    }

    private List<MdmProcedureToolingRequirementEntity> nodeRequirements(MdmProcessRouteWorkingEntity node) {
        return requirementMapper.selectList(nodeRequirementQuery(node)
                .orderByAsc(MdmProcedureToolingRequirementEntity::getRequirementSeq));
    }

    private Boolean saveNodeRequirements(MdmProcessRouteWorkingEntity node,
                                         List<MdmProcedureToolingRequirementEntity> requirements) {
        require(requirements != null && requirements.stream().noneMatch(Objects::isNull),
                "请提供完整工装要求列表，清空时提交空列表");
        List<MdmProcedureToolingRequirementEntity> safe = requirements;
        Map<String, MdmToolingEntity> enabledToolings = listToolings(null, true).stream()
                .filter(item -> Objects.equals(item.getTenantId(), node.getTenantId())
                        && Objects.equals(item.getFactoryCode(), node.getFactoryCode()))
                .collect(Collectors.toMap(MdmToolingEntity::getToolingCode, Function.identity(), (a, b) -> a));
        Set<Integer> sequences = new HashSet<>();
        for (int index = 0; index < safe.size(); index++) {
            MdmProcedureToolingRequirementEntity item = safe.get(index);
            validateRequirement(item, index + 1, sequences, enabledToolings);
        }
        requirementMapper.delete(nodeRequirementQuery(node));
        for (int index = 0; index < safe.size(); index++) {
            MdmProcedureToolingRequirementEntity input = safe.get(index);
            MdmProcedureToolingRequirementEntity item = new MdmProcedureToolingRequirementEntity();
            item.setTenantId(node.getTenantId());
            item.setFactoryCode(node.getFactoryCode());
            item.setRouteCode(node.getRouteCode());
            item.setRouteNodeCode(node.getRouteNodeCode());
            item.setProcessCode(node.getProcessCode());
            item.setProcessName(node.getProcessName());
            item.setDelFlag("0");
            item.setToolingType(input.getToolingType().toUpperCase(Locale.ROOT));
            item.setRequirementSeq(Optional.ofNullable(input.getRequirementSeq()).orElse(index + 1));
            item.setRequiredQuantity(Optional.ofNullable(input.getRequiredQuantity()).orElse(1));
            item.setUsageMode(StrUtil.blankToDefault(input.getUsageMode(), "FIXED").toUpperCase(Locale.ROOT));
            item.setUsageValue(Optional.ofNullable(input.getUsageValue()).orElse(BigDecimal.ONE));
            item.setCandidateToolingCodes(input.getCandidateToolingCodes() == null ? null
                    : input.getCandidateToolingCodes().clone());
            item.setDeviceCompatibilityRequired(!Boolean.FALSE.equals(input.getDeviceCompatibilityRequired()));
            item.setMandatory(!Boolean.FALSE.equals(input.getMandatory()));
            item.setStatus(!Boolean.FALSE.equals(input.getStatus()));
            require(requirementMapper.insert(item) == 1, "工装节点要求保存失败");
        }
        return true;
    }

    @Override
    public List<MdmToolingCandidateDTO> availability(MdmToolingAvailabilityQueryDTO query) {
        validateAvailabilityQuery(query);
        List<MdmProcessRouteWorkingEntity> nodes = routeNodeResolver.resolveBatch(query.getRouteCodes(),
                query.getRouteNodeCodes(), query.getProcessCodes());
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<String, MdmProcessRouteWorkingEntity> byNode = nodes.stream().collect(Collectors.toMap(
                MdmProcessRouteWorkingEntity::getRouteNodeCode, Function.identity()));
        List<MdmProcedureToolingRequirementEntity> requirements = requirementMapper.selectList(
                new LambdaQueryWrapper<MdmProcedureToolingRequirementEntity>()
                        .in(MdmProcedureToolingRequirementEntity::getRouteNodeCode, byNode.keySet())
                        .eq(MdmProcedureToolingRequirementEntity::getStatus, true)
                        .orderByAsc(MdmProcedureToolingRequirementEntity::getRouteCode)
                        .orderByAsc(MdmProcedureToolingRequirementEntity::getRouteNodeCode)
                        .orderByAsc(MdmProcedureToolingRequirementEntity::getRequirementSeq));
        for (MdmProcedureToolingRequirementEntity requirement : requirements) {
            MdmProcessRouteWorkingEntity node = byNode.get(requirement.getRouteNodeCode());
            require(node != null && Objects.equals(node.getTenantId(), requirement.getTenantId())
                            && Objects.equals(node.getFactoryCode(), requirement.getFactoryCode())
                            && Objects.equals(node.getRouteCode(), requirement.getRouteCode())
                            && Objects.equals(node.getProcessCode(), requirement.getProcessCode()),
                    "工装要求与真实路线节点身份不一致，请先修正主数据");
        }
        LambdaQueryWrapper<MdmToolingEntity> toolingQuery = new LambdaQueryWrapper<MdmToolingEntity>()
                .eq(MdmToolingEntity::getStatus, true);
        List<String> requestedCodes = query.getToolingCodes() == null ? List.of()
                : query.getToolingCodes().stream().filter(StrUtil::isNotBlank).distinct().toList();
        toolingQuery.in(CollUtil.isNotEmpty(requestedCodes), MdmToolingEntity::getToolingCode, requestedCodes);
        List<MdmToolingEntity> toolings = toolingMapper.selectList(toolingQuery);
        Map<String, List<MdmToolingDeviceCompatibilityEntity>> compatibility = compatibilityMapper.selectList(
                        new LambdaQueryWrapper<MdmToolingDeviceCompatibilityEntity>()
                                .eq(MdmToolingDeviceCompatibilityEntity::getStatus, true)).stream()
                .collect(Collectors.groupingBy(MdmToolingDeviceCompatibilityEntity::getToolingCode));
        Map<String, List<MdmToolingUnavailableWindowEntity>> windows = windowMapper.selectList(
                        new LambdaQueryWrapper<MdmToolingUnavailableWindowEntity>()
                                .eq(MdmToolingUnavailableWindowEntity::getStatus, true)
                                .lt(MdmToolingUnavailableWindowEntity::getStartTime, query.getEndTime())
                                .gt(MdmToolingUnavailableWindowEntity::getEndTime, query.getStartTime())).stream()
                .collect(Collectors.groupingBy(MdmToolingUnavailableWindowEntity::getToolingCode));

        List<MdmToolingCandidateDTO> result = new ArrayList<>();
        for (MdmProcedureToolingRequirementEntity requirement : requirements) {
            Set<String> specified = requirement.getCandidateToolingCodes() == null ? Set.of()
                    : Arrays.stream(requirement.getCandidateToolingCodes()).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
            List<MdmToolingEntity> candidates = toolings.stream()
                    .filter(item -> requirement.getToolingType().equalsIgnoreCase(item.getToolingType()))
                    .filter(item -> specified.isEmpty() || specified.contains(item.getToolingCode())).toList();
            if (candidates.isEmpty()) {
                MdmToolingCandidateDTO missing = baseCandidate(requirement, null);
                missing.setQualified(false);
                missing.setQualificationReason("工序要求没有匹配到启用的候选工装");
                result.add(missing);
                continue;
            }
            for (MdmToolingEntity tooling : candidates) {
                MdmToolingCandidateDTO candidate = baseCandidate(requirement, tooling);
                candidate.setCompatibleDeviceCodes(compatibility.getOrDefault(tooling.getToolingCode(), List.of()).stream()
                        .map(MdmToolingDeviceCompatibilityEntity::getDeviceCode).distinct().toList());
                List<MdmToolingUnavailableWindowEntity> unavailable = new ArrayList<>(
                        windows.getOrDefault(tooling.getToolingCode(), List.of()));
                addDueDateWindows(tooling, query, unavailable);
                candidate.setUnavailableWindows(unavailable);
                String gap = qualificationGap(tooling, query.getStartTime().toLocalDate());
                candidate.setQualified(gap == null);
                candidate.setQualificationReason(gap == null
                        ? (unavailable.isEmpty() ? "状态、寿命、校准和维护条件满足" : "基础条件满足，需避让不可用窗口") : gap);
                result.add(candidate);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MdmToolingUsageResultDTO> consumeLife(MdmToolingUsageRequestDTO request) {
        if (request == null || StrUtil.isBlank(request.getRequestId()) || request.getRequestId().trim().length() > 128
                || StrUtil.isBlank(request.getBusinessType()) || StrUtil.isBlank(request.getBusinessCode())
                || CollUtil.isEmpty(request.getLines())) {
            throw new CheckedException("工装寿命扣减请求号、业务来源、业务单号和扣减明细不能为空");
        }
        String requestId = request.getRequestId().trim();
        Map<String, BigDecimal> usageByCode = new TreeMap<>();
        for (MdmToolingUsageLineDTO line : request.getLines()) {
            if (line == null || StrUtil.isBlank(line.getToolingCode()) || line.getUsage() == null
                    || line.getUsage().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("工装编码不能为空且寿命消耗必须大于0");
            }
            usageByCode.merge(line.getToolingCode().trim(), line.getUsage(), BigDecimal::add);
        }

        List<MdmToolingUsageResultDTO> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : usageByCode.entrySet()) {
            MdmToolingUsageLedgerEntity existing = usageLedgerMapper.selectOne(
                    new LambdaQueryWrapper<MdmToolingUsageLedgerEntity>()
                            .eq(MdmToolingUsageLedgerEntity::getRequestId, requestId)
                            .eq(MdmToolingUsageLedgerEntity::getToolingCode, entry.getKey()));
            if (existing != null) {
                if (existing.getUsage() == null || existing.getUsage().compareTo(entry.getValue()) != 0
                        || !Objects.equals(existing.getBusinessType(), request.getBusinessType())
                        || !Objects.equals(existing.getBusinessCode(), request.getBusinessCode())) {
                    throw new CheckedException("同一寿命扣减请求号的业务或数量不一致，拒绝重复处理");
                }
                result.add(usageResult(entry.getKey(), entry.getValue(), existing.getAfterUsedLife(), true));
                continue;
            }
            MdmToolingEntity tooling = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                    .eq(MdmToolingEntity::getToolingCode, entry.getKey()).last("FOR UPDATE"));
            if (tooling == null || Boolean.FALSE.equals(tooling.getStatus())) {
                throw new CheckedException("工装[" + entry.getKey() + "]不存在或已停用，寿命扣减失败");
            }
            // 首次查询与取得工装行锁之间可能有相同请求并发提交，锁后必须再次确认幂等账本。
            existing = usageLedgerMapper.selectOne(new LambdaQueryWrapper<MdmToolingUsageLedgerEntity>()
                    .eq(MdmToolingUsageLedgerEntity::getRequestId, requestId)
                    .eq(MdmToolingUsageLedgerEntity::getToolingCode, entry.getKey()));
            if (existing != null) {
                if (existing.getUsage() == null || existing.getUsage().compareTo(entry.getValue()) != 0
                        || !Objects.equals(existing.getBusinessType(), request.getBusinessType())
                        || !Objects.equals(existing.getBusinessCode(), request.getBusinessCode())) {
                    throw new CheckedException("同一寿命扣减请求号的业务或数量不一致，拒绝重复处理");
                }
                result.add(usageResult(entry.getKey(), entry.getValue(), existing.getAfterUsedLife(), true));
                continue;
            }
            BigDecimal before = Optional.ofNullable(tooling.getUsedLife()).orElse(BigDecimal.ZERO);
            BigDecimal after = before.add(entry.getValue());
            if (tooling.getDesignLife() != null && after.compareTo(tooling.getDesignLife()) > 0) {
                throw new CheckedException("工装[" + entry.getKey() + "]寿命不足：剩余"
                        + tooling.getDesignLife().subtract(before) + "，本次需扣减" + entry.getValue());
            }
            tooling.setUsedLife(after);
            if (toolingMapper.updateById(tooling) != 1) {
                throw new CheckedException("工装[" + entry.getKey() + "]寿命更新失败");
            }
            MdmToolingUsageLedgerEntity ledger = new MdmToolingUsageLedgerEntity();
            ledger.setRequestId(requestId);
            ledger.setBusinessType(request.getBusinessType().trim());
            ledger.setBusinessCode(request.getBusinessCode().trim());
            ledger.setToolingCode(entry.getKey());
            ledger.setUsage(entry.getValue());
            ledger.setBeforeUsedLife(before);
            ledger.setAfterUsedLife(after);
            ledger.setRemark(request.getRemark());
            usageLedgerMapper.insert(ledger);
            result.add(usageResult(entry.getKey(), entry.getValue(), after, false));
        }
        return result;
    }

    private MdmToolingUsageResultDTO usageResult(String toolingCode, BigDecimal usage,
                                                 BigDecimal usedLife, boolean duplicated) {
        MdmToolingEntity tooling = requireTooling(toolingCode);
        MdmToolingUsageResultDTO result = new MdmToolingUsageResultDTO();
        result.setToolingCode(toolingCode);
        result.setUsage(usage);
        result.setUsedLife(usedLife);
        BigDecimal remaining = tooling.getDesignLife() == null ? null : tooling.getDesignLife().subtract(usedLife);
        result.setRemainingLife(remaining);
        result.setWarning(remaining != null && tooling.getWarningRemainingLife() != null
                && remaining.compareTo(tooling.getWarningRemainingLife()) <= 0);
        result.setDuplicated(duplicated);
        return result;
    }

    private void validateTooling(MdmToolingEntity tooling) {
        if (tooling == null || StrUtil.isBlank(tooling.getToolingCode()) || StrUtil.isBlank(tooling.getToolingName())) {
            throw new CheckedException("工装编码和名称不能为空");
        }
        String type = StrUtil.blankToDefault(tooling.getToolingType(), "").toUpperCase(Locale.ROOT);
        String state = StrUtil.blankToDefault(tooling.getToolingState(), "AVAILABLE").toUpperCase(Locale.ROOT);
        String unit = StrUtil.blankToDefault(tooling.getLifeUnit(), "CYCLE").toUpperCase(Locale.ROOT);
        if (!TOOLING_TYPES.contains(type) || !TOOLING_STATES.contains(state) || !LIFE_UNITS.contains(unit)) {
            throw new CheckedException("工装类型、状态或寿命单位不合法");
        }
        BigDecimal used = Optional.ofNullable(tooling.getUsedLife()).orElse(BigDecimal.ZERO);
        if (used.compareTo(BigDecimal.ZERO) < 0 || tooling.getDesignLife() != null
                && (tooling.getDesignLife().compareTo(BigDecimal.ZERO) <= 0 || used.compareTo(tooling.getDesignLife()) > 0)) {
            throw new CheckedException("工装已用寿命必须大于等于0且不能超过设计寿命");
        }
        if (tooling.getWarningRemainingLife() != null && tooling.getWarningRemainingLife().compareTo(BigDecimal.ZERO) < 0) {
            throw new CheckedException("寿命预警阈值不能小于0");
        }
        if (Boolean.TRUE.equals(tooling.getCalibrationRequired()) && tooling.getCalibrationValidTo() == null) {
            throw new CheckedException("需要校准的工装必须维护校准有效期");
        }
    }

    private void validateCompatibleDevices(List<MdmToolingDeviceCompatibilityEntity> devices) {
        Set<String> seen = new HashSet<>();
        for (MdmToolingDeviceCompatibilityEntity item : devices) {
            if (item == null || StrUtil.isBlank(item.getDeviceCode()) || !seen.add(item.getDeviceCode())) {
                throw new CheckedException("兼容设备不能为空或重复");
            }
            MdmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getDeviceCode, item.getDeviceCode()));
            if (device == null || Boolean.FALSE.equals(device.getStatus()) || !"1".equals(device.getDeviceUsable())) {
                throw new CheckedException("兼容设备[" + item.getDeviceCode() + "]不存在、停用或不可用");
            }
        }
    }

    private void validateWindows(List<MdmToolingUnavailableWindowEntity> windows) {
        Set<String> identities = new HashSet<>();
        for (MdmToolingUnavailableWindowEntity item : windows) {
            if (item == null || item.getStartTime() == null || item.getEndTime() == null
                    || !item.getEndTime().isAfter(item.getStartTime())
                    || !WINDOW_TYPES.contains(StrUtil.blankToDefault(item.getWindowType(), "").toUpperCase(Locale.ROOT))) {
                throw new CheckedException("工装不可用窗口的类型、开始或结束时间不合法");
            }
            if (!identities.add(toolingWindowKey(item))) {
                throw new CheckedException("同一工装不能重复维护完全相同的不可用窗口");
            }
        }
        List<MdmToolingUnavailableWindowEntity> enabled = windows.stream()
                .filter(item -> !Boolean.FALSE.equals(item.getStatus()))
                .sorted(Comparator.comparing(MdmToolingUnavailableWindowEntity::getStartTime)).toList();
        for (int i = 1; i < enabled.size(); i++) {
            if (enabled.get(i).getStartTime().isBefore(enabled.get(i - 1).getEndTime())) {
                throw new CheckedException("同一工装的启用不可用窗口不能重叠");
            }
        }
    }

    private String toolingWindowKey(MdmToolingUnavailableWindowEntity item) {
        return String.join("|", StrUtil.blankToDefault(item.getWindowType(), "").toUpperCase(Locale.ROOT),
                Objects.toString(item.getStartTime(), ""), Objects.toString(item.getEndTime(), ""),
                StrUtil.blankToDefault(item.getReason(), "").trim(),
                String.valueOf(!Boolean.FALSE.equals(item.getStatus())));
    }

    private void validateRequirement(MdmProcedureToolingRequirementEntity item, int defaultSequence,
                                     Set<Integer> sequences, Map<String, MdmToolingEntity> toolings) {
        if (item == null || !TOOLING_TYPES.contains(StrUtil.blankToDefault(item.getToolingType(), "").toUpperCase(Locale.ROOT))) {
            throw new CheckedException("工序工装要求类型不合法");
        }
        int sequence = Optional.ofNullable(item.getRequirementSeq()).orElse(defaultSequence);
        if (sequence <= 0 || !sequences.add(sequence)) {
            throw new CheckedException("工序工装要求顺序必须大于0且不能重复");
        }
        int quantity = Optional.ofNullable(item.getRequiredQuantity()).orElse(1);
        if (quantity <= 0 || quantity > 20) {
            throw new CheckedException("每项工装要求数量必须在1至20之间");
        }
        String mode = StrUtil.blankToDefault(item.getUsageMode(), "FIXED").toUpperCase(Locale.ROOT);
        BigDecimal usage = Optional.ofNullable(item.getUsageValue()).orElse(BigDecimal.ONE);
        if (!USAGE_MODES.contains(mode) || usage.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("工装寿命消耗方式或消耗值不合法");
        }
        // null和空集合都表示不限候选，与页面和availability按类型筛选的合同一致。
        // 只有显式限定候选时才能按其个数预校验；实际可用性仍由排产/执行核验。
        if (item.getCandidateToolingCodes() != null && item.getCandidateToolingCodes().length > 0) {
            Set<String> seen = new HashSet<>();
            for (String code : item.getCandidateToolingCodes()) {
                MdmToolingEntity tooling = toolings.get(code);
                if (StrUtil.isBlank(code) || tooling == null || !seen.add(code)
                        || !item.getToolingType().equalsIgnoreCase(tooling.getToolingType())) {
                    throw new CheckedException("候选工装不存在、重复、停用或类型不匹配：[" + code + "]");
                }
            }
            if (!Boolean.FALSE.equals(item.getMandatory()) && seen.size() < quantity) {
                throw new CheckedException("必选工装的候选数量不能少于要求数量");
            }
        }
    }

    private void validateAvailabilityQuery(MdmToolingAvailabilityQueryDTO query) {
        if (query == null || CollUtil.isEmpty(query.getRouteCodes())
                || (CollUtil.isEmpty(query.getRouteNodeCodes()) && CollUtil.isEmpty(query.getProcessCodes()))
                || query.getStartTime() == null || query.getEndTime() == null
                || !query.getEndTime().isAfter(query.getStartTime())) {
            throw new CheckedException("工装可用性查询的路线、工序和时间范围不能为空");
        }
        if (ChronoUnit.DAYS.between(query.getStartTime(), query.getEndTime()) > MAX_QUERY_DAYS) {
            throw new CheckedException("工装可用性查询范围不能超过" + MAX_QUERY_DAYS + "天");
        }
    }

    private MdmToolingCandidateDTO baseCandidate(MdmProcedureToolingRequirementEntity requirement,
                                                 MdmToolingEntity tooling) {
        MdmToolingCandidateDTO candidate = new MdmToolingCandidateDTO();
        candidate.setRequirementId(requirement.getId());
        candidate.setRouteCode(requirement.getRouteCode());
        candidate.setRouteNodeCode(requirement.getRouteNodeCode());
        candidate.setProcessCode(requirement.getProcessCode());
        candidate.setRequirementSnapshot(requirement);
        if (tooling != null) {
            candidate.setToolingCode(tooling.getToolingCode());
            candidate.setToolingName(tooling.getToolingName());
            candidate.setToolingType(tooling.getToolingType());
            candidate.setToolingState(tooling.getToolingState());
            candidate.setLifeUnit(tooling.getLifeUnit());
            candidate.setRemainingLife(tooling.getDesignLife() == null ? null
                    : tooling.getDesignLife().subtract(Optional.ofNullable(tooling.getUsedLife()).orElse(BigDecimal.ZERO)));
            candidate.setWarningRemainingLife(tooling.getWarningRemainingLife());
            candidate.setCalibrationRequired(tooling.getCalibrationRequired());
            candidate.setCalibrationValidTo(tooling.getCalibrationValidTo());
            candidate.setMaintenanceDueDate(tooling.getMaintenanceDueDate());
        }
        return candidate;
    }

    private String qualificationGap(MdmToolingEntity tooling, LocalDate queryStart) {
        if (!SCHEDULABLE_STATES.contains(tooling.getToolingState())) {
            return "工装当前状态不可排产：" + tooling.getToolingState();
        }
        if (tooling.getDesignLife() != null && tooling.getDesignLife()
                .subtract(Optional.ofNullable(tooling.getUsedLife()).orElse(BigDecimal.ZERO)).compareTo(BigDecimal.ZERO) <= 0) {
            return "工装寿命已经耗尽";
        }
        if (Boolean.TRUE.equals(tooling.getCalibrationRequired())
                && (tooling.getCalibrationValidTo() == null || tooling.getCalibrationValidTo().isBefore(queryStart))) {
            return "工装校准已失效";
        }
        if (tooling.getMaintenanceDueDate() != null && tooling.getMaintenanceDueDate().isBefore(queryStart)) {
            return "工装已超过维护到期日";
        }
        return null;
    }

    private void addDueDateWindows(MdmToolingEntity tooling, MdmToolingAvailabilityQueryDTO query,
                                   List<MdmToolingUnavailableWindowEntity> windows) {
        if (Boolean.TRUE.equals(tooling.getCalibrationRequired()) && tooling.getCalibrationValidTo() != null) {
            LocalDateTime invalidFrom = tooling.getCalibrationValidTo().plusDays(1).atStartOfDay();
            addSyntheticWindow(tooling, query, windows, invalidFrom, "CALIBRATION", "校准有效期届满");
        }
        if (tooling.getMaintenanceDueDate() != null) {
            addSyntheticWindow(tooling, query, windows, tooling.getMaintenanceDueDate().atStartOfDay(),
                    "MAINTENANCE", "维护到期");
        }
    }

    private void addSyntheticWindow(MdmToolingEntity tooling, MdmToolingAvailabilityQueryDTO query,
                                    List<MdmToolingUnavailableWindowEntity> windows,
                                    LocalDateTime start, String type, String reason) {
        if (start.isBefore(query.getEndTime()) && query.getEndTime().isAfter(query.getStartTime())) {
            MdmToolingUnavailableWindowEntity window = new MdmToolingUnavailableWindowEntity();
            window.setToolingCode(tooling.getToolingCode());
            window.setToolingName(tooling.getToolingName());
            window.setWindowType(type);
            window.setStartTime(start.isBefore(query.getStartTime()) ? query.getStartTime() : start);
            window.setEndTime(query.getEndTime());
            window.setReason(reason);
            window.setStatus(true);
            windows.add(window);
        }
    }

    private MdmToolingEntity requireTooling(String toolingCode) {
        if (StrUtil.isBlank(toolingCode)) {
            throw new CheckedException("工装编码不能为空");
        }
        MdmToolingEntity tooling = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                .eq(MdmToolingEntity::getToolingCode, toolingCode));
        if (tooling == null) {
            throw new CheckedException("工装不存在或已删除");
        }
        return tooling;
    }

    private MdmProcessRouteWorkingEntity requireRouteAndProcedure(String routeCode, String routeNodeCode,
                                                                  String processCode, boolean lock) {
        MdmProcessRouteWorkingEntity node = routeNodeResolver.resolve(routeCode, routeNodeCode, processCode, lock);
        MdmProcessRouteEntity route = routeMapper.selectOne(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, node.getTenantId())
                .eq(MdmProcessRouteEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmProcessRouteEntity::getRouteCode, node.getRouteCode()));
        if (route == null || Boolean.FALSE.equals(route.getStatus())) {
            throw new CheckedException("工艺路线不存在或已停用");
        }
        MdmProcedureEntity procedure = procedureMapper.selectOne(new LambdaQueryWrapper<MdmProcedureEntity>()
                .eq(MdmProcedureEntity::getTenantId, node.getTenantId())
                .eq(MdmProcedureEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmProcedureEntity::getProcessCode, node.getProcessCode()));
        if (procedure == null || Boolean.FALSE.equals(procedure.getStatus())) {
            throw new CheckedException("工序不存在或已停用");
        }
        return node;
    }

    private long activeRequirementReferences(String toolingCode) {
        return requirementMapper.selectList(new LambdaQueryWrapper<MdmProcedureToolingRequirementEntity>()
                        .eq(MdmProcedureToolingRequirementEntity::getStatus, true)).stream()
                .filter(item -> contains(item.getCandidateToolingCodes(), toolingCode)).count();
    }

    private long requirementReferenceCount(String toolingCode) {
        return requirementMapper.selectList(null).stream()
                .filter(item -> contains(item.getCandidateToolingCodes(), toolingCode)).count();
    }

    private boolean contains(String[] values, String expected) {
        return values != null && Arrays.asList(values).contains(expected);
    }
}
