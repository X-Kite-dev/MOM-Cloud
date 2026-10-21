package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.tpm.dto.TpmRepairNoticeCommand;
import com.kitex.mom.tpm.entity.TpmRepairNoticeEntity;
import com.kitex.mom.tpm.entity.TpmRepairNoticeOperationEntity;
import com.kitex.mom.tpm.entity.TpmRepairRemindDetailsEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.RepairPersonTypeEnum;
import com.kitex.mom.tpm.enums.TimeUnitEnum;
import com.kitex.mom.tpm.mapper.TpmRepairNoticeMapper;
import com.kitex.mom.tpm.mapper.TpmRepairNoticeOperationMapper;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报修提醒来源冻结、持久入箱、结果不明人工核对。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TpmRepairNoticeService {
    private static final String CODE = "TPMDELAY";
    private final TpmRepairNoticeMapper notices;
    private final TpmRepairNoticeOperationMapper operations;
    private final TpmRepairTaskMapper tasks;
    private final TpmRepairNoticeWorker worker;
    private final RemoteUserService users;
    private final RemoteMessagePushService messages;
    private final RemoteMdmFactoryAccessService factories;
    private final ObjectMapper json;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    /**
     * 在保存任务前解析全部规则及收件人；不完整配置直接阻断报修提醒模式。
     */
    public List<Draft> prepare(TpmRepairTaskEntity task, List<TpmRepairRemindDetailsEntity> details) {
        require(task != null && task.getSubmitTime() != null && StrUtil.isNotBlank(task.getTaskCode()) && details != null && !details.isEmpty(), "维修提醒配置或任务不完整");
        require(details.stream().allMatch(d -> d != null && StrUtil.isNotBlank(d.getRemindCode())), "维修提醒层级缺少规则编号");
        var grouped = details.stream().collect(Collectors.groupingBy(TpmRepairRemindDetailsEntity::getRemindCode));
        Set<Long> handlers = new TreeSet<>();
        Map<Integer, Set<Long>> escalation = new TreeMap<>();
        Map<Integer, Set<String>> escalationRules = new TreeMap<>();
        for (var entry : grouped.entrySet()) {
            var stages = entry.getValue().stream().sorted(Comparator.comparing(TpmRepairRemindDetailsEntity::getUserLevel,
                    Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            require(!stages.isEmpty() && RepairPersonTypeEnum.HANDLE.getCode().equals(stages.get(0).getPersonType()), "维修提醒规则缺少首级处理人");
            handlers.addAll(parseIds(stages.get(0).getUserIds()));
            int elapsed = stages.size() > 1 ? minutes(stages.get(0)) : 0;
            for (int i = 1; i < stages.size(); i++) {
                var stage = stages.get(i);
                escalation.computeIfAbsent(elapsed, ignored -> new TreeSet<>()).addAll(parseIds(stage.getUserIds()));
                escalationRules.computeIfAbsent(elapsed, ignored -> new TreeSet<>()).add(entry.getKey());
                if (i < stages.size() - 1) elapsed = Math.addExact(elapsed, minutes(stage));
            }
        }
        require(!handlers.isEmpty() && escalation.size() <= 100, "维修提醒处理人为空或升级层级过多");
        Set<Long> all = new TreeSet<>(handlers);
        escalation.values().forEach(all::addAll);
        var response = users.getUserByIdInner(new ArrayList<>(all));
        require(response != null && response.isOk() && response.getData() != null, "维修提醒收件人不可读取");
        Map<Long, SysUserEntity> resolved = response.getData().stream().filter(Objects::nonNull).collect(Collectors.toMap(SysUserEntity::getUserId,
                user -> user, (left, right) -> left));
        require(resolved.keySet().containsAll(all), "维修提醒含已删除或不可访问的收件人");
        require(all.stream().allMatch(id -> StrUtil.isNotBlank(resolved.get(id).getUsername()) && StrUtil.isNotBlank(resolved.get(id).getName())),
                "维修提醒收件人实名信息不完整");
        task.setRepairBy(handlers.stream().map(id -> resolved.get(id).getUsername()).collect(Collectors.joining(",")));
        task.setRepairByName(handlers.stream().map(id -> resolved.get(id).getName()).collect(Collectors.joining(",")));
        var drafts = new ArrayList<Draft>();
        drafts.add(draft("IMMEDIATE", task.getSubmitTime(), new TreeSet<>(grouped.keySet()), handlers, resolved, "维修任务【" + task.getTaskCode() + "】已报修，请及时处理。"));
        for (var entry : escalation.entrySet())
            drafts.add(draft("ESCALATE:" + entry.getKey(), task.getSubmitTime().plusMinutes(entry.getKey()), escalationRules.get(entry.getKey()), entry.getValue(), resolved,
                    "维修任务【" + task.getTaskCode() + "】报修后已超过" + entry.getKey() + "分钟仍未完成，请升级处理。"));
        return drafts;
    }

    private Draft draft(String key, LocalDateTime due, Set<String> rules, Set<Long> recipients, Map<Long, SysUserEntity> resolved, String message) {
        var ids = List.copyOf(recipients);
        String wx = ids.stream().map(id -> resolved.get(id).getWxDingUserid()).filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(","));
        require(wx.length() <= 2000 && message.length() <= 1000, "维修提醒消息或外部收件人超出容量");
        return new Draft(key, due, List.copyOf(rules), ids, wx, message);
    }

    private Set<Long> parseIds(String raw) {
        require(StrUtil.isNotBlank(raw), "维修提醒收件人为空");
        Set<Long> result = new TreeSet<>();
        try {
            for (String part : raw.split(",")) {
                long id = Long.parseLong(part.trim());
                require(id > 0, "维修提醒收件人ID无效");
                result.add(id);
            }
        } catch (NumberFormatException ex) {
            throw new CheckedException("维修提醒收件人ID无效");
        }
        require(!result.isEmpty(), "维修提醒收件人为空");
        return result;
    }

    private int minutes(TpmRepairRemindDetailsEntity stage) {
        Integer limit = stage.getHandleLimit();
        String unit = stage.getHandleUnit();
        require(limit != null && limit > 0 && limit <= 365, "维修提醒超时阈值无效");
        int value = TimeUnitEnum.MINUTE.getCode().equals(unit) ? limit : TimeUnitEnum.HOUR.getCode().equals(unit) ? limit * 60 :
                TimeUnitEnum.DAY.getCode().equals(unit) ? limit * 1440 : 0;
        require(value > 0 && value <= 525600, "维修提醒超时单位或阈值无效");
        return value;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public List<TpmRepairNoticeEntity> enqueue(TpmRepairTaskEntity task, List<Draft> drafts) {
        require(task.getId() != null && Boolean.TRUE.equals(task.getRemindSign()) && drafts != null && !drafts.isEmpty(), "维修提醒入箱参数不完整");
        var inserted = new ArrayList<TpmRepairNoticeEntity>();
        for (var d : drafts) {
            String ids, rules;
            try {
                ids = json.writeValueAsString(d.userIds().stream().map(String::valueOf).toList());
                rules = json.writeValueAsString(d.ruleCodes());
            } catch (Exception ex) {
                throw new CheckedException("维修提醒规则或收件人快照无法保存");
            }
            var n = new TpmRepairNoticeEntity().setTaskId(task.getId()).setNoticeKey(d.key()).setDueTime(d.dueTime())
                    .setRuleCodesJson(rules).setUserIdsJson(ids).setWxUserIds(d.wxUserIds()).setMessage(d.message()).setStatus("PENDING")
                    .setAttemptCount(0).setFactoryCode(task.getFactoryCode());
            n.setTenantId(task.getTenantId());
            require(notices.insert(n) == 1, "维修提醒持久入箱失败");
            worker.operation(n, "AUTO:" + n.getId() + ":ENQUEUE", "ENQUEUE", null, "PENDING", "报修同事务冻结提醒规则、收件人和到期时刻");
            inserted.add(n);
        }
        return inserted;
    }

    /**
     * 维修经异岗验证完成后，同事务取消尚未占用的未来提醒；结果不明仍留人工核对。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void cancelPendingForTask(TpmRepairTaskEntity task) {
        var rows = notices.selectList(new LambdaQueryWrapper<TpmRepairNoticeEntity>().eq(TpmRepairNoticeEntity::getTenantId, task.getTenantId())
                .eq(TpmRepairNoticeEntity::getFactoryCode, task.getFactoryCode()).eq(TpmRepairNoticeEntity::getTaskId, task.getId())
                .eq(TpmRepairNoticeEntity::getStatus, "PENDING"));
        for (var candidate : rows) {
            var n = notices.lockOne(candidate.getId(), task.getTenantId(), task.getFactoryCode());
            if (n == null || !"PENDING".equals(n.getStatus())) continue;
            n.setStatus("CANCELLED").setLastError("维修任务已独立验证完成，未到期提醒取消");
            require(notices.updateById(n) == 1, "已完成维修提醒取消失败");
            worker.operation(n, "AUTO:" + n.getId() + ":VERIFIED_CANCEL", "CANCEL", "PENDING", "CANCELLED", n.getLastError());
        }
    }

    public int dispatchDue(int limit) {
        LocalDateTime now = LocalDateTime.now(), cutoff = now.minusMinutes(10);
        var stale = notices.selectList(new LambdaQueryWrapper<TpmRepairNoticeEntity>().eq(TpmRepairNoticeEntity::getStatus, "IN_FLIGHT")
                .le(TpmRepairNoticeEntity::getClaimedTime, cutoff).orderByAsc(TpmRepairNoticeEntity::getClaimedTime).last("LIMIT 100"));
        for (var n : stale)
            withTenant(n, () -> worker.markStale(n.getId(), n.getTenantId(), n.getFactoryCode(), cutoff));
        var due = notices.selectList(new LambdaQueryWrapper<TpmRepairNoticeEntity>().eq(TpmRepairNoticeEntity::getStatus, "PENDING")
                .le(TpmRepairNoticeEntity::getDueTime, now).orderByAsc(TpmRepairNoticeEntity::getDueTime).orderByAsc(TpmRepairNoticeEntity::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
        int delivered = 0;
        for (var n : due) if (dispatchOne(n.getId(), n.getTenantId(), n.getFactoryCode())) delivered++;
        return delivered;
    }

    public boolean dispatchOne(Long id, Long tenantId, String factoryCode) {
        return withTenantValue(tenantId, () -> {
            TpmRepairNoticeEntity n;
            try {
                n = worker.claim(id, tenantId, factoryCode);
            } catch (RuntimeException ex) {
                log.error("维修提醒占用失败，noticeId={}", id, ex);
                return false;
            }
            if (n == null) return false;
            boolean delivered = false;
            String detail = "";
            try {
                List<String> rawIds = json.readValue(n.getUserIdsJson(), new TypeReference<List<String>>() {
                });
                List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
                require(!ids.isEmpty(), "维修提醒收件人快照为空");
                var dto = new MessageDTO();
                dto.setCode(CODE);
                dto.setMessageType(MessageTypeEnum.TEXT.getType());
                dto.setUserList(ids);
                dto.setUserIds(n.getWxUserIds());
                dto.setMessage(n.getMessage());
                var response = messages.pushPersonMsg(dto);
                delivered = response != null && response.isOk() && Boolean.TRUE.equals(response.getData());
                if (!delivered)
                    detail = response == null ? "消息服务无回执" : Objects.toString(response.getMessage(), "消息服务未确认完整送达");
            } catch (Exception ex) {
                detail = Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
            }
            try {
                worker.finish(id, tenantId, factoryCode, n.getAttemptCount(), delivered, detail);
            } catch (RuntimeException ex) {
                log.error("维修提醒外发后本地结果保存失败，noticeId={}，须核对IN_FLIGHT", id, ex);
                return false;
            }
            return delivered;
        });
    }

    private void withTenant(TpmRepairNoticeEntity n, Runnable work) {
        withTenantValue(n.getTenantId(), () -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.error("维修提醒过期占用对账失败，noticeId={}", n.getId(), ex);
            }
            return true;
        });
    }

    private <T> T withTenantValue(Long tenantId, java.util.function.Supplier<T> work) {
        return TenantBroker.applyAs(tenantId, ignored -> work.get());
    }

    @Transactional(readOnly = true)
    public Object ledger(Long taskId) {
        String factory = access("read");
        Long tenant = TenantContextHolder.getTenantId();
        var t = task(taskId, tenant, factory, false);
        var rows = notices.selectList(new LambdaQueryWrapper<TpmRepairNoticeEntity>().eq(TpmRepairNoticeEntity::getTaskId, t.getId())
                .eq(TpmRepairNoticeEntity::getTenantId, tenant).eq(TpmRepairNoticeEntity::getFactoryCode, factory).orderByAsc(TpmRepairNoticeEntity::getDueTime));
        var ids = rows.stream().map(TpmRepairNoticeEntity::getId).toList();
        var history = ids.isEmpty() ? List.<TpmRepairNoticeOperationEntity>of() : operations.selectList(new LambdaQueryWrapper<TpmRepairNoticeOperationEntity>()
                .in(TpmRepairNoticeOperationEntity::getNoticeId, ids).eq(TpmRepairNoticeOperationEntity::getTenantId, tenant)
                .eq(TpmRepairNoticeOperationEntity::getFactoryCode, factory).orderByAsc(TpmRepairNoticeOperationEntity::getId));
        return Map.of("task", t, "notices", rows, "operations", history);
    }

    @Transactional(rollbackFor = Exception.class)
    public Object resolve(TpmRepairNoticeCommand c) {
        String factory = access("write");
        Long tenant = TenantContextHolder.getTenantId();
        var user = SecurityUtils.getUser();
        require(c != null && c.getNoticeId() != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}")
                && Set.of("RETRY", "ACK_DELIVERED", "CANCEL").contains(c.getAction()) && StrUtil.isNotBlank(c.getReason())
                && c.getReason().trim().length() <= 1000 && StrUtil.isNotBlank(c.getExpectedStatus()), "维修提醒核对须填写动作、稳定请求号、原状态和具体依据");
        String reason = c.getReason().trim();
        String fingerprint = TpmRepairNoticeWorker.hash(c.getNoticeId() + "|" + c.getAction() + "|" + c.getExpectedStatus() + "|" + reason + "|" + user.getId());
        var replay = operations.selectOne(new LambdaQueryWrapper<TpmRepairNoticeOperationEntity>().eq(TpmRepairNoticeOperationEntity::getTenantId, tenant)
                .eq(TpmRepairNoticeOperationEntity::getFactoryCode, factory).eq(TpmRepairNoticeOperationEntity::getRequestId, c.getRequestId()));
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), c.getNoticeId()) && Objects.equals(replay.getRequestFingerprint(), fingerprint), "请求号已用于其他内容或操作员");
            return Map.of("notice", notices.selectById(c.getNoticeId()), "operation", replay, "replayed", true);
        }
        var candidate = notices.selectById(c.getNoticeId());
        require(candidate != null && Objects.equals(candidate.getTenantId(), tenant) && Objects.equals(candidate.getFactoryCode(), factory), "当前工厂提醒不存在");
        var currentTask = task(candidate.getTaskId(), tenant, factory, true);
        var n = notices.lockOne(c.getNoticeId(), tenant, factory);
        require(n != null && Objects.equals(n.getStatus(), c.getExpectedStatus()), "提醒状态已变化，请刷新后核对");
        String before = n.getStatus();
        if ("RETRY".equals(c.getAction())) {
            require("UNCERTAIN".equals(before), "仅结果不明提醒可人工补发");
            require("0".equals(currentTask.getState()), "维修任务已结束，不应补发超时提醒");
            n.setStatus("PENDING").setLastError("人工核对后允许补发：" + TpmRepairNoticeWorker.trim(reason));
        } else if ("ACK_DELIVERED".equals(c.getAction())) {
            require("UNCERTAIN".equals(before), "仅结果不明提醒可人工确认已送达");
            n.setStatus("DELIVERED").setDeliveredTime(LocalDateTime.now()).setLastError("人工核实已送达：" + TpmRepairNoticeWorker.trim(reason));
        } else {
            require(Set.of("PENDING", "UNCERTAIN").contains(before), "只能取消待发或结果不明提醒");
            n.setStatus("CANCELLED").setLastError("人工取消：" + TpmRepairNoticeWorker.trim(reason));
        }
        require(notices.updateById(n) == 1, "维修提醒人工处置保存失败");
        var op = new TpmRepairNoticeOperationEntity().setNoticeId(n.getId()).setRequestId(c.getRequestId())
                .setRequestFingerprint(fingerprint).setAction(c.getAction()).setBeforeStatus(before).setAfterStatus(n.getStatus())
                .setAttemptNo(n.getAttemptCount()).setReason(reason).setOperatorId(user.getId()).setOperatorCode(user.getUsername())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(factory);
        op.setTenantId(tenant);
        require(operations.insert(op) == 1, "维修提醒人工操作履历保存失败");
        return Map.of("notice", n, "operation", op, "replayed", false);
    }

    private TpmRepairTaskEntity task(Long id, Long tenant, String factory, boolean lock) {
        var q = new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .eq(TpmRepairTaskEntity::getId, id).eq(TpmRepairTaskEntity::getTenantId, tenant).eq(TpmRepairTaskEntity::getFactoryCode, factory);
        if (lock) q.last("FOR UPDATE");
        var t = tasks.selectOne(q);
        require(t != null, "当前工厂维修任务不存在");
        return t;
    }

    private String access(String permission) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory), "须实名选择实际工厂");
        var response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission(permission));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()), "当前工厂权限不足或不可核验");
        return factory;
    }

    public record Draft(String key, LocalDateTime dueTime, List<String> ruleCodes, List<Long> userIds, String wxUserIds,
                        String message) {
    }
}
