package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesLaborCommand;
import com.kitex.mom.mes.entity.MesLaborEntry;
import com.kitex.mom.mes.entity.MesLaborOperation;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.mapper.MesLaborEntryMapper;
import com.kitex.mom.mes.mapper.MesLaborOperationMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 本人实际直接作业申报；连续生产段只是允许范围，不按人数乘时长自动生成事实。
 */
@Service
@RequiredArgsConstructor
public class MesLaborService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MesActualFactoryAccessService access;
    private final MesTaskArrangeMapper arranges;
    private final MesTaskExecutionSegmentMapper segments;
    private final MesLaborEntryMapper entries;
    private final MesLaborOperationMapper operations;
    private final org.mybatis.spring.SqlSessionTemplate session;

    public Object context(String arrangeCode, long page) {
        String f = access.require("read");
        require(StrUtil.isNotBlank(arrangeCode) && page > 0 && page <= 100000, "请指定派工编码和有效页码");
        var a = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, f).eq(MesTaskArrangeEntity::getCode, arrangeCode.trim()));
        require(a != null, "当前工厂派工不存在");
        var p = segments.selectPage(new Page<>(page, 30), segmentScope(f).eq(MesTaskExecutionSegmentEntity::getArrangeId, a.getId()).orderByDesc(MesTaskExecutionSegmentEntity::getSegmentNo));
        return Map.of("arrange", a, "segments", p, "actorId", SecurityUtils.getUser().getId(), "actorCode", SecurityUtils.getUser().getUsername(),
                "notice", "本人只能申报来源段指定人员范围内的实际作业。每段可填多个不重叠区间以扣除休息/离岗，不自动用设备时长填满。历史缺口/未关闭段不能作为批准依据。");
    }

    @Transactional(readOnly = true)
    public Object preview(Long segmentId) {
        String f = access.require("read");
        var s = segment(segmentId, f, false);
        eligible(s);
        require(member(s, SecurityUtils.getUser().getUsername()), "只有该生产段冻结的指定人员可以申报");
        return Map.of("segment", s, "fingerprint", hash(json(s)), "sourceJson", json(s));
    }

    public Object page(long page, String status) {
        String f = access.require("read");
        require(page > 0 && page <= 100000, "页码无效");
        return entries.selectPage(new Page<>(page, 30), scope(f).eq(StrUtil.isNotBlank(status), MesLaborEntry::getStatus, status).orderByDesc(MesLaborEntry::getId));
    }

    public Object detail(Long id) {
        String f = access.require("read");
        var e = entry(id, f, false);
        var s = segments.selectOne(segmentScope(f).eq(MesTaskExecutionSegmentEntity::getId, e.getSegmentId()));
        return Map.of("entry", e, "operations", operations.selectList(opScope(f).eq(MesLaborOperation::getEntryId, id).orderByAsc(MesLaborOperation::getId)),
                "sourceState", s == null ? "MISSING" : e.getSourceFingerprint().equals(hash(json(s))) ? "UNCHANGED" : "CHANGED");
    }

    @Transactional(rollbackFor = Exception.class)
    public Object execute(String action, MesLaborCommand c) {
        String f = access.require("write");
        var user = SecurityUtils.getUser();
        require(Set.of("SUBMIT", "APPROVE", "REJECT", "WITHDRAW", "REQUEST_VOID", "APPROVE_VOID", "REJECT_VOID", "WITHDRAW_VOID").contains(action), "工时动作无效");
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 1000, "须填写稳定请求号及1000字内实际依据");
        c.setReason(c.getReason().trim());
        if ("SUBMIT".equals(action))
            require(c.getId() == null && c.getSegmentId() != null && c.getLaborStart() != null && c.getLaborEnd() != null && c.getExpectedFingerprint() != null && c.getExpectedFingerprint().matches("[a-f0-9]{64}"), "先选择真实生产段并预览，填写本人实际起止时间");
        else
            require(c.getId() != null && c.getSegmentId() == null && c.getLaborStart() == null && c.getLaborEnd() == null && c.getCorrectedFromId() == null && c.getExpectedFingerprint() == null, "原工时处理不得夹带人员/时间/来源变更");
        String fp = hash(json(Arrays.asList(action, c, user.getId())));
        entries.lockKey("MES-LABOR-REQUEST:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getRequestId());
        session.clearCache();
        var old = operations.selectOne(opScope(f).eq(MesLaborOperation::getRequestId, c.getRequestId()));
        if (old != null) {
            require(fp.equals(old.getFingerprint()), "请求号已用于其他内容、人员或动作");
            return Map.of("entry", entry(old.getEntryId(), f, false), "operation", old, "replayed", true);
        }
        MesLaborEntry e;
        String before = null, observed = null;
        if ("SUBMIT".equals(action)) {
            // 跨工厂人员锁与数据库重叠守卫共用；不得把同一人在不同工厂的同一秒重复记账。
            entries.lockKey("MES-LABOR-WORKER:" + TenantContextHolder.getTenantId() + ":" + user.getId());
            session.clearCache();
            var s = segment(c.getSegmentId(), f, true);
            eligible(s);
            require(member(s, user.getUsername()), "本人不在该生产段冻结的执行人员内，不能代报");
            observed = hash(json(s));
            require(observed.equals(c.getExpectedFingerprint()), "生产段事实已变化，请重新预览");
            long seconds = Duration.between(c.getLaborStart(), c.getLaborEnd()).getSeconds();
            require(c.getLaborStart().getNano() == 0 && c.getLaborEnd().getNano() == 0 && seconds > 0 && seconds <= 86400
                    && !c.getLaborStart().isBefore(s.getStartTime()) && !c.getLaborEnd().isAfter(s.getEndTime()) && !c.getLaborEnd().isAfter(LocalDateTime.now()), "实际工时须为已结束生产段内的正向整秒区间，单次不超过24小时；不得含暂停或未来时间");
            if (c.getCorrectedFromId() != null) {
                var prior = entry(c.getCorrectedFromId(), f, false);
                require("VOIDED".equals(prior.getStatus()) && Objects.equals(prior.getWorkerId(), user.getId()) && Objects.equals(prior.getSegmentId(), s.getId()), "更正来源须为本人同段已批准作废工时");
            }
            e = new MesLaborEntry();
            e.setTenantId(TenantContextHolder.getTenantId());
            e.setFactoryCode(f);
            e.setSegmentId(s.getId());
            e.setArrangeId(s.getArrangeId());
            e.setArrangeCode(s.getArrangeCode());
            e.setTaskCode(s.getTaskCode());
            e.setProduceTaskCode(s.getProduceTaskCode());
            e.setWorkerId(user.getId());
            e.setWorkerCode(user.getUsername());
            e.setWorkerName(user.getName());
            e.setLaborStart(c.getLaborStart());
            e.setLaborEnd(c.getLaborEnd());
            e.setDurationSeconds(seconds);
            e.setCorrectedFromId(c.getCorrectedFromId());
            e.setStatus("PENDING");
            e.setSourceJson(json(s));
            e.setSourceFingerprint(observed);
            e.setReason(c.getReason());
            e.setOperatorId(user.getId());
            e.setOperationReason(c.getReason());
            assertNoOverlap(e);
            require(entries.insert(e) == 1, "本人工时申报保存失败");
        } else {
            var found = entry(c.getId(), f, false);
            entries.lockKey("MES-LABOR-WORKER:" + TenantContextHolder.getTenantId() + ":" + found.getWorkerId());
            session.clearCache();
            e = entry(c.getId(), f, true);
            before = e.getStatus();
            boolean own = Objects.equals(user.getId(), e.getWorkerId());
            if (Set.of("WITHDRAW", "REQUEST_VOID", "WITHDRAW_VOID").contains(action))
                require(own, "只有本人可撤回或发起作废，不能代操作");
            else require(!own, "工时批准/驳回必须由另一名授权人员完成");
            String next = switch (action) {
                case "APPROVE" -> {
                    require("PENDING".equals(before), "仅待审工时可批准");
                    var s = segment(e.getSegmentId(), f, true);
                    eligible(s);
                    observed = hash(json(s));
                    require(e.getSourceFingerprint().equals(observed), "生产段已变化，不能按旧依据批准；请驳回重报");
                    assertNoOverlap(e);
                    yield "APPROVED";
                }
                case "REJECT" -> {
                    require("PENDING".equals(before), "仅待审工时可驳回");
                    yield "REJECTED";
                }
                case "WITHDRAW" -> {
                    require("PENDING".equals(before), "仅待审工时可撤回");
                    yield "WITHDRAWN";
                }
                case "REQUEST_VOID" -> {
                    require("APPROVED".equals(before), "仅有效批准工时可申请作废");
                    yield "VOID_PENDING";
                }
                case "APPROVE_VOID" -> {
                    require("VOID_PENDING".equals(before), "仅待作废工时可批准作废");
                    yield "VOIDED";
                }
                case "REJECT_VOID", "WITHDRAW_VOID" -> {
                    require("VOID_PENDING".equals(before), "仅待作废工时可恢复原批准状态");
                    yield "APPROVED";
                }
                default -> throw new CheckedException("工时动作无效");
            };
            e.setStatus(next);
            e.setOperatorId(user.getId());
            e.setOperationReason(c.getReason());
            require(entries.updateById(e) == 1, "工时处理保存失败");
        }
        var op = new MesLaborOperation();
        op.setTenantId(e.getTenantId());
        op.setFactoryCode(f);
        op.setEntryId(e.getId());
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fp);
        op.setAction(action);
        op.setBeforeStatus(before);
        op.setAfterStatus(e.getStatus());
        op.setOperatorId(user.getId());
        op.setReason(c.getReason());
        op.setObservedFingerprint(observed);
        require(operations.insert(op) == 1, "工时永久回执保存失败");
        return Map.of("entry", e, "operation", op, "replayed", false);
    }

    private void assertNoOverlap(MesLaborEntry e) {
        require(entries.selectCount(scope(e.getFactoryCode()).eq(MesLaborEntry::getWorkerId, e.getWorkerId()).ne(e.getId() != null, MesLaborEntry::getId, e.getId())
                .in(MesLaborEntry::getStatus, "PENDING", "APPROVED", "VOID_PENDING").lt(MesLaborEntry::getLaborStart, e.getLaborEnd()).gt(MesLaborEntry::getLaborEnd, e.getLaborStart())) == 0, "本人已有重叠的待审/有效/待作废工时，请先核对原记录");
    }

    private void eligible(MesTaskExecutionSegmentEntity s) {
        require("CLOSED".equals(s.getStatus()) && "RUN".equals(s.getSegmentType()) && Boolean.TRUE.equals(s.getSampleEligible())
                && s.getStartTime() != null && s.getEndTime() != null && s.getEndTime().isAfter(s.getStartTime()) && s.getDurationSeconds() != null
                && s.getDurationSeconds() == Duration.between(s.getStartTime(), s.getEndTime()).getSeconds() && !"LEGACY_GAP".equals(s.getCloseReason()), "须有已关闭、非历史缺口且秒数守恒的真实生产段，不从设备累计时长猜实际工时");
    }

    private boolean member(MesTaskExecutionSegmentEntity s, String code) {
        return StrUtil.isNotBlank(code) && Arrays.stream(Objects.toString(s.getWorkerCodes(), "").split(",")).map(String::trim).anyMatch(code::equals);
    }

    private MesTaskExecutionSegmentEntity segment(Long id, String f, boolean lock) {
        var q = segmentScope(f).eq(MesTaskExecutionSegmentEntity::getId, id);
        if (lock) q.last("FOR SHARE");
        var s = segments.selectOne(q);
        require(s != null, "当前工厂生产段不存在");
        var a = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, s.getArrangeId()).eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskArrangeEntity::getFactoryCode, f));
        require(a != null && Objects.equals(a.getCode(), s.getArrangeCode()) && Objects.equals(a.getTaskCode(), s.getTaskCode()) && Objects.equals(a.getProduceTaskCode(), s.getProduceTaskCode())
                && Objects.equals(a.getRouteCode(), s.getProcessCode()) && Objects.equals(a.getMatCode(), s.getMaterialCode()), "生产段与派工/工序/物料身份不一致，不能记工时");
        return s;
    }

    private MesLaborEntry entry(Long id, String f, boolean lock) {
        var q = scope(f).eq(MesLaborEntry::getId, id);
        if (lock) q.last("FOR UPDATE");
        var e = entries.selectOne(q);
        require(e != null, "当前工厂工时记录不存在");
        return e;
    }

    private LambdaQueryWrapper<MesLaborEntry> scope(String f) {
        return new LambdaQueryWrapper<MesLaborEntry>().eq(MesLaborEntry::getTenantId, TenantContextHolder.getTenantId()).eq(MesLaborEntry::getFactoryCode, f);
    }

    private LambdaQueryWrapper<MesLaborOperation> opScope(String f) {
        return new LambdaQueryWrapper<MesLaborOperation>().eq(MesLaborOperation::getTenantId, TenantContextHolder.getTenantId()).eq(MesLaborOperation::getFactoryCode, f);
    }

    private LambdaQueryWrapper<MesTaskExecutionSegmentEntity> segmentScope(String f) {
        return new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>().eq(MesTaskExecutionSegmentEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskExecutionSegmentEntity::getFactoryCode, f);
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("工时来源证据无法完整序列化");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
