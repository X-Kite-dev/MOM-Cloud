package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesCarrierCommand;
import com.kitex.mom.mes.dto.MesCarrierReportCommand;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesCarrierEntity;
import com.kitex.mom.mes.entity.MesCarrierItemEntity;
import com.kitex.mom.mes.entity.MesCarrierOperationEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesCarrierItemMapper;
import com.kitex.mom.mes.mapper.MesCarrierMapper;
import com.kitex.mom.mes.mapper.MesCarrierOperationMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesReportCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可复用生产载具业务协调器。
 *
 * <p>载具成员始终是可追溯的单件生产标识。整载具过站不是一笔数量报工，而是在一个数据库事务中
 * 将冻结成员逐件送入现有移动端报工主链；任一成员的人员、前序、工位、设备、质量、物料或库存门
 * 失败，全部成员和载具回执一起回滚。</p>
 */
@Service
@RequiredArgsConstructor
public class MesCarrierCoordinator {

    private static final Set<String> TYPES = Set.of("TRAY", "RACK", "TOTE", "CART", "OTHER");
    private static final Set<String> OPERATIONS = Set.of(
            "CREATE", "LOAD", "UNLOAD", "LOCK", "UNLOCK", "MAINTAIN", "RESTORE", "RETIRE");

    private final MesActualFactoryAccessService factoryAccess;
    private final MesCarrierMapper carrierMapper;
    private final MesCarrierItemMapper itemMapper;
    private final MesCarrierOperationMapper operationMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesStationPassQueryService stationPassQueryService;
    private final MesTaskReportService taskReportService;
    private final MesExecutionTaskLock taskLock;
    private final MesReportCommandService reportCommands;

    /** 读取载具、活动成员及由所有成员共同决定的唯一下一工序。 */
    public Map<String, Object> preview(String carrierCode) {
        String factory = factoryAccess.require("read");
        MesCarrierEntity carrier = findCarrier(factory, normalizeCarrierCode(carrierCode), false);
        require(carrier != null, "载具不存在或不在当前工厂");
        List<MesCarrierItemEntity> items = activeItems(factory, carrier.getId(), false);
        Map<String, Object> candidate = items.isEmpty() || "RETIRED".equals(carrier.getStatus())
                || "MAINTENANCE".equals(carrier.getStatus())
                ? null : commonCandidate(carrier.getTaskCode(), identifiers(items), false, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("carrier", carrier);
        result.put("items", items);
        result.put("candidate", candidate);
        // readyArrangeCode是最近一次成功操作后的快照。下一道派工可能稍后才开工，
        // 因此实时共同候选才是放行依据，不能让旧快照永久卡住已重新开工的路线。
        result.put("canReport", "LOCKED".equals(carrier.getStatus()) && candidate != null);
        result.put("boundary", "载具报工在线原子展开为逐件报工；任一成员失败整载具回滚，不支持离线拆分重放");
        return result;
    }

    /** 创建、装载、卸载、锁定以及生命周期操作。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesCarrierCommand command) {
        String factory = factoryAccess.require("write");
        MomUser actor = actor();
        normalize(command);
        String fingerprint = hash(JSONUtil.toJsonStr(List.of(command, actor.getId())));
        lockRequest(factory, command.getRequestId());
        MesCarrierOperationEntity replay = operationByRequest(factory, command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), fingerprint), "请求号已被其他载具内容或人员使用");
            return result(findCarrier(factory, replay.getCarrierCode(), false), replay, true);
        }

        MesCarrierEntity carrier;
        String beforeStatus;
        int beforeVersion;
        List<MesCarrierItemEntity> affected = new ArrayList<>();
        if ("CREATE".equals(command.getOperation())) {
            carrierMapper.lockBusinessKey(scope(factory, "CODE", command.getCarrierCode()));
            require(findCarrier(factory, command.getCarrierCode(), false) == null, "载具编码已存在");
            carrier = new MesCarrierEntity().setCarrierCode(command.getCarrierCode())
                    .setCarrierType(command.getCarrierType()).setCapacity(command.getCapacity())
                    .setStatus("EMPTY").setCurrentQuantity(0).setVersionNo(1)
                    .setLastReason(command.getReason()).setFactoryCode(factory);
            require(carrierMapper.insert(carrier) == 1, "载具创建失败");
            beforeStatus = "NONE";
            beforeVersion = 0;
        } else {
            carrier = findCarrier(factory, command.getCarrierCode(), true);
            require(carrier != null, "载具不存在或不在当前工厂");
            require(command.getExpectedVersion() != null
                    && Objects.equals(command.getExpectedVersion(), carrier.getVersionNo()),
                    "载具状态已被其他人员修改，请刷新后重试");
            beforeStatus = carrier.getStatus();
            beforeVersion = carrier.getVersionNo();
            switch (command.getOperation()) {
                case "LOAD" -> affected.addAll(load(factory, carrier, command));
                case "UNLOAD" -> affected.addAll(unload(factory, carrier, command));
                case "LOCK" -> lock(carrier);
                case "UNLOCK" -> unlock(carrier);
                case "MAINTAIN" -> maintain(carrier);
                case "RESTORE" -> restore(carrier);
                case "RETIRE" -> retire(carrier);
                default -> throw new CheckedException("不支持的载具操作");
            }
            carrier.setVersionNo(beforeVersion + 1).setLastReason(command.getReason());
            require(carrierMapper.updateById(carrier) == 1, "载具状态更新失败");
        }
        MesCarrierOperationEntity receipt = receipt(carrier, command.getRequestId(), fingerprint,
                command.getOperation(), beforeStatus, beforeVersion, actor, command.getReason(),
                affected.isEmpty() ? activeItems(factory, carrier.getId(), false) : affected);
        receipt.setResultSnapshot(JSONUtil.toJsonStr(Map.of("carrierCode", carrier.getCarrierCode(),
                "status", carrier.getStatus(), "versionNo", carrier.getVersionNo(),
                "currentQuantity", carrier.getCurrentQuantity())));
        require(operationMapper.insert(receipt) == 1, "载具操作回执保存失败");
        return result(carrier, receipt, false);
    }

    /** 将锁定载具的成员逐件送入标准移动端报工事务。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> report(MesCarrierReportCommand command) {
        String factory = factoryAccess.require("write");
        MomUser actor = actor();
        normalize(command);
        String fingerprint = hash(JSONUtil.toJsonStr(List.of(command, actor.getId())));
        lockRequest(factory, command.getRequestId());
        MesCarrierOperationEntity replay = operationByRequest(factory, command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), fingerprint), "请求号已被其他载具报工内容或人员使用");
            return result(findCarrier(factory, replay.getCarrierCode(), false), replay, true);
        }

        // 外层载具事务也必须先锁真实工单，不能等逐件调用报工后再补锁。
        MesCarrierEntity observed = findCarrier(factory, command.getCarrierCode(), false);
        require(observed != null && "LOCKED".equals(observed.getStatus())
                && Objects.equals(command.getExpectedVersion(), observed.getVersionNo()), "载具状态已变化，请原号重核");
        List<MesCarrierItemEntity> observedItems = activeItems(factory, observed.getId(), false);
        require(!observedItems.isEmpty(), "锁定载具没有活动成员");
        // 子请求锁也必须先于工单/载具行锁，避免直接逐件重试与整载具展开反向等待。
        reportCommands.lockRequests(observedItems.stream().map(item -> childRequest(command, item)).toList());
        MesTaskArrangeEntity ownership = taskLock.arrange(command.getArrangeCode());
        require(observed != null && Objects.equals(observed.getTaskCode(), ownership.getTaskCode()),
                "载具与所选派工不属于同一真实工单");
        MesCarrierEntity carrier = findCarrier(factory, command.getCarrierCode(), true);
        require(carrier != null, "载具不存在或不在当前工厂");
        taskLock.assertTaskCode(ownership.getTaskCode(), carrier.getTaskCode());
        require("LOCKED".equals(carrier.getStatus()), "整载具报工前必须锁定成员，当前状态：" + carrier.getStatus());
        require(Objects.equals(command.getExpectedVersion(), carrier.getVersionNo()),
                "载具状态已变化，请重新预览成员和下一工序");
        List<MesCarrierItemEntity> items = activeItems(factory, carrier.getId(), true);
        require(Objects.equals(observed.getId(), carrier.getId())
                && identifiers(observedItems).equals(identifiers(items)), "载具成员已变化，请原号重核");
        require(!items.isEmpty() && items.size() == carrier.getCurrentQuantity(), "载具成员账与主档数量不一致，禁止报工");
        Map<String, Object> candidate = commonCandidate(carrier.getTaskCode(), identifiers(items), true, true);
        require(Objects.equals(command.getArrangeCode(), candidate.get("arrangeCode")),
                "载具共同下一工序已变化，请刷新后重试");

        int beforeVersion = carrier.getVersionNo();
        MesCarrierOperationEntity receipt = receipt(carrier, command.getRequestId(), fingerprint,
                "REPORT_EXPAND", carrier.getStatus(), beforeVersion, actor, command.getReason(), items);
        receipt.setId(IdWorker.getId());

        List<Map<String, Object>> reportResults = new ArrayList<>();
        for (MesCarrierItemEntity item : items) {
            String childRequest = childRequest(command, item);
            MesTaskReportVO report = new MesTaskReportVO();
            report.setRequestId(childRequest);
            report.setArrangeCode(command.getArrangeCode());
            report.setTransferCode(item.getIdentifier());
            report.setPassNum(BigDecimal.ONE);
            report.setWorksiteCode(command.getWorksiteCode());
            report.setDeviceCode(command.getDeviceCode());
            report.setManualOverrideReason(command.getManualOverrideReason());
            report.setCarrierOperationId(receipt.getId());
            report.setCarrierCode(carrier.getCarrierCode());
            taskReportService.reportMobile(report);
            MesTaskReportEntity saved = reportMapper.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getFactoryCode, factory)
                    .eq(MesTaskReportEntity::getRequestId, childRequest));
            require(saved != null && Objects.equals(saved.getCarrierOperationId(), receipt.getId())
                            && Objects.equals(saved.getTransferCode(), item.getIdentifier()),
                    "载具成员报工完成后未找到一致的逐件凭证，事务已回滚");
            item.setLastReportId(saved.getId()).setLastArrangeCode(saved.getArrangeCode())
                    .setLastProduceTaskCode(saved.getProduceTaskCode())
                    .setLastRouteCode(Objects.toString(candidate.get("routeCode"), null))
                    .setLastRouteName(Objects.toString(candidate.get("routeName"), null))
                    .setLastReportTime(saved.getReportTime())
                    .setReportCount(Objects.requireNonNullElse(item.getReportCount(), 0) + 1);
            itemMapper.updateById(item);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("identifier", item.getIdentifier());
            one.put("reportId", saved.getId());
            one.put("requestId", childRequest);
            reportResults.add(one);
        }

        Map<String, Object> next = commonCandidate(carrier.getTaskCode(), identifiers(items), false, false);
        applyReady(carrier, next);
        carrier.setVersionNo(beforeVersion + 1).setLastReportTime(LocalDateTime.now())
                .setLastReason(command.getReason());
        require(carrierMapper.updateById(carrier) == 1, "载具报工后状态更新失败");
        receipt.setAfterStatus(carrier.getStatus()).setAfterVersion(carrier.getVersionNo())
                .setResultSnapshot(JSONUtil.toJsonStr(Map.of("carrierCode", carrier.getCarrierCode(),
                        "versionNo", carrier.getVersionNo(), "reports", reportResults,
                        "nextArrangeCode", Objects.toString(carrier.getReadyArrangeCode(), ""))));
        require(operationMapper.insert(receipt) == 1, "载具报工结果回执保存失败");
        return result(carrier, receipt, false);
    }

    /** 派生号保持既有算法；预取锁和真正展开必须使用同一原号。 */
    private String childRequest(MesCarrierReportCommand command, MesCarrierItemEntity item) {
        return "CAR-" + hash(command.getRequestId() + "|" + item.getIdentifier()).substring(0, 60);
    }

    public List<MesCarrierItemEntity> items(Long carrierId) {
        String factory = factoryAccess.require("read");
        MesCarrierEntity carrier = carrierMapper.selectOne(new LambdaQueryWrapper<MesCarrierEntity>()
                .eq(MesCarrierEntity::getFactoryCode, factory).eq(MesCarrierEntity::getId, carrierId));
        require(carrier != null, "载具不存在或不在当前工厂");
        return itemMapper.selectList(new LambdaQueryWrapper<MesCarrierItemEntity>()
                .eq(MesCarrierItemEntity::getFactoryCode, factory)
                .eq(MesCarrierItemEntity::getCarrierId, carrierId)
                .orderByDesc(MesCarrierItemEntity::getLoadedTime).orderByDesc(MesCarrierItemEntity::getId));
    }

    public List<MesCarrierOperationEntity> operations(Long carrierId) {
        String factory = factoryAccess.require("read");
        require(carrierMapper.selectCount(new LambdaQueryWrapper<MesCarrierEntity>()
                .eq(MesCarrierEntity::getFactoryCode, factory).eq(MesCarrierEntity::getId, carrierId)) == 1,
                "载具不存在或不在当前工厂");
        return operationMapper.selectList(new LambdaQueryWrapper<MesCarrierOperationEntity>()
                .eq(MesCarrierOperationEntity::getFactoryCode, factory)
                .eq(MesCarrierOperationEntity::getCarrierId, carrierId)
                .orderByDesc(MesCarrierOperationEntity::getOperatedTime)
                .orderByDesc(MesCarrierOperationEntity::getId));
    }

    private List<MesCarrierItemEntity> load(String factory, MesCarrierEntity carrier, MesCarrierCommand command) {
        require(Set.of("EMPTY", "LOADING").contains(carrier.getStatus()), "只有空闲或装载中的载具可以增加成员");
        require(StrUtil.isNotBlank(command.getTaskCode()), "装载必须指定生产工单，用于消除同码跨工单歧义");
        if (StrUtil.isNotBlank(carrier.getTaskCode())) {
            require(Objects.equals(carrier.getTaskCode(), command.getTaskCode()), "同一载具不能混装不同生产工单");
        }
        List<String> requested = normalizeIdentifiers(command.getIdentifiers(), true);
        List<MesCarrierItemEntity> existing = activeItems(factory, carrier.getId(), true);
        int total = existing.size() + requested.size();
        require(total <= carrier.getCapacity(), "装载后成员数超过载具容量" + carrier.getCapacity());
        for (String identifier : requested) {
            require(itemMapper.selectCount(new LambdaQueryWrapper<MesCarrierItemEntity>()
                            .eq(MesCarrierItemEntity::getFactoryCode, factory)
                            .eq(MesCarrierItemEntity::getIdentifier, identifier)
                            .eq(MesCarrierItemEntity::getStatus, "LOADED")) == 0,
                    "生产标识已装在其他载具中：" + identifier);
        }
        List<String> allIdentifiers = new ArrayList<>(identifiers(existing));
        allIdentifiers.addAll(requested);
        Map<String, Object> candidate = commonCandidate(command.getTaskCode(), allIdentifiers, true, false);
        List<MesCarrierItemEntity> inserted = new ArrayList<>();
        for (String identifier : requested) {
            MesCarrierItemEntity item = new MesCarrierItemEntity().setCarrierId(carrier.getId())
                    .setCarrierCode(carrier.getCarrierCode()).setIdentifier(identifier)
                    .setTaskCode(command.getTaskCode())
                    .setMatCode(Objects.toString(candidate.get("matCode"), null))
                    .setMatName(Objects.toString(candidate.get("matName"), null))
                    .setStatus("LOADED").setLoadRequestId(command.getRequestId())
                    .setLoadedTime(LocalDateTime.now()).setReportCount(0).setFactoryCode(factory);
            require(itemMapper.insert(item) == 1, "载具成员装载失败：" + identifier);
            inserted.add(item);
        }
        carrier.setStatus("LOADING").setCurrentQuantity(total).setTaskCode(command.getTaskCode())
                .setMatCode(Objects.toString(candidate.get("matCode"), null))
                .setMatName(Objects.toString(candidate.get("matName"), null));
        applyReady(carrier, candidate);
        return inserted;
    }

    private List<MesCarrierItemEntity> unload(String factory, MesCarrierEntity carrier, MesCarrierCommand command) {
        require("LOADING".equals(carrier.getStatus()), "卸载成员前必须先解锁载具");
        List<MesCarrierItemEntity> active = activeItems(factory, carrier.getId(), true);
        Set<String> targets = new LinkedHashSet<>(normalizeIdentifiers(command.getIdentifiers(), false));
        List<MesCarrierItemEntity> affected = targets.isEmpty() ? active : active.stream()
                .filter(item -> targets.contains(item.getIdentifier())).toList();
        require(!affected.isEmpty(), "未找到需要卸载的活动成员");
        if (!targets.isEmpty()) {
            require(affected.size() == targets.size(), "部分待卸载标识不在当前载具中");
        }
        for (MesCarrierItemEntity item : affected) {
            item.setStatus("UNLOADED").setUnloadRequestId(command.getRequestId())
                    .setUnloadedTime(LocalDateTime.now());
            itemMapper.updateById(item);
        }
        List<MesCarrierItemEntity> remaining = active.stream()
                .filter(item -> affected.stream().noneMatch(removed -> Objects.equals(removed.getId(), item.getId())))
                .toList();
        carrier.setCurrentQuantity(remaining.size());
        if (remaining.isEmpty()) {
            clearContent(carrier);
            carrier.setStatus("EMPTY");
        } else {
            Map<String, Object> candidate = commonCandidate(carrier.getTaskCode(), identifiers(remaining), true, false);
            applyReady(carrier, candidate);
        }
        return affected;
    }

    private void lock(MesCarrierEntity carrier) {
        require("LOADING".equals(carrier.getStatus()) && carrier.getCurrentQuantity() != null
                && carrier.getCurrentQuantity() > 0, "只有已装载成员的载具可以锁定");
        Map<String, Object> candidate = commonCandidate(carrier.getTaskCode(),
                identifiers(activeItems(carrier.getFactoryCode(), carrier.getId(), true)), true, false);
        applyReady(carrier, candidate);
        MomUser user = actor();
        carrier.setStatus("LOCKED").setLockedBy(user.getUsername()).setLockedTime(LocalDateTime.now());
    }

    private void unlock(MesCarrierEntity carrier) {
        require("LOCKED".equals(carrier.getStatus()), "只有已锁定载具可以解锁");
        carrier.setStatus("LOADING").setLockedBy(null).setLockedTime(null);
    }

    private void maintain(MesCarrierEntity carrier) {
        require("EMPTY".equals(carrier.getStatus()) && Objects.equals(carrier.getCurrentQuantity(), 0),
                "只有空载具可以进入维护状态");
        carrier.setStatus("MAINTENANCE");
    }

    private void restore(MesCarrierEntity carrier) {
        require("MAINTENANCE".equals(carrier.getStatus()), "只有维护中的载具可以恢复使用");
        carrier.setStatus("EMPTY");
    }

    private void retire(MesCarrierEntity carrier) {
        require(Set.of("EMPTY", "MAINTENANCE").contains(carrier.getStatus())
                && Objects.equals(carrier.getCurrentQuantity(), 0), "只有空载具或维护载具可以退役");
        carrier.setStatus("RETIRED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> commonCandidate(String taskCode, List<String> identifiers, boolean required,
                                                boolean requireAssigned) {
        require(StrUtil.isNotBlank(taskCode), "载具缺少成员生产工单");
        require(!identifiers.isEmpty() && identifiers.size() <= 200, "载具成员数量须在1至200之间");
        Set<String> common = null;
        Map<String, Map<String, Object>> sample = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            Map<String, Object> preview = requireAssigned
                    ? stationPassQueryService.previewCarrierReportMember(identifier)
                    : stationPassQueryService.previewCarrierMember(identifier);
            Object rawRows = preview.get("allProcessList");
            List<Map<String, Object>> rows = rawRows instanceof List<?> list ? list.stream()
                    .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item)
                    .filter(item -> Boolean.TRUE.equals(item.get("canReport")))
                    .filter(item -> Objects.equals("ONE_CODE", item.get("reportMode")))
                    .filter(item -> Objects.equals(taskCode, item.get("taskCode"))).toList() : List.of();
            Map<String, Map<String, Object>> byArrange = rows.stream().collect(Collectors.toMap(
                    item -> Objects.toString(item.get("arrangeCode"), ""), item -> item,
                    (left, right) -> left, LinkedHashMap::new));
            if (byArrange.isEmpty()) {
                if (!required) return null;
                throw new CheckedException("生产标识当前没有可执行工序：" + identifier);
            }
            if (common == null) {
                common = new LinkedHashSet<>(byArrange.keySet());
                sample.putAll(byArrange);
            } else {
                common.retainAll(byArrange.keySet());
            }
        }
        if (common == null || common.isEmpty()) {
            if (!required) return null;
            throw new CheckedException("载具成员没有共同的唯一下一工序，禁止整载具报工");
        }
        require(common.size() == 1, "载具成员存在多个共同候选工序，请先完善工艺前后序或拆分载具");
        return sample.get(common.iterator().next());
    }

    private void applyReady(MesCarrierEntity carrier, Map<String, Object> candidate) {
        carrier.setReadyArrangeCode(candidate == null ? null : Objects.toString(candidate.get("arrangeCode"), null));
        carrier.setReadyRouteCode(candidate == null ? null : Objects.toString(candidate.get("routeCode"), null));
        carrier.setReadyRouteName(candidate == null ? null : Objects.toString(candidate.get("routeName"), null));
    }

    private void clearContent(MesCarrierEntity carrier) {
        carrier.setTaskCode(null).setMatCode(null).setMatName(null).setReadyArrangeCode(null)
                .setReadyRouteCode(null).setReadyRouteName(null).setLockedBy(null).setLockedTime(null);
    }

    private MesCarrierOperationEntity receipt(MesCarrierEntity carrier, String requestId, String fingerprint,
                                               String operation, String beforeStatus, int beforeVersion,
                                               MomUser actor, String reason, List<MesCarrierItemEntity> items) {
        return new MesCarrierOperationEntity().setCarrierId(carrier.getId()).setCarrierCode(carrier.getCarrierCode())
                .setRequestId(requestId).setRequestFingerprint(fingerprint).setOperationType(operation)
                .setBeforeStatus(beforeStatus).setAfterStatus(carrier.getStatus())
                .setBeforeVersion(beforeVersion).setAfterVersion(carrier.getVersionNo())
                .setItemCount(items.size()).setItemSnapshot(JSONUtil.toJsonStr(items.stream()
                        .sorted(Comparator.comparing(MesCarrierItemEntity::getIdentifier))
                        .map(item -> Map.of("id", item.getId(), "identifier", item.getIdentifier(),
                                "taskCode", item.getTaskCode(), "status", item.getStatus()))
                        .toList()))
                .setResultSnapshot("{}").setOperatorId(actor.getId()).setOperatorCode(actor.getUsername())
                .setOperatorName(StrUtil.blankToDefault(actor.getName(), actor.getUsername()))
                .setReason(reason).setOperatedTime(LocalDateTime.now())
                .setFactoryCode(carrier.getFactoryCode());
    }

    private MesCarrierEntity findCarrier(String factory, String carrierCode, boolean lock) {
        return carrierMapper.selectOne(new LambdaQueryWrapper<MesCarrierEntity>()
                .eq(MesCarrierEntity::getFactoryCode, factory)
                .eq(MesCarrierEntity::getCarrierCode, carrierCode)
                .last(lock ? "FOR UPDATE" : "LIMIT 1"));
    }

    private List<MesCarrierItemEntity> activeItems(String factory, Long carrierId, boolean lock) {
        return itemMapper.selectList(new LambdaQueryWrapper<MesCarrierItemEntity>()
                .eq(MesCarrierItemEntity::getFactoryCode, factory)
                .eq(MesCarrierItemEntity::getCarrierId, carrierId)
                .eq(MesCarrierItemEntity::getStatus, "LOADED")
                .orderByAsc(MesCarrierItemEntity::getIdentifier)
                .last(lock ? "FOR UPDATE" : ""));
    }

    private MesCarrierOperationEntity operationByRequest(String factory, String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<MesCarrierOperationEntity>()
                .eq(MesCarrierOperationEntity::getFactoryCode, factory)
                .eq(MesCarrierOperationEntity::getRequestId, requestId));
    }

    private void lockRequest(String factory, String requestId) {
        carrierMapper.lockBusinessKey(scope(factory, "REQUEST", requestId));
    }

    private String scope(String factory, String kind, String value) {
        return "MES-CARRIER:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + kind + ":" + value;
    }

    private List<String> identifiers(List<MesCarrierItemEntity> items) {
        return items.stream().map(MesCarrierItemEntity::getIdentifier).sorted().toList();
    }

    private List<String> normalizeIdentifiers(List<String> identifiers, boolean required) {
        if (identifiers == null || identifiers.isEmpty()) {
            if (required) throw new CheckedException("至少提供一个生产标识");
            return List.of();
        }
        List<String> result = identifiers.stream().map(value -> StrUtil.blankToDefault(value, "").trim())
                .filter(StrUtil::isNotBlank).distinct().sorted().toList();
        require(result.size() == identifiers.size(), "生产标识不能为空或重复");
        require(result.size() <= 200 && result.stream().allMatch(value -> value.length() <= 200),
                "单次最多处理200个且每个不超过200字的生产标识");
        return result;
    }

    private void normalize(MesCarrierCommand command) {
        require(command != null, "载具操作命令不能为空");
        command.setRequestId(normalizeRequest(command.getRequestId()));
        command.setCarrierCode(normalizeCarrierCode(command.getCarrierCode()));
        command.setOperation(Objects.toString(command.getOperation(), "").trim().toUpperCase());
        require(OPERATIONS.contains(command.getOperation()), "不支持的载具操作");
        command.setReason(normalizeReason(command.getReason()));
        if ("CREATE".equals(command.getOperation())) {
            command.setCarrierType(Objects.toString(command.getCarrierType(), "").trim().toUpperCase());
            require(TYPES.contains(command.getCarrierType()), "载具类型须为TRAY/RACK/TOTE/CART/OTHER之一");
            require(command.getCapacity() != null && command.getCapacity() >= 1 && command.getCapacity() <= 200,
                    "载具容量须在1至200之间");
            require(command.getExpectedVersion() == null, "创建载具不能携带历史版本");
        } else {
            require(command.getExpectedVersion() != null && command.getExpectedVersion() > 0,
                    "操作前必须读取并提交载具状态版本");
        }
        command.setTaskCode(StrUtil.blankToDefault(command.getTaskCode(), "").trim());
        if ("LOAD".equals(command.getOperation())) {
            require(!command.getTaskCode().isEmpty() && command.getTaskCode().length() <= 100,
                    "装载必须提供100字以内生产工单号");
            command.setIdentifiers(normalizeIdentifiers(command.getIdentifiers(), true));
        } else if ("UNLOAD".equals(command.getOperation())) {
            command.setIdentifiers(normalizeIdentifiers(command.getIdentifiers(), false));
        }
    }

    private void normalize(MesCarrierReportCommand command) {
        require(command != null, "载具报工命令不能为空");
        command.setRequestId(normalizeRequest(command.getRequestId()));
        command.setCarrierCode(normalizeCarrierCode(command.getCarrierCode()));
        command.setArrangeCode(StrUtil.blankToDefault(command.getArrangeCode(), "").trim());
        command.setWorksiteCode(StrUtil.blankToDefault(command.getWorksiteCode(), "").trim());
        command.setDeviceCode(StrUtil.trim(command.getDeviceCode()));
        command.setManualOverrideReason(StrUtil.trim(command.getManualOverrideReason()));
        command.setReason(normalizeReason(command.getReason()));
        require(!command.getArrangeCode().isEmpty() && command.getArrangeCode().length() <= 100,
                "载具报工必须提供100字以内派工单号");
        require(!command.getWorksiteCode().isEmpty() && command.getWorksiteCode().length() <= 100,
                "载具报工必须提供100字以内实际工位");
        require(command.getExpectedVersion() != null && command.getExpectedVersion() > 0,
                "载具报工必须提交预览时的状态版本");
    }

    private String normalizeRequest(String value) {
        String request = StrUtil.blankToDefault(value, "").trim();
        require(request.matches("[A-Za-z0-9_-]{8,64}"), "须提供8至64位字母、数字、横线或下划线请求号");
        return request;
    }

    private String normalizeCarrierCode(String value) {
        String code = StrUtil.blankToDefault(value, "").trim();
        require(code.matches("[A-Za-z0-9._:/-]{1,100}"), "载具编码须为1至100位字母、数字或._:/-组合");
        return code;
    }

    private String normalizeReason(String value) {
        String reason = StrUtil.blankToDefault(value, "").trim();
        require(!reason.isEmpty() && reason.length() <= 500, "须填写1至500字真实操作依据");
        return reason;
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
        return user;
    }

    private Map<String, Object> result(MesCarrierEntity carrier, MesCarrierOperationEntity operation,
                                       boolean replayed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("carrier", carrier);
        result.put("operation", operation);
        result.put("replayed", replayed);
        return result;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256算法", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
