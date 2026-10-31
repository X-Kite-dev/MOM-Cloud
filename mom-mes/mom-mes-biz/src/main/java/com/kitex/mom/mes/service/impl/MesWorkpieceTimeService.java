package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWorkpieceTimeDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeIntervalEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeOperationEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeSessionEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesWorkpieceTimeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 原号回执、会话状态及实际时间区间在同一事务落库，不写报工或派工产量。
 */
@Service
@RequiredArgsConstructor
public class MesWorkpieceTimeService {

    private static final String VERSION = "MES_WORKPIECE_TIME_V1";
    private static final String BASIS = "OPERATOR_OBSERVED";
    private static final String COMMAND_KIND = "WORKPIECE_TIME";
    private final MesWorkpieceTimeMapper rows;
    private final MesTaskArrangeMapper arranges;
    private final MesActualFactoryAccessService access;
    private final MesExecutionTaskLock taskLocks;
    private final MesTerminalExecutionGuard terminal;
    private final MesExecutionCommandCancellationService cancellations;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Transactional(rollbackFor = Exception.class)
    public MesWorkpieceTimeDTO.Receipt execute(MesWorkpieceTimeDTO.Command command) {
        String factory = access.require("write");
        ready();
        validate(command);
        Long tenant = TenantContextHolder.getTenantId();
        String hash = hash(tenant + ":" + factory + ":" + SecurityUtils.getUser().getId() + ":" + write(command));
        rows.lockRequest(tenant + ":" + factory + ":" + command.getRequestId());
        var original = rows.operation(tenant, factory, command.getRequestId());
        if (original != null) {
            require(Objects.equals(original.getActorId(), SecurityUtils.getUser().getId())
                    && Objects.equals(original.getRequestHash(), hash), "工件计时原号已用于其他操作者或内容");
            return readReceipt(original);
        }
        cancellations.assertActive(COMMAND_KIND, command.getRequestId(), hash);

        var arrange = lockedArrangement(id(command.getArrangeId()));
        assertWorker(arrange);
        terminal.assertUnoccupied(arrange);
        if (Set.of("START", "RESUME").contains(command.getAction())) {
            require("PRODUCT".equals(arrange.getStatus()), "必须先完成派工真实开工，才能开始或恢复工件计时");
        } else {
            require(Set.of("PRODUCT", "PAUSE").contains(arrange.getStatus()), "当前派工已终结或未开工，不能补造工件时间");
        }
        if ("BATCH".equals(command.getIdentifierType())) {
            require(arrange.getBatchNo() == null || arrange.getBatchNo().isBlank()
                    || arrange.getBatchNo().equals(command.getIdentifier()), "所读批次与派工已知生产批次冲突");
        } else {
            require(rows.knownSnConflicts(tenant, factory, arrange.getTaskCode(), command.getIdentifier()) == 0,
                    "单件标识已有其他工单的明确生产归属，不能作为本工单计时来源");
            require(rows.knownSessionConflicts(tenant, factory, arrange.getTaskCode(), arrange.getId(), command.getIdentifier()) == 0,
                    "单件标识已有其他工单归属或其他派工正在计时，须先核清原会话");
        }
        var session = rows.session(tenant, factory, arrange.getId(), command.getIdentifierType(), command.getIdentifier());
        LocalDateTime at = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if ("START".equals(command.getAction())) {
            require(session == null, "此派工标识已有原计时会话，请按原会话状态操作，禁止重复开始");
            session = new MesWorkpieceTimeSessionEntity().setFactoryCode(factory).setArrangeId(arrange.getId())
                    .setArrangeCode(arrange.getCode()).setTaskCode(arrange.getTaskCode()).setProduceTaskCode(arrange.getProduceTaskCode())
                    .setIdentifier(command.getIdentifier()).setIdentifierType(command.getIdentifierType())
                    .setWorkerCode(SecurityUtils.getUser().getUsername()).setDeviceCode(arrange.getPlannedDeviceCode())
                    .setStatus("RUNNING").setVersionNo(1L).setCaptureBasis(BASIS);
            session.setId(IdWorker.getId());
            session.setTenantId(tenant);
            require(rows.insertSession(session) == 1, "工件原计时会话保存失败");
            open(session, "RUN", at, null);
        } else {
            require(session != null, "工件原计时会话不存在，禁止补造开始时间");
            assertSession(arrange, session);
            require(Objects.equals(session.getWorkerCode(), SecurityUtils.getUser().getUsername()), "只能操作本人原工件计时会话");
            require(Objects.equals(command.getExpectedVersion(), session.getVersionNo()), "工件计时版本已变化，请恢复原号或刷新原会话");
            switch (command.getAction()) {
                case "PAUSE" -> {
                    require("RUNNING".equals(session.getStatus()), "只有运行中的工件可暂停");
                    transition(session, "PAUSED", "OPERATOR", "PAUSE", at, command.getReasonCode());
                }
                case "RESUME" -> {
                    require("PAUSED".equals(session.getStatus()) && "OPERATOR".equals(session.getPauseSource()),
                            "只能手工恢复本人主动暂停，随派工暂停的工件须由派工复工恢复");
                    transition(session, "RUNNING", null, "RUN", at, null);
                }
                case "FINISH" -> {
                    require(Set.of("RUNNING", "PAUSED").contains(session.getStatus()), "原工件计时已结束");
                    transition(session, "FINISHED", null, null, at, null);
                }
                default -> throw new CheckedException("未知工件计时动作");
            }
        }
        return record(session, command.getRequestId(), hash, command.getAction(), at);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesWorkpieceTimeDTO.View view(Long arrangeId) {
        return view(arrangeId, 1, 1);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesWorkpieceTimeDTO.View view(Long arrangeId, int sessionPage, int intervalPage) {
        String factory = access.require("read");
        ready();
        require(sessionPage > 0 && intervalPage > 0, "工件计时页码必须大于零");
        var arrange = arrangement(arrangeId, factory, false);
        assertWorker(arrange);
        long sessionTotal = rows.sessionTotal(arrange.getTenantId(), factory, arrangeId);
        long intervalTotal = rows.intervalTotal(arrange.getTenantId(), factory, arrangeId);
        var sessions = rows.sessions(arrange.getTenantId(), factory, arrangeId, (sessionPage - 1L) * 100);
        var intervals = rows.intervals(arrange.getTenantId(), factory, arrangeId, (intervalPage - 1L) * 200);
        sessions.forEach(session -> assertSession(arrange, session));
        return new MesWorkpieceTimeDTO.View(arrange.getTenantId().toString(), factory, arrangeId.toString(), BASIS,
                "标识由操作者现场读取；仅校验形态和已知归属冲突，不代表已核实物绑定，不产生生产数量。", sessions, intervals,
                sessionPage, 100, sessionTotal, intervalPage, 200, intervalTotal);
    }

    @Transactional(rollbackFor = Exception.class)
    public MesWorkpieceTimeDTO.Resolution cancel(MesWorkpieceTimeDTO.Command command) {
        String factory = access.require("write");
        ready();
        validate(command);
        Long tenant = TenantContextHolder.getTenantId();
        String hash = hash(tenant + ":" + factory + ":" + SecurityUtils.getUser().getId() + ":" + write(command));
        rows.lockRequest(tenant + ":" + factory + ":" + command.getRequestId());
        var original = rows.operation(tenant, factory, command.getRequestId());
        if (original != null) {
            require(Objects.equals(original.getActorId(), SecurityUtils.getUser().getId())
                    && Objects.equals(original.getRequestHash(), hash), "工件计时原号已用于其他操作者或内容");
            return resolution(command.getRequestId(), "APPLIED", readReceipt(original));
        }
        cancellations.cancel(COMMAND_KIND, command.getRequestId(), hash);
        return resolution(command.getRequestId(), "CANCELLED", null);
    }

    @Transactional(rollbackFor = Exception.class)
    public MesWorkpieceTimeDTO.Resolution receipt(String requestId) {
        String factory = access.require("read");
        ready();
        require(requestId != null && requestId.matches("[A-Za-z0-9._:-]{16,100}"), "原请求号格式无效");
        // 等待已到达的同号命令；查无不能撤销尚在网络中的请求，客户端仍须保留原号原文。
        rows.lockRequest(TenantContextHolder.getTenantId() + ":" + factory + ":" + requestId);
        var row = rows.operation(TenantContextHolder.getTenantId(), factory, requestId);
        if (row == null) {
            return resolution(requestId, cancellations.cancelled(COMMAND_KIND, requestId) ? "CANCELLED" : "ABSENT", null);
        }
        require(Objects.equals(row.getActorId(), SecurityUtils.getUser().getId()), "当前操作者原计时回执不存在");
        return resolution(requestId, "APPLIED", readReceipt(row));
    }

    private MesWorkpieceTimeDTO.Resolution resolution(String requestId, String state, MesWorkpieceTimeDTO.Receipt receipt) {
        return new MesWorkpieceTimeDTO.Resolution("MES_WORKPIECE_TIME_RESOLUTION_V1", requestId, state, receipt);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void pauseForArrange(MesTaskArrangeEntity arrange, LocalDateTime at, String reason) {
        lifecycle(arrange, at, "DISPATCH_PAUSE", reason);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void resumeForArrange(MesTaskArrangeEntity arrange, LocalDateTime at) {
        lifecycle(arrange, at, "DISPATCH_RESUME", null);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void finishForArrange(MesTaskArrangeEntity arrange, LocalDateTime at, String reason) {
        lifecycle(arrange, at, "DISPATCH_FINISH", reason);
    }

    private void lifecycle(MesTaskArrangeEntity arrange, LocalDateTime at, String action, String reason) {
        String factory = access.require("write");
        ready();
        require(arrange != null && Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(arrange.getFactoryCode(), factory), "派工计时生命周期作用域不一致");
        require(at != null, "派工计时事件缺少原事务发生时刻");
        at = at.truncatedTo(ChronoUnit.MICROS);
        // 原派工/工单锁由父动作持有。状态变化会移出活动集合，必须按稳定ID前进，不能用OFFSET跳页。
        long after = 0;
        while (true) {
            var sessions = rows.lockSessions(arrange.getTenantId(), factory, arrange.getId(), after);
            if (sessions.isEmpty()) {
                break;
            }
            for (var session : sessions) {
                require(session.getId() != null && session.getId() > after, "工件生命周期分页未按原会话ID前进");
                after = session.getId();
                assertSession(arrange, session);
                boolean running = "RUNNING".equals(session.getStatus());
                boolean paused = "PAUSED".equals(session.getStatus());
                if ("DISPATCH_PAUSE".equals(action) && running) {
                    transition(session, "PAUSED", "DISPATCH", "PAUSE", at, reason);
                } else if ("DISPATCH_RESUME".equals(action) && paused && "DISPATCH".equals(session.getPauseSource())) {
                    transition(session, "RUNNING", null, "RUN", at, null);
                } else if ("DISPATCH_FINISH".equals(action) && (running || paused)) {
                    transition(session, "FINISHED", null, null, at, null);
                } else {
                    continue;
                }
                String request = action + ":" + session.getId() + ":" + session.getVersionNo();
                record(session, request, hash(request), action, at);
            }
        }
    }

    private void transition(MesWorkpieceTimeSessionEntity session, String status, String pauseSource,
                            String nextKind, LocalDateTime at, String reason) {
        var open = rows.openIntervals(session.getTenantId(), session.getFactoryCode(), session.getId());
        require(open.size() == 1, "原工件必须有唯一实际开放区间，禁止猜测起止");
        var interval = open.get(0);
        require(Objects.equals(interval.getArrangeId(), session.getArrangeId())
                && Objects.equals(interval.getIdentifier(), session.getIdentifier())
                && ("RUNNING".equals(session.getStatus()) ? "RUN" : "PAUSE").equals(interval.getKind())
                && interval.getStartTime() != null && !at.isBefore(interval.getStartTime()), "原工件区间身份、状态或时间顺序不一致");
        require(rows.closeInterval(session.getTenantId(), session.getFactoryCode(), interval.getId(), at) == 1,
                "工件区间关闭失败，整笔操作回滚");
        Long before = session.getVersionNo();
        require(before != null && before > 0 && before < Long.MAX_VALUE, "工件会话版本未知或耗尽");
        session.setStatus(status).setPauseSource(pauseSource).setVersionNo(before + 1);
        require(rows.changeSession(session, before) == 1, "工件会话版本冲突，整笔操作回滚");
        if (nextKind != null) {
            open(session, nextKind, at, reason);
        }
    }

    private void open(MesWorkpieceTimeSessionEntity session, String kind, LocalDateTime at, String reason) {
        var interval = new MesWorkpieceTimeIntervalEntity().setFactoryCode(session.getFactoryCode()).setSessionId(session.getId())
                .setArrangeId(session.getArrangeId()).setArrangeCode(session.getArrangeCode()).setTaskCode(session.getTaskCode())
                .setProduceTaskCode(session.getProduceTaskCode()).setIdentifier(session.getIdentifier()).setIdentifierType(session.getIdentifierType())
                .setDeviceCode(session.getDeviceCode()).setWorkerCode(session.getWorkerCode()).setKind(kind).setStartTime(at)
                .setStatus("OPEN").setReasonCode(reason);
        interval.setId(IdWorker.getId());
        interval.setTenantId(session.getTenantId());
        require(rows.insertInterval(interval) == 1, "工件实际区间保存失败，整笔操作回滚");
    }

    private MesWorkpieceTimeDTO.Receipt record(MesWorkpieceTimeSessionEntity session, String request, String hash,
                                               String action, LocalDateTime at) {
        var receipt = new MesWorkpieceTimeDTO.Receipt(VERSION, request, action, session.getId().toString(),
                session.getArrangeId().toString(), session.getIdentifierType(), session.getIdentifier(), session.getStatus(),
                session.getPauseSource(), session.getVersionNo(), at, BASIS);
        var operation = new MesWorkpieceTimeOperationEntity().setFactoryCode(session.getFactoryCode()).setRequestId(request)
                .setRequestHash(hash).setSessionId(session.getId()).setArrangeId(session.getArrangeId())
                .setActorId(SecurityUtils.getUser().getId()).setAction(action).setReceiptJson(write(receipt));
        operation.setId(IdWorker.getId());
        operation.setTenantId(session.getTenantId());
        require(rows.insertOperation(operation) == 1, "工件计时永久原回执保存失败，整笔操作回滚");
        return receipt;
    }

    private MesWorkpieceTimeDTO.Receipt readReceipt(MesWorkpieceTimeOperationEntity operation) {
        try {
            var result = json.readValue(operation.getReceiptJson(), MesWorkpieceTimeDTO.Receipt.class);
            require(VERSION.equals(result.contractVersion()) && operation.getRequestId().equals(result.requestId())
                    && operation.getSessionId().toString().equals(result.sessionId())
                    && operation.getArrangeId().toString().equals(result.arrangeId()), "原工件回执身份或版本损坏");
            return result;
        } catch (JsonProcessingException failure) {
            throw new CheckedException("原工件计时回执损坏，禁止重放动作");
        }
    }

    private MesTaskArrangeEntity lockedArrangement(Long id) {
        var original = taskLocks.arrange(id);
        var locked = arrangement(id, access.require("write"), true);
        taskLocks.assertArrange(original, locked);
        return locked;
    }

    private MesTaskArrangeEntity arrangement(Long id, String factory, boolean lock) {
        require(id != null && id > 0, "请选择精确派工");
        var query = new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskArrangeEntity::getFactoryCode, factory);
        if (lock) {
            query.last("FOR UPDATE NOWAIT");
        }
        var arrange = arranges.selectOne(query);
        require(arrange != null && Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(arrange.getFactoryCode(), factory) && Objects.equals(arrange.getId(), id)
                && arrange.getTaskCode() != null && arrange.getProduceTaskCode() != null, "当前实际工厂派工不存在或原工序归属不完整");
        return arrange;
    }

    private void assertSession(MesTaskArrangeEntity arrange, MesWorkpieceTimeSessionEntity session) {
        require(Objects.equals(arrange.getTenantId(), session.getTenantId()) && Objects.equals(arrange.getFactoryCode(), session.getFactoryCode())
                && Objects.equals(arrange.getId(), session.getArrangeId()) && Objects.equals(arrange.getCode(), session.getArrangeCode())
                && Objects.equals(arrange.getTaskCode(), session.getTaskCode()) && Objects.equals(arrange.getProduceTaskCode(), session.getProduceTaskCode())
                && BASIS.equals(session.getCaptureBasis()), "工件计时会话与原派工的实际作用域不一致");
    }

    private void assertWorker(MesTaskArrangeEntity arrange) {
        require(arrange.getWorkerCode() != null && Arrays.stream(arrange.getWorkerCode().split(","))
                .map(String::trim).anyMatch(SecurityUtils.getUser().getUsername()::equals), "必须由本厂派工当前指定执行人采集工件时间");
    }

    private void ready() {
        require(Boolean.TRUE.equals(rows.ready()), "工件计时原账及数据库守卫未就绪，禁止补造时间");
    }

    private void validate(MesWorkpieceTimeDTO.Command command) {
        require(command != null, "工件计时命令不能为空");
        require(command.getRequestId() != null && command.getRequestId().matches("[A-Za-z0-9._:-]{16,100}"), "计时原请求号格式无效");
        require(command.getAction() != null && Set.of("START", "PAUSE", "RESUME", "FINISH").contains(command.getAction()), "未知工件计时动作");
        require(command.getIdentifierType() != null && Set.of("SN", "BATCH").contains(command.getIdentifierType()), "计时标识只允许SN或BATCH");
        require(command.getIdentifier() != null && command.getIdentifier().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}"), "工件标识形态无效，禁止按空码或控制字符采集");
        require("START".equals(command.getAction()) ? command.getExpectedVersion() == null
                : command.getExpectedVersion() != null && command.getExpectedVersion() > 0, "请携带所见工件会话版本；首次开始不能伪带旧版本");
        require(!"PAUSE".equals(command.getAction()) || command.getReasonCode() != null
                && command.getReasonCode().matches("[A-Z][A-Z0-9_]{0,63}"), "暂停须填写明确原因编码");
        require("PAUSE".equals(command.getAction()) || command.getReasonCode() == null, "非暂停动作不能携带旁路原因字段");
        id(command.getArrangeId());
    }

    private Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "派工ID须为精确正整数文本");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new CheckedException("派工ID超过Long范围");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new CheckedException("工件计时原正文无法序列化");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
