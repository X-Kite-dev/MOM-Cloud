package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceHoldDTO;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceClaimEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceHoldEntity;
import com.kitex.mom.mes.mapper.MesPhysicalTransformCommandMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceClaimMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceHoldMapper;
import com.kitex.mom.mes.service.MesPhysicalTransformSourceHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 原命令下的真实来源持久占用、本人取消及独立查证。
 * 不接受客户端政策/来源证据，不记产或转换库存；完整资格和三端仍由后续执行事务建设。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalTransformSourceHoldServiceImpl
        extends ServiceImpl<MesPhysicalTransformSourceHoldMapper, MesPhysicalTransformSourceHoldEntity>
        implements MesPhysicalTransformSourceHoldService {
    private final MesPhysicalTransformCommandMapper commands;
    private final MesPhysicalTransformSourceClaimMapper claims;
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformSourceHoldCodec holdCodec;
    private final MesPhysicalTransformSourceFreezeService sources;
    private final MesPhysicalTransformSourceScopeLock scope;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalTransformSourceHoldDTO.Receipt acquire(String requestId, String rawJson) {
        codec.code(requestId, "永久原请求号", 100);
        String fingerprint = holdCodec.acquireFingerprint(rawJson);
        Identity owner = identity();
        return independent(() -> {
            requestLock(owner, requestId);
            var command = command(owner, requestId);
            require(fingerprint.equals(command.getRequestFingerprint()), "占用请求不是永久登记的原正文SHA");
            var body = codec.command(command.getCommandSnapshot());
            require(body instanceof MesPhysicalTransformCommandDTO.BodyV2, "旧V1原文不能换算法重解释为V2来源占用");
            require("REGISTERED".equals(command.getStatus()), "已封存或未知状态的原命令不能取得来源占用");
            requireAvailable();
            var prior = baseMapper.permanent(owner.tenantId(), command.getId());
            if (prior != null) {
                // 已提交原事实仅恢复，不重新冻结、不重开已取消原号，也不重新消耗来源。
                return receiptOf(command, prior, owner);
            }
            requireReady();
            scope.acquire(owner.tenantId(), owner.factoryCode());
            requireReady(); // SUE目录保护锁内再次核验，不能信锁前可能被禁用的typed守卫。
            // 原号锁后取原命令NOWAIT行锁，避免外部事务持父行时FK隐性无限等待。
            assertCommand(commands.locked(owner.tenantId(), requestId), owner);
            var bundle = sources.lockAndRead(body.getInputs().stream().map(row -> row.getStockId()).toList());
            var frozen = holdCodec.freeze(command.getId().toString(), requestId, fingerprint,
                    (MesPhysicalTransformCommandDTO.BodyV2) body, bundle);
            var row = new MesPhysicalTransformSourceHoldEntity().setFactoryCode(owner.factoryCode())
                    .setCommandId(command.getId()).setCommandFingerprint(fingerprint)
                    .setHoldVersion(MesPhysicalTransformSourceHoldCodec.VERSION).setFrozenSnapshot(frozen.snapshot())
                    .setFrozenFingerprint(frozen.fingerprint()).setActorId(owner.actorId()).setActorCode(owner.actorCode())
                    .setStatus("SOURCE_HELD").setHeldTime(LocalDateTime.now());
            row.setTenantId(owner.tenantId());
            row.setCreateBy(owner.actorCode());
            row.setDelFlag("0");
            require(baseMapper.insert(row) == 1, "永久来源占用必要主写未恰好一行");
            for (var pair : frozen.identities()) {
                var claim = new MesPhysicalTransformSourceClaimEntity().setFactoryCode(owner.factoryCode())
                        .setHoldId(row.getId()).setIdentityType(pair.get(0)).setIdentityKey(pair.get(1)).setStatus("HELD");
                claim.setTenantId(owner.tenantId());
                claim.setCreateBy(owner.actorCode());
                claim.setDelFlag("0");
                require(claims.insert(claim) == 1, "来源身份必要写未恰好一行，整次占用回滚");
            }
            var stored = baseMapper.permanent(owner.tenantId(), command.getId());
            require(stored != null && row.getId().equals(stored.getId()) && "SOURCE_HELD".equals(stored.getStatus())
                            && frozen.snapshot().equals(stored.getFrozenSnapshot()) && frozen.fingerprint().equals(stored.getFrozenFingerprint()),
                    "来源占用完整主账写回读不一致");
            return receiptOf(command, stored, owner);
        });
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesPhysicalTransformSourceHoldDTO.Receipt cancel(String requestId, String rawJson) {
        codec.code(requestId, "永久原请求号", 100);
        var input = holdCodec.cancel(rawJson);
        Identity owner = identity();
        return independent(() -> {
            requestLock(owner, requestId);
            var command = command(owner, requestId);
            requireAvailable();
            var row = baseMapper.permanent(owner.tenantId(), command.getId());
            require(row != null, "没有来源占用原事实；不根据NONE/响应不明自动销账");
            var original = receiptOf(command, row, owner);
            require(input.fingerprint().equals(row.getFrozenFingerprint()), "取消SHA与原占用全文不一致");
            if ("CANCELLED".equals(row.getStatus())) {
                require(Objects.equals(input.reason(), row.getCancelReason()), "原占用已有另一取消原因，不覆盖历史");
                return original;
            }
            require("SOURCE_HELD".equals(row.getStatus()) && "REGISTERED".equals(command.getStatus()), "只有本人未执行来源占用可以取消");
            requireReady();
            scope.acquire(owner.tenantId(), owner.factoryCode());
            requireReady();
            assertCommand(commands.locked(owner.tenantId(), requestId), owner);
            require(baseMapper.cancel(row.getId(), owner.tenantId(), owner.factoryCode(), owner.actorId(), owner.actorCode(),
                    input.fingerprint(), LocalDateTime.now(), input.reason()) == 1, "本人来源占用取消CAS未恰好一行");
            require(claims.release(row.getId(), owner.tenantId(), owner.factoryCode()) == original.getIdentities().size(),
                    "取消未释放完整原身份集合，整次事务回滚");
            var stored = baseMapper.permanent(owner.tenantId(), command.getId());
            require(stored != null && "CANCELLED".equals(stored.getStatus()) && stored.getCancelledTime() != null
                    && input.reason().equals(stored.getCancelReason()), "本人取消完整回读不一致");
            return receiptOf(command, stored, owner);
        });
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesPhysicalTransformSourceHoldDTO.Receipt receipt(String requestId) {
        codec.code(requestId, "永久原请求号", 100);
        Identity owner = identity();
        return latestRead(() -> {
            var command = commands.permanent(owner.tenantId(), requestId);
            if (command == null) {
                var receipt = empty(null, owner, "ABSENT");
                receipt.setRequestId(requestId);
                receipt.setMessage("独立原号查询未见永久命令，不证明未投递/未占用；保留完整原文原号查证，不自动重投");
                return receipt;
            }
            assertCommand(command, owner);
            return attached(command, owner);
        });
    }

    @Override
    public MesPhysicalTransformSourceHoldDTO.Receipt attachedReceipt(MesPhysicalTransformCommandEntity command) {
        Identity owner = identity();
        assertCommand(command, owner);
        return attached(command, owner);
    }

    /**
     * 必须沿调用方的最新原账事务附带恢复；不取得来源行锁或重新计算冻结原文。
     */
    private MesPhysicalTransformSourceHoldDTO.Receipt attached(MesPhysicalTransformCommandEntity command, Identity owner) {
        if (!Boolean.TRUE.equals(baseMapper.available())) {
            return empty(command, owner, "UNAVAILABLE");
        }
        var row = baseMapper.permanent(owner.tenantId(), command.getId());
        return row == null ? empty(command, owner, "NONE") : receiptOf(command, row, owner);
    }

    private MesPhysicalTransformSourceHoldDTO.Receipt receiptOf(MesPhysicalTransformCommandEntity command,
                                                                MesPhysicalTransformSourceHoldEntity row, Identity owner) {
        require(Objects.equals(row.getTenantId(), owner.tenantId()) && Objects.equals(row.getFactoryCode(), owner.factoryCode())
                && Objects.equals(row.getActorId(), owner.actorId()) && Objects.equals(row.getActorCode(), owner.actorCode())
                && Objects.equals(row.getCommandId(), command.getId()) && Objects.equals(row.getCommandFingerprint(), command.getRequestFingerprint())
                && MesPhysicalTransformSourceHoldCodec.VERSION.equals(row.getHoldVersion()) && "0".equals(row.getDelFlag())
                && row.getHeldTime() != null && List.of("SOURCE_HELD", "CANCELLED", "APPLIED").contains(row.getStatus()), "永久来源占用owner/版本/主状态损坏");
        var identities = holdCodec.storedIdentities(row.getFrozenSnapshot(), row.getFrozenFingerprint());
        var frozen = codec.objectDocument(row.getFrozenSnapshot(), MesPhysicalTransformSourceHoldCodec.MAX_SNAPSHOT);
        require(command.getId().toString().equals(frozen.path("commandId").textValue())
                && command.getRequestId().equals(frozen.path("requestId").textValue())
                && command.getRequestFingerprint().equals(frozen.path("commandFingerprint").textValue())
                && owner.tenantId().toString().equals(frozen.path("tenantId").textValue())
                && owner.factoryCode().equals(frozen.path("factoryCode").textValue())
                && owner.actorId().toString().equals(frozen.path("actorUserId").textValue())
                && owner.actorCode().equals(frozen.path("actorCode").textValue()), "永久来源冻结全文不属于原命令/当前本人");
        require(Objects.equals(row.getIdentityCount(), (long) identities.size())
                && Objects.equals(row.getMatchingIdentityCount(), (long) identities.size()), "永久身份全集与主状态不一致，不能猜占用/取消成功");
        boolean held = "SOURCE_HELD".equals(row.getStatus());
        boolean cancelled = "CANCELLED".equals(row.getStatus());
        require(held || "APPLIED".equals(row.getStatus()) ? row.getCancelledTime() == null && row.getCancelReason() == null
                        : cancelled && row.getCancelledTime() != null && row.getCancelReason() != null && !row.getCancelledTime().isBefore(row.getHeldTime()),
                "永久来源占用取消时间/原因不一致");
        var receipt = empty(command, owner, row.getStatus());
        receipt.setHoldId(row.getId().toString());
        receipt.setHoldVersion(row.getHoldVersion());
        receipt.setFrozenSnapshot(row.getFrozenSnapshot());
        receipt.setFrozenFingerprint(row.getFrozenFingerprint());
        receipt.setIdentities(identities);
        receipt.setHeldTime(row.getHeldTime().toString());
        receipt.setCancelledTime(row.getCancelledTime() == null ? null : row.getCancelledTime().toString());
        receipt.setCancelReason(row.getCancelReason());
        receipt.setMessage(held ? "原账显示完整来源身份占用；未转换库存或记产，仍须正式执行门核验"
                : "APPLIED".equals(row.getStatus()) ? "原来源占用已由同一永久命令正式消费；查看执行回执核对精确库存与输出谱系"
                : "本人已取消原来源占用；原命令及取消历史仍永久保留，原占用不能重开，没有库存或产量逆向");
        return receipt;
    }

    private MesPhysicalTransformSourceHoldDTO.Receipt empty(MesPhysicalTransformCommandEntity command, Identity owner, String status) {
        var receipt = new MesPhysicalTransformSourceHoldDTO.Receipt();
        if (command != null) {
            receipt.setRequestId(command.getRequestId());
            receipt.setCommandId(command.getId().toString());
            receipt.setCommandFingerprint(command.getRequestFingerprint());
        }
        receipt.setTenantId(owner.tenantId().toString());
        receipt.setFactoryCode(owner.factoryCode());
        receipt.setActorUserId(owner.actorId().toString());
        receipt.setActorCode(owner.actorCode());
        receipt.setStatus(status);
        receipt.setExecutionAvailable(false);
        receipt.setMessage("UNAVAILABLE".equals(status) ? "永久来源占用表未部署/不可确认，不能解释为NONE或自动封存，请保留原号查证"
                : "当前可读永久账未见来源占用；不证明执行资格或未知投递结果，不自动重投");
        return receipt;
    }

    private MesPhysicalTransformCommandEntity command(Identity owner, String requestId) {
        var row = commands.permanent(owner.tenantId(), requestId);
        require(row != null, "原命令不存在，请先按完整原文及原号查证，不改用新请求号");
        assertCommand(row, owner);
        return row;
    }

    private void assertCommand(MesPhysicalTransformCommandEntity row, Identity owner) {
        require(row != null && Objects.equals(row.getTenantId(), owner.tenantId()) && Objects.equals(row.getFactoryCode(), owner.factoryCode())
                && Objects.equals(row.getActorId(), owner.actorId()) && Objects.equals(row.getActorCode(), owner.actorCode())
                && "0".equals(row.getDelFlag()), "原命令不属于当前实际租户/工厂/实名");
        var body = codec.command(row.getCommandSnapshot());
        require(Objects.equals(body.getVersion(), row.getCommandVersion())
                        && Objects.equals(row.getRequestFingerprint(), codec.fingerprint(row.getCommandVersion(), row.getCommandSnapshot()))
                        && owner.tenantId().toString().equals(body.getExpectedTenantId())
                        && owner.factoryCode().equals(body.getExpectedFactoryCode()) && owner.actorId().toString().equals(body.getExpectedActorUserId()),
                "永久原文算法/SHA/声明owner不一致，不能覆盖原历史");
    }

    private void requireAvailable() {
        require(Boolean.TRUE.equals(baseMapper.available()), "请先部署V2永久来源占用表；未部署不解释为NONE");
    }

    private void requireReady() {
        require(Boolean.TRUE.equals(baseMapper.readinessAvailable()) && Boolean.TRUE.equals(baseMapper.ready()),
                "来源持久占用/版本栅栏/提交证明/消费守卫未齐备或被禁用，禁止生命周期写");
    }

    private void requestLock(Identity owner, String requestId) {
        commands.lockRequest(codec.snapshot(List.of(MesPhysicalTransformCodec.REQUEST_LOCK_VERSION, owner.tenantId().toString(), requestId)));
    }

    private Identity identity() {
        String factory = codec.code(factoryAccess.require("write"), "实际工厂", 100);
        var user = SecurityUtils.getUser();
        require(user != null && TenantContextHolder.getTenantId() != null, "来源占用恢复需要实际租户/工厂/实名");
        codec.id(TenantContextHolder.getTenantId().toString());
        codec.id(user.getId().toString());
        return new Identity(TenantContextHolder.getTenantId(), factory, user.getId(), codec.code(user.getUsername(), "实际实名账号", 100));
    }

    /**
     * 写入口禁止外围旧快照；事务不明时不伪造占用成功或自动取消。
     */
    private <T> T independent(Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "来源占用入口禁止嵌入外层业务事务");
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> work.get());
    }

    /**
     * 只读最新原事实，暂停旧外围快照；不拿来源/任务锁、不重建或重投。
     */
    private <T> T latestRead(Supplier<T> work) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(true);
        return tx.execute(status -> work.get());
    }

    private record Identity(Long tenantId, String factoryCode, Long actorId, String actorCode) {
    }
}
