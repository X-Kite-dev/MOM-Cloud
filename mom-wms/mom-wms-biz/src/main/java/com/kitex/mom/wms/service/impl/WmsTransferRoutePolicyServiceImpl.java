package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsTransferRoutePolicyDTO;
import com.kitex.mom.wms.entity.WmsTransferRoutePolicyEntity;
import com.kitex.mom.wms.mapper.WmsTransferRoutePolicyMapper;
import com.kitex.mom.wms.service.WmsTransferRoutePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WmsTransferRoutePolicyServiceImpl
        extends ServiceImpl<WmsTransferRoutePolicyMapper, WmsTransferRoutePolicyEntity>
        implements WmsTransferRoutePolicyService {

    private static final Set<String> TRANSPORT_MODES = Set.of("INTERNAL_VEHICLE", "AGV", "THIRD_PARTY", "MANUAL");
    private static final Set<String> URGENCY_LEVELS = Set.of("NORMAL", "URGENT", "EMERGENCY");
    private static final Set<String> CALENDAR_MODES = Set.of("CONTINUOUS", "MDM_WORKDAY");
    private static final int MAX_LEAD_MINUTES = 366 * 24 * 60;
    private static final int MAX_ROUTE_COUNT = 3000;
    private static final int MAX_CALENDAR_DAYS = 3660;

    private final RemoteWarehouseService remoteWarehouseService;
    private final RemoteCalendarService remoteCalendarService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferRoutePolicyEntity savePolicy(WmsTransferRoutePolicyEntity policy) {
        require(policy != null, "调拨路线策略不能为空");
        normalize(policy);
        validate(policy);
        String factory = StrUtil.blankToDefault(FactoryContextHolder.getFactoryCode(), policy.getFactoryCode());
        require(StrUtil.isNotBlank(factory), "当前工厂不能为空");
        require(StrUtil.isBlank(FactoryContextHolder.getFactoryCode()) || StrUtil.isBlank(policy.getFactoryCode())
                || Objects.equals(factory, policy.getFactoryCode()), "路线策略工厂超出当前数据范围");
        policy.setFactoryCode(factory);
        if (policy.getId() != null) {
            WmsTransferRoutePolicyEntity existing = getPolicy(policy.getId());
            require(Objects.equals(existing.getFactoryCode(), factory), "路线策略不属于当前工厂");
        }
        Map<String, MdmWarehouseEntity> warehouses = warehouses(factory);
        MdmWarehouseEntity source = warehouses.get(policy.getSourceWarehouseCode());
        MdmWarehouseEntity target = warehouses.get(policy.getTargetWarehouseCode());
        require(source != null && target != null, "源仓或目标仓不存在、未启用或不属于当前工厂");
        policy.setSourceWarehouseName(source.getWarehouseName());
        policy.setTargetWarehouseName(target.getWarehouseName());
        require(!Objects.equals(policy.getSourceWarehouseCode(), policy.getTargetWarehouseCode()), "源仓和目标仓不能相同");
        baseMapper.lockPolicyDimension("WMS_TRANSFER_ROUTE_POLICY:" + factory + ":"
                + policy.getSourceWarehouseCode() + ":" + policy.getTargetWarehouseCode() + ":"
                + policy.getTransportMode() + ":" + policy.getUrgencyLevel());
        require(count(new LambdaQueryWrapper<WmsTransferRoutePolicyEntity>()
                        .eq(WmsTransferRoutePolicyEntity::getPolicyCode, policy.getPolicyCode())
                        .ne(policy.getId() != null, WmsTransferRoutePolicyEntity::getId, policy.getId())) == 0,
                "路线策略编码已存在");
        List<WmsTransferRoutePolicyEntity> sameDimension = list(new LambdaQueryWrapper<WmsTransferRoutePolicyEntity>()
                .eq(WmsTransferRoutePolicyEntity::getFactoryCode, factory)
                .eq(WmsTransferRoutePolicyEntity::getSourceWarehouseCode, policy.getSourceWarehouseCode())
                .eq(WmsTransferRoutePolicyEntity::getTargetWarehouseCode, policy.getTargetWarehouseCode())
                .eq(WmsTransferRoutePolicyEntity::getTransportMode, policy.getTransportMode())
                .eq(WmsTransferRoutePolicyEntity::getUrgencyLevel, policy.getUrgencyLevel())
                .eq(WmsTransferRoutePolicyEntity::getStatus, true)
                .ne(policy.getId() != null, WmsTransferRoutePolicyEntity::getId, policy.getId()));
        if (Boolean.TRUE.equals(policy.getStatus())) {
            require(sameDimension.stream().noneMatch(existing -> overlaps(policy, existing)),
                    "相同路线、运输方式和紧急等级存在有效期重叠的启用策略");
        }
        require(saveOrUpdate(policy), "路线策略保存失败");
        return getById(policy.getId());
    }

    @Override
    public WmsTransferRoutePolicyEntity getPolicy(Long id) {
        require(id != null, "路线策略ID不能为空");
        WmsTransferRoutePolicyEntity policy = getById(id);
        require(policy != null, "路线策略不存在");
        String currentFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, policy.getFactoryCode()),
                "路线策略超出当前工厂数据范围");
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removePolicies(List<Long> ids) {
        require(ids != null && !ids.isEmpty(), "请选择要删除的路线策略");
        List<WmsTransferRoutePolicyEntity> policies = listByIds(ids);
        require(policies.size() == new HashSet<>(ids).size(), "部分路线策略不存在或超出数据范围");
        String currentFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(currentFactory) || policies.stream()
                        .allMatch(item -> Objects.equals(currentFactory, item.getFactoryCode())),
                "部分路线策略超出当前工厂数据范围");
        require(policies.stream().noneMatch(item -> Boolean.TRUE.equals(item.getStatus())),
                "启用中的路线策略不得删除，请先停用以保留历史审计语义");
        return removeBatchByIds(ids);
    }

    @Override
    public WmsTransferRoutePolicyDTO.RoutePlan preview(WmsTransferRoutePolicyDTO.PreviewQuery query) {
        require(query != null, "路线试算参数不能为空");
        WmsTransferRoutePolicyDTO.RouteRequirement route = new WmsTransferRoutePolicyDTO.RouteRequirement();
        route.setSourceWarehouseCode(query.getSourceWarehouseCode());
        route.setTargetWarehouseCode(query.getTargetWarehouseCode());
        route.setUrgencyLevel(query.getUrgencyLevel());
        WmsTransferRoutePolicyDTO.BatchQuery batch = new WmsTransferRoutePolicyDTO.BatchQuery();
        batch.setFactoryCode(query.getFactoryCode());
        batch.setDepartureTime(query.getDepartureTime());
        batch.setRoutes(List.of(route));
        WmsTransferRoutePolicyDTO.RoutePlan result = resolveBatch(batch).get(routeKey(
                query.getSourceWarehouseCode(), query.getTargetWarehouseCode(), normalizeUrgency(query.getUrgencyLevel())));
        require(result != null, "当前日期没有匹配的启用路线策略");
        return result;
    }

    @Override
    public Map<String, WmsTransferRoutePolicyDTO.RoutePlan> resolveBatch(WmsTransferRoutePolicyDTO.BatchQuery query) {
        require(query != null, "路线解析参数不能为空");
        require(query.getRoutes() != null && !query.getRoutes().isEmpty(), "路线解析请求不能为空");
        require(query.getRoutes().size() <= MAX_ROUTE_COUNT, "单次最多解析" + MAX_ROUTE_COUNT + "条路线");
        String currentFactory = FactoryContextHolder.getFactoryCode();
        String factory = StrUtil.blankToDefault(query.getFactoryCode(), currentFactory);
        require(StrUtil.isNotBlank(factory), "路线解析必须指定工厂");
        factory = factory.trim();
        require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, factory), "路线解析工厂超出当前数据范围");
        LocalDateTime departure = Optional.ofNullable(query.getDepartureTime()).orElse(LocalDateTime.now())
                .truncatedTo(ChronoUnit.SECONDS);
        List<WmsTransferRoutePolicyDTO.RouteRequirement> requested = query.getRoutes().stream().peek(route -> {
            require(route != null && StrUtil.isNotBlank(route.getSourceWarehouseCode())
                    && StrUtil.isNotBlank(route.getTargetWarehouseCode()), "路线解析缺少源仓或目标仓");
            route.setSourceWarehouseCode(route.getSourceWarehouseCode().trim());
            route.setTargetWarehouseCode(route.getTargetWarehouseCode().trim());
            route.setUrgencyLevel(normalizeUrgency(route.getUrgencyLevel()));
            require(!Objects.equals(route.getSourceWarehouseCode(), route.getTargetWarehouseCode()), "路线源仓和目标仓不能相同");
        }).toList();
        LocalDate effectiveDate = departure.toLocalDate();
        List<WmsTransferRoutePolicyEntity> active = list(new LambdaQueryWrapper<WmsTransferRoutePolicyEntity>()
                .eq(WmsTransferRoutePolicyEntity::getFactoryCode, factory)
                .eq(WmsTransferRoutePolicyEntity::getStatus, true)
                .le(WmsTransferRoutePolicyEntity::getEffectiveStartDate, effectiveDate)
                .and(wrapper -> wrapper.isNull(WmsTransferRoutePolicyEntity::getEffectiveEndDate)
                        .or().ge(WmsTransferRoutePolicyEntity::getEffectiveEndDate, effectiveDate)));

        Map<String, List<WmsTransferRoutePolicyEntity>> candidatesByPair = active.stream().collect(Collectors.groupingBy(
                item -> pairKey(item.getSourceWarehouseCode(), item.getTargetWarehouseCode())));
        Map<String, WmsTransferRoutePolicyEntity> selected = new LinkedHashMap<>();
        for (WmsTransferRoutePolicyDTO.RouteRequirement route : requested) {
            List<WmsTransferRoutePolicyEntity> pairCandidates = candidatesByPair.getOrDefault(
                    pairKey(route.getSourceWarehouseCode(), route.getTargetWarehouseCode()), List.of());
            List<WmsTransferRoutePolicyEntity> exact = pairCandidates.stream()
                    .filter(item -> Objects.equals(item.getUrgencyLevel(), route.getUrgencyLevel())).toList();
            List<WmsTransferRoutePolicyEntity> applicable = exact.isEmpty() && !"NORMAL".equals(route.getUrgencyLevel())
                    ? pairCandidates.stream().filter(item -> "NORMAL".equals(item.getUrgencyLevel())).toList() : exact;
            applicable.stream().min(Comparator
                            .comparing(WmsTransferRoutePolicyEntity::getRoutePriority)
                            .thenComparing(WmsTransferRoutePolicyEntity::getLeadTimeMinutes)
                            .thenComparing(WmsTransferRoutePolicyEntity::getPolicyCode))
                    .ifPresent(policy -> selected.put(routeKey(route.getSourceWarehouseCode(),
                            route.getTargetWarehouseCode(), route.getUrgencyLevel()), policy));
        }
        if (selected.isEmpty()) return Map.of();
        boolean workdayNeeded = selected.values().stream().anyMatch(item -> "MDM_WORKDAY".equals(item.getCalendarMode()));
        Map<LocalDate, Boolean> workdayOverrides = workdayNeeded
                ? loadWorkdayOverrides(departure.toLocalDate(), departure.toLocalDate().plusDays(MAX_CALENDAR_DAYS))
                : Map.of();
        Map<String, WmsTransferRoutePolicyDTO.RoutePlan> result = new LinkedHashMap<>();
        selected.forEach((key, policy) -> result.put(key, toPlan(key, policy, departure, workdayOverrides)));
        return result;
    }

    @Override
    public String routeKey(String sourceWarehouseCode, String targetWarehouseCode, String urgencyLevel) {
        return StrUtil.trim(sourceWarehouseCode) + "\u001f" + StrUtil.trim(targetWarehouseCode)
                + "\u001f" + normalizeUrgency(urgencyLevel);
    }

    private WmsTransferRoutePolicyDTO.RoutePlan toPlan(String key, WmsTransferRoutePolicyEntity policy,
                                                       LocalDateTime departure, Map<LocalDate, Boolean> workdayOverrides) {
        LocalDateTime arrival = "CONTINUOUS".equals(policy.getCalendarMode())
                ? departure.plusMinutes(policy.getLeadTimeMinutes())
                : addWorkingMinutes(departure, policy, workdayOverrides);
        return new WmsTransferRoutePolicyDTO.RoutePlan().setRouteKey(key).setPolicyId(policy.getId())
                .setPolicyCode(policy.getPolicyCode()).setPolicyName(policy.getPolicyName())
                .setSourceWarehouseCode(policy.getSourceWarehouseCode())
                .setTargetWarehouseCode(policy.getTargetWarehouseCode())
                .setTransportMode(policy.getTransportMode()).setPolicyUrgencyLevel(policy.getUrgencyLevel())
                .setUrgencyLevel(key.substring(key.lastIndexOf('\u001f') + 1))
                .setLeadTimeMinutes(policy.getLeadTimeMinutes())
                .setTransferLeadHours((policy.getLeadTimeMinutes() + 59) / 60)
                .setCalendarMode(policy.getCalendarMode()).setDepartureTime(departure)
                .setEstimatedArrivalTime(arrival).setPolicyFingerprint(policyFingerprint(policy));
    }

    private LocalDateTime addWorkingMinutes(LocalDateTime departure, WmsTransferRoutePolicyEntity policy,
                                            Map<LocalDate, Boolean> workdayOverrides) {
        long remaining = policy.getLeadTimeMinutes();
        LocalDateTime cursor = departure;
        for (int dayCount = 0; dayCount <= MAX_CALENDAR_DAYS; dayCount++) {
            LocalDate date = cursor.toLocalDate();
            if (!isWorkday(date, workdayOverrides)) {
                cursor = date.plusDays(1).atTime(policy.getWorkdayStartTime());
                continue;
            }
            LocalDateTime start = date.atTime(policy.getWorkdayStartTime());
            LocalDateTime end = date.atTime(policy.getWorkdayEndTime());
            if (cursor.isBefore(start)) cursor = start;
            if (!cursor.isBefore(end)) {
                cursor = date.plusDays(1).atTime(policy.getWorkdayStartTime());
                continue;
            }
            long available = Duration.between(cursor, end).toMinutes();
            if (remaining <= available) return cursor.plusMinutes(remaining);
            remaining -= available;
            cursor = date.plusDays(1).atTime(policy.getWorkdayStartTime());
        }
        throw new CheckedException("路线策略工作日历在未来" + MAX_CALENDAR_DAYS + "天内无法得到预计到达时间");
    }

    private Map<LocalDate, Boolean> loadWorkdayOverrides(LocalDate start, LocalDate end) {
        try {
            R<List<MdmCalendarEntity>> response = remoteCalendarService.listByRange(start, end);
            require(response != null && response.isOk() && response.getData() != null,
                    "无法读取MDM工作日历，工作日路线已故障关闭");
            return response.getData().stream().filter(item -> item.getTime() != null)
                    .collect(Collectors.toMap(MdmCalendarEntity::getTime,
                            item -> "0".equals(item.getStatus()), (left, right) -> right));
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("读取MDM工作日历失败，工作日路线已故障关闭", ex);
        }
    }

    private boolean isWorkday(LocalDate date, Map<LocalDate, Boolean> overrides) {
        Boolean override = overrides.get(date);
        if (override != null) return override;
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private Map<String, MdmWarehouseEntity> warehouses(String factory) {
        MdmWarehouseEntity filter = new MdmWarehouseEntity();
        filter.setFactoryCode(factory);
        filter.setStatus(Boolean.TRUE);
        R<List<MdmWarehouseEntity>> response = remoteWarehouseService.getList(filter);
        require(response != null && response.isOk() && response.getData() != null,
                "无法读取工厂仓库，路线策略保存已故障关闭");
        return response.getData().stream().filter(item -> Boolean.TRUE.equals(item.getStatus())
                        && Objects.equals(factory, item.getFactoryCode()) && StrUtil.isNotBlank(item.getWarehouseCode()))
                .collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, Function.identity(), (left, right) -> left));
    }

    private void normalize(WmsTransferRoutePolicyEntity policy) {
        policy.setPolicyCode(upper(policy.getPolicyCode()));
        policy.setPolicyName(StrUtil.trim(policy.getPolicyName()));
        policy.setSourceWarehouseCode(StrUtil.trim(policy.getSourceWarehouseCode()));
        policy.setTargetWarehouseCode(StrUtil.trim(policy.getTargetWarehouseCode()));
        policy.setTransportMode(upper(policy.getTransportMode()));
        policy.setUrgencyLevel(normalizeUrgency(policy.getUrgencyLevel()));
        policy.setCalendarMode(upper(policy.getCalendarMode()));
        policy.setRemark(StrUtil.trim(policy.getRemark()));
        if (policy.getStatus() == null) policy.setStatus(Boolean.TRUE);
        if (policy.getRoutePriority() == null) policy.setRoutePriority(100);
        if (policy.getEffectiveStartDate() == null) policy.setEffectiveStartDate(LocalDate.now());
        if ("CONTINUOUS".equals(policy.getCalendarMode())) {
            policy.setWorkdayStartTime(null);
            policy.setWorkdayEndTime(null);
        }
    }

    private void validate(WmsTransferRoutePolicyEntity policy) {
        require(StrUtil.isNotBlank(policy.getPolicyCode()) && policy.getPolicyCode().length() <= 64,
                "策略编码不能为空且最多64位");
        require(StrUtil.isNotBlank(policy.getPolicyName()) && policy.getPolicyName().length() <= 100,
                "策略名称不能为空且最多100位");
        require(StrUtil.isNotBlank(policy.getSourceWarehouseCode()) && StrUtil.isNotBlank(policy.getTargetWarehouseCode()),
                "源仓和目标仓不能为空");
        require(TRANSPORT_MODES.contains(policy.getTransportMode()), "运输方式无效");
        require(URGENCY_LEVELS.contains(policy.getUrgencyLevel()), "紧急等级无效");
        require(CALENDAR_MODES.contains(policy.getCalendarMode()), "日历方式无效");
        require(policy.getLeadTimeMinutes() != null && policy.getLeadTimeMinutes() > 0
                && policy.getLeadTimeMinutes() <= MAX_LEAD_MINUTES, "提前期必须为1分钟至366天");
        require(policy.getRoutePriority() != null && policy.getRoutePriority() >= 1 && policy.getRoutePriority() <= 999,
                "路线优先级必须为1至999");
        require(policy.getEffectiveStartDate() != null && (policy.getEffectiveEndDate() == null
                || !policy.getEffectiveEndDate().isBefore(policy.getEffectiveStartDate())), "策略有效期无效");
        if ("MDM_WORKDAY".equals(policy.getCalendarMode())) {
            require(policy.getWorkdayStartTime() != null && policy.getWorkdayEndTime() != null
                            && policy.getWorkdayEndTime().isAfter(policy.getWorkdayStartTime()),
                    "工作日路线必须配置有效的每日作业起止时间");
        }
        require(policy.getRemark() == null || policy.getRemark().length() <= 500, "策略说明最多500位");
    }

    private boolean overlaps(WmsTransferRoutePolicyEntity left, WmsTransferRoutePolicyEntity right) {
        LocalDate leftEnd = Optional.ofNullable(left.getEffectiveEndDate()).orElse(LocalDate.MAX);
        LocalDate rightEnd = Optional.ofNullable(right.getEffectiveEndDate()).orElse(LocalDate.MAX);
        return !leftEnd.isBefore(right.getEffectiveStartDate()) && !rightEnd.isBefore(left.getEffectiveStartDate());
    }

    private String policyFingerprint(WmsTransferRoutePolicyEntity policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", policy.getId());
        snapshot.put("code", policy.getPolicyCode());
        snapshot.put("source", policy.getSourceWarehouseCode());
        snapshot.put("target", policy.getTargetWarehouseCode());
        snapshot.put("mode", policy.getTransportMode());
        snapshot.put("urgency", policy.getUrgencyLevel());
        snapshot.put("leadMinutes", policy.getLeadTimeMinutes());
        snapshot.put("calendar", policy.getCalendarMode());
        snapshot.put("workdayStart", policy.getWorkdayStartTime());
        snapshot.put("workdayEnd", policy.getWorkdayEndTime());
        snapshot.put("priority", policy.getRoutePriority());
        snapshot.put("effectiveStart", policy.getEffectiveStartDate());
        snapshot.put("effectiveEnd", policy.getEffectiveEndDate());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("路线策略摘要生成失败", ex);
        }
    }

    private String pairKey(String source, String target) {
        return source + "\u001f" + target;
    }

    private String normalizeUrgency(String urgency) {
        String normalized = StrUtil.isBlank(urgency) ? "NORMAL" : upper(urgency);
        require(URGENCY_LEVELS.contains(normalized), "紧急等级无效");
        return normalized;
    }

    private String upper(String value) {
        return StrUtil.isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
