package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels;
import com.kitex.mom.mdm.feign.RemoteMdmParticipantService;
import com.kitex.mom.mes.dto.MesShiftHandoverCommand;
import com.kitex.mom.mes.dto.MesShiftLeftoverCommand;
import com.kitex.mom.mes.entity.MesShiftHandover;
import com.kitex.mom.mes.entity.MesShiftHandoverLeftover;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesShiftHandoverLeftoverMapper;
import com.kitex.mom.mes.mapper.MesShiftHandoverMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 交接遗留事项闭环：实名责任、原事项跨班引用、异人验证和永久命令账。
 * 不把人工说明当成MES完成证明；派工类必须重新读取真实派工完成状态。
 */
@Service
@RequiredArgsConstructor
public class MesShiftLeftoverService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MesActualFactoryAccessService access;
    private final RemoteMdmParticipantService people;
    private final MesShiftHandoverMapper handovers;
    private final MesShiftHandoverLeftoverMapper items;
    private final MesTaskArrangeMapper arranges;
    private final JdbcTemplate jdbc;
    private final MesShiftCommandJournal commands;
    private final PlatformTransactionManager transactionManager;

    /**
     * 与正式交接一起冻结的责任计划。只能引用当前工厂原账，不能从名称创建虚构来源。
     */
    public Plan preview(String factory, MesShiftHandoverCommand command) {
        var drafts = command.getLeftoverItems() == null ? List.<MesShiftHandoverCommand.LeftoverDraft>of() : command.getLeftoverItems();
        var carryIds = command.getCarryForwardIds() == null ? List.<String>of() : command.getCarryForwardIds();
        require(drafts.size() <= 20 && carryIds.size() <= 100 && new HashSet<>(carryIds).size() == carryIds.size(), "新增遗留最多20项、结转最多100项且不可重复");
        Set<String> keys = new HashSet<>();
        List<PlannedItem> created = new ArrayList<>();
        for (var draft : drafts) {
            require(draft != null && draft.getClientItemKey() != null && draft.getClientItemKey().matches("[A-Za-z0-9_-]{1,64}")
                    && keys.add(draft.getClientItemKey()), "遗留事项本地编号缺失或重复");
            require(draft.getCategory() != null && Set.of("PRODUCTION", "QUALITY", "EQUIPMENT", "MATERIAL", "SAFETY").contains(draft.getCategory()), "遗留类别不合法");
            text(draft.getTitle(), 200, "遗留标题");
            text(draft.getDescription(), 2000, "遗留说明");
            require(draft.getSourceType() != null && Set.of("MES_DISPATCH", "HUMAN_DECLARED").contains(draft.getSourceType()), "遗留来源仅支持真实派工或明确人工声明");
            require(draft.getDueAt() != null && !draft.getDueAt().isBefore(command.getWindowEnd()), "遗留期限不得早于本次交班结束");
            if ("MES_DISPATCH".equals(draft.getSourceType())) {
                require(command.getArrangeCodes().contains(draft.getArrangeCode()), "遗留派工必须属于本次交接范围");
                arrange(factory, draft.getArrangeCode());
            } else
                require(draft.getArrangeCode() == null || draft.getArrangeCode().isBlank(), "人工现场事项不能夹带未核验的派工来源");
            created.add(new PlannedItem(draft, person(factory, draft.getOwnerUserId())));
        }
        List<Carry> carried = new ArrayList<>();
        for (String raw : carryIds.stream().sorted().toList()) {
            var item = required(factory, id(raw), false);
            require(!"VERIFIED".equals(item.getStatus()), "已验证事项不应继续结转，可先按依据重开");
            person(factory, item.getOwnerUserId().toString());
            carried.add(new Carry(item.getId().toString(), item.getRevision(), item.getTitle(), item.getStatus(), item.getOwnerUserId().toString()));
        }
        return new Plan(List.copyOf(created), List.copyOf(carried));
    }

    /**
     * 调用者已在交接SUBMIT事务，原请求重放不会再次进入本方法。
     */
    public void record(MesShiftHandover handover, MesShiftHandoverCommand command) {
        Plan plan = preview(handover.getFactoryCode(), command);
        // 再比对冻结计划，避免人员/原责任账在外部取证后发生变化却照旧提交。
        try {
            var original = JSON.readTree(handover.getFormalSnapshotJson()).path("leftovers");
            require(original.equals(JSON.valueToTree(plan)), "遗留责任计划已变化，请重新预览后提交");
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("遗留冻结计划无法解析");
        }
        for (var planned : plan.created()) {
            var draft = planned.draft();
            var item = new MesShiftHandoverLeftover();
            item.setTenantId(handover.getTenantId());
            item.setFactoryCode(handover.getFactoryCode());
            item.setHandoverId(handover.getId());
            item.setClientItemKey(draft.getClientItemKey());
            item.setCategory(draft.getCategory());
            item.setTitle(draft.getTitle());
            item.setDescription(draft.getDescription());
            item.setArrangeCode(draft.getArrangeCode());
            item.setSourceType(draft.getSourceType());
            item.setOwnerUserId(id(planned.owner().userId()));
            item.setOwnerSnapshotJson(json(planned.owner()));
            item.setDueAt(draft.getDueAt());
            item.setStatus("OPEN");
            item.setRevision(1);
            item.setSubmittedBy(SecurityUtils.getUser().getId());
            require(items.insert(item) == 1, "遗留事项保存失败");
            link(handover, item.getId(), item.getRevision());
        }
        for (Carry carry : plan.carried()) {
            var item = required(handover.getFactoryCode(), id(carry.id()), true);
            require(Objects.equals(item.getRevision(), carry.revision()) && !"VERIFIED".equals(item.getStatus()), "结转事项已并发变化，请重取原账");
            link(handover, item.getId(), item.getRevision());
        }
    }

    public Object page(Long handoverId, long current, String status) {
        String factory = access.require("read");
        require(current > 0 && current <= 100000, "遗留页码无效");
        var query = scope(factory).eq(status != null && !status.isBlank(), MesShiftHandoverLeftover::getStatus, status);
        if (handoverId != null)
            query.apply("id IN (SELECT leftover_id FROM mom_mes.mes_shift_handover_leftover_link WHERE tenant_id={0} AND factory_code={1} AND handover_id={2})",
                    TenantContextHolder.getTenantId(), factory, handoverId);
        return items.selectPage(new Page<>(current, 20), query.orderByDesc(MesShiftHandoverLeftover::getId));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object history(Long itemId, long current) {
        String factory = access.require("read");
        require(current > 0 && current <= 100000, "履历页码无效");
        required(factory, itemId, false);
        Long total = jdbc.queryForObject("SELECT count(*) FROM mom_mes.mes_shift_handover_leftover_operation WHERE tenant_id=? AND factory_code=? AND leftover_id=?",
                Long.class, TenantContextHolder.getTenantId(), factory, itemId);
        var result = jdbc.queryForList("SELECT request_id,action,actor_id::text,reason,evidence,result_json,create_time FROM mom_mes.mes_shift_handover_leftover_operation WHERE tenant_id=? AND factory_code=? AND leftover_id=? ORDER BY id DESC LIMIT 20 OFFSET ?",
                TenantContextHolder.getTenantId(), factory, itemId, (current - 1) * 20);
        return Map.of("current", current, "size", 20, "total", total == null ? 0 : total, "records", result);
    }

    public Object request(String requestId) {
        String factory = access.require("read");
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "遗留原请求号无效");
        Object receipt = commands.receipt("LEFTOVER", factory, requestId);
        if (receipt != null) return receipt;
        var rows = jdbc.queryForList("SELECT actor_id,result_json FROM mom_mes.mes_shift_handover_leftover_operation WHERE tenant_id=? AND factory_code=? AND request_id=?",
                TenantContextHolder.getTenantId(), factory, requestId);
        if (rows.isEmpty()) return null;
        require(String.valueOf(SecurityUtils.getUser().getId()).equals(String.valueOf(rows.get(0).get("actor_id"))), "仅原操作者可核此遗留命令");
        return parse((String) rows.get(0).get("result_json"));
    }

    /**
     * 取消墓碑只终结未执行请求；已完成事项不会因封存操作回退。
     */
    public Object cancelUnexecuted(String requestId) {
        return commands.cancel("LEFTOVER", access.require("write"), requestId);
    }

    /**
     * 同号异载荷先拒绝；同号重放回原响应，不执行第二次状态迁移。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Object act(String action, MesShiftLeftoverCommand command) {
        String factory = access.require("write");
        require(command != null && Set.of("REPORT_DONE", "VERIFY", "REOPEN", "REASSIGN").contains(action), "遗留办理动作无效");
        require(command.requestId() != null && command.requestId().matches("[A-Za-z0-9_-]{16,64}"), "遗留请求号无效");
        text(command.reason(), 1000, "办理依据");
        Long actor = SecurityUtils.getUser().getId();
        Long itemId = id(command.id());
        String commandHash = hash(json(Arrays.asList(action, command, actor)));
        commands.reserve("LEFTOVER", factory, command.requestId(), action, commandHash);
        return new TransactionTemplate(transactionManager).execute(ignored -> actReserved(action, command, factory, actor, itemId, commandHash));
    }

    private Object actReserved(String action, MesShiftLeftoverCommand command, String factory, Long actor, Long itemId, String commandHash) {
        Object completed = commands.begin("LEFTOVER", factory, command.requestId(), action, commandHash);
        if (completed != null) return completed;
        handovers.lockKey("MES-LEFTOVER-REQUEST:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + command.requestId());
        var prior = jdbc.queryForList("SELECT command_hash,result_json FROM mom_mes.mes_shift_handover_leftover_operation WHERE tenant_id=? AND factory_code=? AND request_id=?",
                TenantContextHolder.getTenantId(), factory, command.requestId());
        if (!prior.isEmpty()) {
            require(commandHash.equals(prior.get(0).get("command_hash")), "同号不能换遗留动作、人员或内容");
            return commands.succeed("LEFTOVER", factory, command.requestId(), parse((String) prior.get(0).get("result_json")));
        }
        var item = required(factory, itemId, true);
        Long accepted = jdbc.queryForObject("SELECT count(*) FROM mom_mes.mes_shift_handover_leftover_link l JOIN mom_mes.mes_shift_handover h ON h.id=l.handover_id AND h.tenant_id=l.tenant_id AND h.factory_code=l.factory_code WHERE l.tenant_id=? AND l.factory_code=? AND l.leftover_id=? AND h.status='ACCEPTED' AND h.del_flag='0'",
                Long.class, TenantContextHolder.getTenantId(), factory, itemId);
        require(accepted != null && accepted > 0, "该遗留事项尚无已接收的交接版本，请先完成实名接班；驳回/撤回不等于已接收责任");
        require(Objects.equals(command.expectedRevision(), item.getRevision()), "遗留事项已被他人处理，请刷新原账");
        if ("REPORT_DONE".equals(action)) {
            require(actor.equals(item.getOwnerUserId()) && "OPEN".equals(item.getStatus()), "仅当前负责人可报请验证未完成事项");
            person(factory, actor.toString());
            text(command.evidence(), 4000, "完成验证证据");
            Map<String, Object> evidence = new TreeMap<>();
            evidence.put("sourceType", item.getSourceType());
            evidence.put("submittedEvidence", command.evidence());
            evidence.put("actorId", actor.toString());
            if ("MES_DISPATCH".equals(item.getSourceType()))
                evidence.put("source", completedDispatch(factory, item.getArrangeCode()));
            item.setResolutionEvidenceJson(json(evidence));
            item.setResolutionFingerprint(hash(item.getResolutionEvidenceJson()));
            item.setCompletedBy(actor);
            item.setStatus("DONE_PENDING");
        } else if ("VERIFY".equals(action)) {
            require("DONE_PENDING".equals(item.getStatus()) && !actor.equals(item.getCompletedBy())
                    && !actor.equals(item.getSubmittedBy()), "须由编制和完成人之外的授权人员独立验证");
            person(factory, actor.toString());
            text(command.evidence(), 4000, "独立验证证据");
            require(item.getResolutionEvidenceJson() != null && hash(item.getResolutionEvidenceJson()).equals(item.getResolutionFingerprint()), "原完成证据已损坏");
            if ("MES_DISPATCH".equals(item.getSourceType())) {
                var now = JSON.valueToTree(completedDispatch(factory, item.getArrangeCode()));
                require(now.equals(parse(item.getResolutionEvidenceJson()).path("source")), "原派工完成依据已变化，请重开后重新报请验证");
            }
            item.setStatus("VERIFIED");
            item.setVerifiedBy(actor);
            item.setVerifiedAt(LocalDateTime.now());
        } else if ("REOPEN".equals(action)) {
            require(Set.of("VERIFIED", "DONE_PENDING").contains(item.getStatus()), "只能重开待验证或已验证事项");
            person(factory, actor.toString());
            item.setStatus("OPEN");
        } else {
            require(!"VERIFIED".equals(item.getStatus()), "已验证事项不再改派，请先按依据重开");
            var owner = person(factory, command.newOwnerUserId());
            item.setOwnerUserId(id(owner.userId()));
            item.setOwnerSnapshotJson(json(owner));
            item.setStatus("OPEN");
        }
        item.setRevision(Math.addExact(item.getRevision(), 1));
        var update = new LambdaUpdateWrapper<MesShiftHandoverLeftover>()
                .eq(MesShiftHandoverLeftover::getId, item.getId())
                .eq(MesShiftHandoverLeftover::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesShiftHandoverLeftover::getFactoryCode, factory);
        // 重开/改派后的旧完成与验证依据只留在不可变操作账，不冒充当前轮次的核验。
        if (!"VERIFY".equals(action)) {
            item.setVerifiedBy(null);
            item.setVerifiedAt(null);
            update.set(MesShiftHandoverLeftover::getVerifiedBy, null).set(MesShiftHandoverLeftover::getVerifiedAt, null);
        }
        if (Set.of("REOPEN", "REASSIGN").contains(action)) {
            item.setCompletedBy(null);
            item.setResolutionEvidenceJson(null);
            item.setResolutionFingerprint(null);
            update.set(MesShiftHandoverLeftover::getCompletedBy, null)
                    .set(MesShiftHandoverLeftover::getResolutionEvidenceJson, null)
                    .set(MesShiftHandoverLeftover::getResolutionFingerprint, null);
        }
        require(items.update(item, update) == 1, "遗留状态保存失败");
        Map<String, Object> result = Map.of("requestId", command.requestId(), "action", action, "item", item);
        jdbc.update("INSERT INTO mom_mes.mes_shift_handover_leftover_operation(tenant_id,factory_code,leftover_id,request_id,command_hash,action,actor_id,reason,evidence,result_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                TenantContextHolder.getTenantId(), factory, item.getId(), command.requestId(), commandHash, action, actor,
                command.reason(), command.evidence(), json(result));
        return commands.succeed("LEFTOVER", factory, command.requestId(), result);
    }

    private Map<String, Object> completedDispatch(String factory, String code) {
        var source = arrange(factory, code);
        require("FINISH".equals(source.getStatus()), "原派工尚未完成，文字说明不能替代真实完成状态");
        return Map.of("id", source.getId().toString(), "code", source.getCode(), "status", source.getStatus(),
                "taskCode", source.getTaskCode(), "produceTaskCode", source.getProduceTaskCode());
    }

    private MesTaskArrangeEntity arrange(String factory, String code) {
        var rows = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, code).last("LIMIT 2"));
        require(rows.size() == 1, "遗留派工来源不存在、重号或不可访问");
        return rows.get(0);
    }

    private MdmParticipantModels.Participant person(String factory, String rawId) {
        id(rawId);
        var response = people.verify(new MdmParticipantModels.Verify(factory, "HANDOVER", List.of(rawId)));
        require(response != null && response.getCode() == 0 && response.getData() != null && response.getData().size() == 1
                && rawId.equals(response.getData().get(0).userId()) && factory.equals(response.getData().get(0).factoryCode())
                && "HANDOVER".equals(response.getData().get(0).purpose()), "遗留责任账号或接班办理权限失效");
        return response.getData().get(0);
    }

    private void link(MesShiftHandover handover, Long itemId, int revision) {
        jdbc.update("INSERT INTO mom_mes.mes_shift_handover_leftover_link(tenant_id,factory_code,handover_id,leftover_id,frozen_revision) VALUES(?,?,?,?,?)",
                handover.getTenantId(), handover.getFactoryCode(), handover.getId(), itemId, revision);
    }

    private MesShiftHandoverLeftover required(String factory, Long id, boolean lock) {
        var query = scope(factory).eq(MesShiftHandoverLeftover::getId, id);
        if (lock) query.last("FOR UPDATE");
        var item = items.selectOne(query);
        require(item != null, "当前工厂遗留事项不存在");
        return item;
    }

    private LambdaQueryWrapper<MesShiftHandoverLeftover> scope(String factory) {
        return new LambdaQueryWrapper<MesShiftHandoverLeftover>().eq(MesShiftHandoverLeftover::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesShiftHandoverLeftover::getFactoryCode, factory);
    }

    private Long id(String value) {
        try {
            require(value != null && value.matches("[1-9][0-9]{0,18}"), "主键必须为正整数文本");
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new CheckedException("主键超出Long范围");
        }
    }

    private void text(String value, int max, String title) {
        require(value != null && !value.isBlank() && value.length() <= max, title + "必填且不能超过" + max + "字");
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("遗留证据序列化失败");
        }
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception ex) {
            throw new CheckedException("遗留原回执损坏");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    public record PlannedItem(MesShiftHandoverCommand.LeftoverDraft draft, MdmParticipantModels.Participant owner) {
    }

    public record Carry(String id, int revision, String title, String status, String ownerUserId) {
    }

    public record Plan(List<PlannedItem> created, List<Carry> carried) {
    }
}
