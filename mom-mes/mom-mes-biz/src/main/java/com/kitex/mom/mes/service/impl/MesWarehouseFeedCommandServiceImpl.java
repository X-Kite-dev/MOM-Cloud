package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWarehouseFeedBirthDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedCommandEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedCommandMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.service.MesWarehouseFeedCommandService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;
import static com.kitex.mom.mes.service.impl.MesWarehouseFeedCommandCodec.COMMAND_VERSION;
import static com.kitex.mom.mes.service.impl.MesWarehouseFeedCommandCodec.EVIDENCE_VERSION;

/**
 * 稳定FEED真实事务。原号恢复先于当前库存/生产许可；首次真实领出、出生与永久终态同事务。
 * 不把超时、ABSENT或客户端撤销当作未执行，不提供任意重号投料或后台自动补投。
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseFeedCommandServiceImpl
        extends ServiceImpl<MesWarehouseFeedCommandMapper, MesWarehouseFeedCommandEntity>
        implements MesWarehouseFeedCommandService {
    private final MesWarehouseFeedCommandCodec commands;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformSourceScopeLock locks;
    private final MesTaskArrangeMapper arrangements;
    private final MesTaskMapper tasks;
    private final MesWarehouseFeedService feeds;
    private final MesWarehouseFeedBirthService births;
    private final PlatformTransactionManager transactions;
    private final MesWarehouseFeedPreparationReader preparation;

    @Override
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    public MesWarehouseFeedCommandDTO.Context context() {
        String factory = access.require("read");
        ready();
        return new MesWarehouseFeedCommandDTO.Context(tenant().toString(), factory, actor().toString(), COMMAND_VERSION);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    @Transactional(propagation = Propagation.NEVER)
    public MesWarehouseFeedPreparationDTO.Observation prepare(MesWarehouseFeedPreparationDTO.Query query) {
        var template = independent(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template.execute(status -> preparation.read(query, context()));
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    @Transactional(propagation = Propagation.NEVER)
    public MesWarehouseFeedCommandDTO.Receipt execute(String raw) {
        String factory = access.require("write");
        JsonNode body = commands.original(raw, factory);
        return independent(false).execute(status -> {
            ready();
            lock(commands.text(body, "requestId"));
            var original = original(commands.text(body, "requestId"), factory);
            if (original != null) {
                require(raw.equals(original.getCommandSnapshot()), "投料原号禁止改变原文");
                return verified(original);
            }
            var feed = commands.feed(body);
            // 无锁定位真实派工，然后按其实际父工单取统一try锁，不靠客户端taskCode决定库存父锁。
            var candidates = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                    .eq(MesTaskArrangeEntity::getCode, feed.getArrangeCode()).last("LIMIT 2"));
            require(candidates.size() == 1, "投料实际派工未唯一归属当前工厂");
            var initial = candidates.get(0);
            require(Objects.equals(initial.getTaskCode(), feed.getTaskCode()), "投料期望工单与实际派工不一致");
            locks.task(tenant(), factory, initial.getTaskCode());
            var parents = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                    .eq(MesTaskEntity::getCode, initial.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
            require(parents.size() == 1, "投料父工单缺失或不唯一");
            // 真实父锁先于派工行锁；saveFeed同事务再次核父/派工、移动未决及自身投料状态门。
            var locked = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getId, initial.getId()).eq(MesTaskArrangeEntity::getTenantId, tenant())
                    .eq(MesTaskArrangeEntity::getFactoryCode, factory).last("LIMIT 2 FOR UPDATE NOWAIT"));
            require(locked.size() == 1, "投料派工变化或当前被占用");
            var arrange = locked.get(0);
            require(Objects.equals(arrange.getCode(), initial.getCode())
                    && Objects.equals(arrange.getTaskCode(), initial.getTaskCode())
                    && Objects.equals(arrange.getProduceTaskCode(), feed.getProduceTaskCode())
                    && Objects.equals(arrange.getActualWorksiteCode() == null ? "" : arrange.getActualWorksiteCode(),
                    commands.text(body, "receivingWorksiteCode")), "实际接收工位/工序/派工与期望不一致");
            var row = base(body, raw, factory, "APPLIED");
            feed.setFeedCommandId(row.getId());
            require(Boolean.TRUE.equals(feeds.saveFeed(feed)), "实际投料失败，原请求全部回滚");
            var birth = births.evidence(feed.getId());
            require("RECORDED".equals(birth.status()) && birth.birth() != null
                    && Objects.equals(birth.birth().getActorId(), actor())
                    && Objects.equals(birth.birth().getUnit(), commands.text(body, "expectedUnit"))
                    && Objects.equals(birth.birth().getReceivingWorksiteCode() == null ? "" : birth.birth().getReceivingWorksiteCode(),
                    commands.text(body, "receivingWorksiteCode")), "真实投料原单位/本人/接收工位不一致，整笔回滚");
            row.setFeedId(feed.getId());
            row.setBirthId(birth.birth().getId());
            freeze(row, birth);
            require(save(row), "投料永久原号必要写失败，全部回滚");
            return verified(original(row.getRequestId(), factory));
        });
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    @Transactional(propagation = Propagation.NEVER)
    public MesWarehouseFeedCommandDTO.Receipt receipt(String requestId) {
        String factory = access.require("read");
        commands.requestId(requestId);
        return independent(true).execute(status -> {
            ready();
            var row = original(requestId, factory);
            return row == null ? new MesWarehouseFeedCommandDTO.Receipt("ABSENT", requestId,
                    tenant().toString(), factory, actor().toString(), null, null) : verified(row);
        });
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    @Transactional(propagation = Propagation.NEVER)
    public MesWarehouseFeedCommandDTO.Receipt cancel(String raw, String reason) {
        String factory = access.require("write");
        var body = commands.original(raw, factory);
        codec.reason(reason);
        require(reason.length() <= 500, "永久作废原因不能超过500字符");
        return independent(false).execute(status -> {
            ready();
            String requestId = commands.text(body, "requestId");
            lock(requestId);
            var row = original(requestId, factory);
            if (row != null) {
                require(raw.equals(row.getCommandSnapshot()), "同投料原号禁止改变原文");
                require(!"CANCELLED".equals(row.getStatus()) || reason.equals(row.getCancellationReason()), "原作废原因不可改变");
                return verified(row);
            }
            row = base(body, raw, factory, "CANCELLED");
            row.setCancellationReason(reason);
            freeze(row, null);
            require(save(row), "投料永久作废必要写失败，保留原号待核");
            return verified(original(requestId, factory));
        });
    }

    /**
     * 同租户跨工厂查询后再核本人，不能切厂将已有号误报ABSENT。
     */
    private MesWarehouseFeedCommandEntity original(String requestId, String factory) {
        var rows = baseMapper.original(tenant(), requestId);
        require(rows.size() <= 1, "投料原号有多个永久结果，禁止猜选");
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        require(Objects.equals(row.getFactoryCode(), factory) && Objects.equals(row.getActorId(), actor()),
                "该投料原号属于另一工厂或人员，请在原上下文核账，禁止复用");
        return row;
    }

    /**
     * 按永久原文和出生全集核回执，不依赖当前物料主数据或重新分配库存。
     */
    private MesWarehouseFeedCommandDTO.Receipt verified(MesWarehouseFeedCommandEntity row) {
        require(row != null && COMMAND_VERSION.equals(row.getCommandVersion())
                        && EVIDENCE_VERSION.equals(row.getEvidenceVersion())
                        && codec.fingerprint(COMMAND_VERSION, row.getCommandSnapshot()).equals(row.getCommandFingerprint())
                        && codec.fingerprint(EVIDENCE_VERSION, row.getEvidenceSnapshot()).equals(row.getEvidenceFingerprint()),
                "投料永久原文版本或指纹无法核验");
        var body = commands.original(row.getCommandSnapshot(), row.getFactoryCode());
        require(row.getRequestId().equals(commands.text(body, "requestId")), "原请求号与原文不一致");
        MesWarehouseFeedBirthDTO birth = null;
        if ("APPLIED".equals(row.getStatus())) {
            birth = births.evidence(row.getFeedId());
            require(birth.birth() != null && Objects.equals(birth.birth().getId(), row.getBirthId())
                    && Objects.equals(birth.birth().getActorId(), row.getActorId())
                    && Objects.equals(birth.birth().getUnit(), commands.text(body, "expectedUnit")), "原投料出生身份/原单位不一致");
        } else {
            require("CANCELLED".equals(row.getStatus()) && row.getFeedId() == null && row.getBirthId() == null
                    && row.getCancellationReason() != null, "投料永久终态不完整");
        }
        require(codec.snapshot(facts(row, birth)).equals(row.getEvidenceSnapshot()), "投料永久终态原字段不一致");
        return new MesWarehouseFeedCommandDTO.Receipt(row.getStatus(), row.getRequestId(), row.getTenantId().toString(),
                row.getFactoryCode(), row.getActorId().toString(), row, birth);
    }

    /**
     * 主键、实际组织、本人及微秒审计全部由服务端生成；原请求正文不重建。
     */
    private MesWarehouseFeedCommandEntity base(JsonNode body, String raw, String factory, String state) {
        var row = new MesWarehouseFeedCommandEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(tenant());
        row.setFactoryCode(factory);
        row.setActorId(actor());
        row.setRequestId(commands.text(body, "requestId"));
        row.setStatus(state);
        row.setCommandVersion(COMMAND_VERSION);
        row.setCommandSnapshot(raw);
        row.setCommandFingerprint(codec.fingerprint(COMMAND_VERSION, raw));
        row.setEvidenceVersion(EVIDENCE_VERSION);
        row.setCreateBy(SecurityUtils.getUser().getUsername());
        row.setCreateTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setDelFlag("0");
        return row;
    }

    /**
     * 固定结果正文和版本域指纹只在首次成功或首次永久作废时生成。
     */
    private void freeze(MesWarehouseFeedCommandEntity row, MesWarehouseFeedBirthDTO birth) {
        row.setEvidenceSnapshot(codec.snapshot(facts(row, birth)));
        row.setEvidenceFingerprint(codec.fingerprint(EVIDENCE_VERSION, row.getEvidenceSnapshot()));
    }

    /**
     * 固定12字段，后续实体扩展不会改变既有成功/作废的原语义。
     */
    private TreeMap<String, String> facts(MesWarehouseFeedCommandEntity row, MesWarehouseFeedBirthDTO birth) {
        var map = new TreeMap<String, String>();
        map.put("version", EVIDENCE_VERSION);
        map.put("commandId", row.getId().toString());
        map.put("requestId", row.getRequestId());
        map.put("tenantId", row.getTenantId().toString());
        map.put("factoryCode", row.getFactoryCode());
        map.put("actorId", row.getActorId().toString());
        map.put("status", row.getStatus());
        map.put("feedId", row.getFeedId() == null ? null : row.getFeedId().toString());
        map.put("birthId", row.getBirthId() == null ? null : row.getBirthId().toString());
        map.put("birthFingerprint", birth == null ? null : birth.birth().getEvidenceFingerprint());
        map.put("commandFingerprint", row.getCommandFingerprint());
        map.put("cancellationReason", row.getCancellationReason());
        return map;
    }

    /**
     * 禁止调用方旧快照参与原号核账，实际投料核心仍加入本次独立事务。
     */
    private TransactionTemplate independent(boolean readOnly) {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        return template;
    }

    /**
     * 未完整升级时拒绝新协议，不回退到旧Boolean投料入口。
     */
    private void ready() {
        require(Boolean.TRUE.equals(baseMapper.ready()), "稳定投料原号/出生保护未完整部署，请先执行累计V2第261节");
    }

    /**
     * 执行和作废共用租户/原号锁；竞争时保留原文待核，不阻塞或另号执行。
     */
    private void lock(String requestId) {
        require(Boolean.TRUE.equals(baseMapper.tryRequest(codec.snapshot(java.util.List.of("MES_FEED_REQUEST_V1", tenant().toString(), requestId)))),
                "投料原号仍在执行，请保留原号查询，禁止另号投料");
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private Long actor() {
        return SecurityUtils.getUser().getId();
    }
}
