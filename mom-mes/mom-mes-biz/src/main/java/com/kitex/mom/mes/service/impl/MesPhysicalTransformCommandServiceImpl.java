package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;
import com.kitex.mom.mes.mapper.MesPhysicalTransformCommandMapper;
import com.kitex.mom.mes.service.MesPhysicalTransformCommandService;
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
 * 完整原正文传输永久协议；REGISTERED只是已保存，尚未取得转换工艺/库存资格。
 * 此处没有execute或自动重投；后续领域事务必须消费同一原文/版本及原号锁。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalTransformCommandServiceImpl
        extends ServiceImpl<MesPhysicalTransformCommandMapper, MesPhysicalTransformCommandEntity>
        implements MesPhysicalTransformCommandService {

    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;
    private final MesPhysicalTransformSourceHoldService sourceHolds;

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalTransformCommandDTO.Receipt register(String requestId, String rawJson) {
        codec.code(requestId, "转换永久请求号", 100);
        var body = codec.command(rawJson);
        Identity owner = identity();
        require(owner.tenantId().toString().equals(body.getExpectedTenantId())
                && owner.factoryCode().equals(body.getExpectedFactoryCode())
                && owner.actorId().toString().equals(body.getExpectedActorUserId()), "登录租户/工厂/实名已变化，不能登记旧页面原命令");
        String fingerprint = codec.fingerprint(body.getVersion(), rawJson);
        return independent(() -> {
            lock(owner, requestId);
            var row = baseMapper.permanent(owner.tenantId(), requestId);
            if (row != null) {
                assertOwner(row, owner);
                assertOriginal(row, rawJson, fingerprint);
                return receiptOf(row);
            }
            row = new MesPhysicalTransformCommandEntity().setFactoryCode(owner.factoryCode()).setRequestId(requestId)
                    .setCommandVersion(body.getVersion()).setRequestFingerprint(fingerprint)
                    .setCommandSnapshot(rawJson).setActorId(owner.actorId()).setActorCode(owner.actorCode())
                    .setStatus("REGISTERED").setRegisteredTime(LocalDateTime.now());
            row.setTenantId(owner.tenantId());
            row.setCreateBy(owner.actorCode());
            row.setDelFlag("0");
            require(baseMapper.insert(row) == 1, "完整转换原命令登记失败，禁止后续执行");
            var stored = baseMapper.permanent(owner.tenantId(), requestId);
            require(stored != null && Objects.equals(row.getId(), stored.getId()) && "REGISTERED".equals(stored.getStatus()),
                    "转换原命令必要写回读不完整");
            assertOwner(stored, owner);
            assertOriginal(stored, rawJson, fingerprint);
            return receiptOf(stored);
        });
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesPhysicalTransformCommandDTO.Receipt receipt(String requestId) {
        codec.code(requestId, "转换永久请求号", 100);
        Identity owner = identity();
        return latestRead(() -> {
            var row = baseMapper.permanent(owner.tenantId(), requestId);
            if (row == null) {
                var absent = new MesPhysicalTransformCommandDTO.Receipt();
                absent.setRequestId(requestId);
                absent.setTenantId(owner.tenantId().toString());
                absent.setFactoryCode(owner.factoryCode());
                absent.setActorUserId(owner.actorId().toString());
                absent.setActorCode(owner.actorCode());
                absent.setStatus("ABSENT");
                absent.setExecutionAvailable(false);
                absent.setMessage("独立查询未查到永久原事实，版本保持空；不证明未投递或已转换，保留完整原文原号核对");
                return absent;
            }
            assertOwner(row, owner);
            return receiptOf(row);
        });
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesPhysicalTransformCommandDTO.Receipt seal(String requestId, String rawJson) {
        codec.code(requestId, "转换永久请求号", 100);
        var input = codec.seal(rawJson);
        Identity owner = identity();
        return independent(() -> {
            lock(owner, requestId);
            var row = baseMapper.locked(owner.tenantId(), requestId);
            require(row != null, "原命令不存在，请先按完整原文和原号核对登记；不据ABSENT销账");
            assertOwner(row, owner);
            require(row.getRequestFingerprint().equals(input.getExpectedFingerprint()), "封存指纹与原正文不一致");
            if ("SEALED".equals(row.getStatus())) {
                require(Objects.equals(row.getSealReason(), input.getReason()), "同原号已有另一封存原因，不能覆盖原历史");
                return receiptOf(row);
            }
            require("REGISTERED".equals(row.getStatus()), "原号不在未执行登记状态，禁止封存执行中或未知事务");
            var hold = sourceHolds.attachedReceipt(row);
            require(!"SOURCE_HELD".equals(hold.getStatus()) && !"UNAVAILABLE".equals(hold.getStatus()),
                    "必须先核实本人来源占用并显式取消；旧seal不能释放活动占用，未部署也不能猜未占用");
            require(baseMapper.seal(row.getId(), owner.tenantId(), owner.factoryCode(), owner.actorId(), owner.actorCode(),
                    input.getExpectedFingerprint(), LocalDateTime.now(), input.getReason()) == 1, "永久封存必要写未恰好一行");
            var stored = baseMapper.permanent(owner.tenantId(), requestId);
            require(stored != null && "SEALED".equals(stored.getStatus()) && stored.getSealedTime() != null
                    && Objects.equals(stored.getSealReason(), input.getReason()), "永久封存回读不完整");
            assertOwner(stored, owner);
            assertOriginal(stored, row.getCommandSnapshot(), row.getRequestFingerprint());
            return receiptOf(stored);
        });
    }

    /**
     * 所有原命令版本与将来claim/execute共用旧字面域，不按factory/version另开原号。
     */
    private void lock(Identity owner, String requestId) {
        baseMapper.lockRequest(codec.snapshot(List.of(MesPhysicalTransformCodec.REQUEST_LOCK_VERSION,
                owner.tenantId().toString(), requestId)));
    }

    /**
     * 提交响应不明只查原号；此事务失败不能包装成SEALED或转换成功。
     */
    private <T> T independent(Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "转换传输原账入口禁止嵌入外层业务事务");
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> work.get());
    }

    /**
     * 暂停外围事务的旧快照，只读当前永久事实；无锁/重建/重投或当前来源状态推断。
     */
    private <T> T latestRead(Supplier<T> work) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
        return transaction.execute(status -> work.get());
    }

    private Identity identity() {
        String factory = codec.code(factoryAccess.require("write"), "实际工厂", 100);
        var user = SecurityUtils.getUser();
        codec.id(TenantContextHolder.getTenantId().toString());
        codec.id(user.getId().toString());
        String actor = codec.code(user.getUsername(), "实际实名账号", 100);
        return new Identity(TenantContextHolder.getTenantId(), factory, user.getId(), actor);
    }

    private void assertOwner(MesPhysicalTransformCommandEntity row, Identity owner) {
        require(Objects.equals(row.getTenantId(), owner.tenantId()) && Objects.equals(row.getFactoryCode(), owner.factoryCode())
                && Objects.equals(row.getActorId(), owner.actorId()) && Objects.equals(row.getActorCode(), owner.actorCode())
                && "0".equals(row.getDelFlag()), "该永久原号不属于当前租户/工厂/实名，或原历史已损坏");
    }

    private void assertOriginal(MesPhysicalTransformCommandEntity row, String rawJson, String fingerprint) {
        require(row.getCommandVersion() != null && List.of(MesPhysicalTransformCodec.COMMAND_VERSION,
                        MesPhysicalTransformCodec.COMMAND_VERSION_V2).contains(row.getCommandVersion())
                        && fingerprint != null && fingerprint.matches("[0-9a-f]{64}")
                        && Objects.equals(row.getCommandSnapshot(), rawJson) && Objects.equals(row.getRequestFingerprint(), fingerprint)
                        && fingerprint.equals(codec.fingerprint(row.getCommandVersion(), row.getCommandSnapshot())),
                "原号已经登记另一原文/版本或指纹损坏，不能重新序列化覆盖");
        require(row.getCommandVersion().equals(codec.command(rawJson).getVersion()), "永久原文声明的版本与原账不一致");
    }

    private MesPhysicalTransformCommandDTO.Receipt receiptOf(MesPhysicalTransformCommandEntity row) {
        assertOriginal(row, row.getCommandSnapshot(), row.getRequestFingerprint());
        var receipt = new MesPhysicalTransformCommandDTO.Receipt();
        receipt.setCommandId(row.getId().toString());
        receipt.setCommandVersion(row.getCommandVersion());
        receipt.setRequestId(row.getRequestId());
        receipt.setTenantId(row.getTenantId().toString());
        receipt.setFactoryCode(row.getFactoryCode());
        receipt.setActorUserId(row.getActorId().toString());
        receipt.setActorCode(row.getActorCode());
        receipt.setRequestFingerprint(row.getRequestFingerprint());
        receipt.setCommandSnapshot(row.getCommandSnapshot());
        receipt.setStatus(row.getStatus());
        receipt.setRegisteredTime(row.getRegisteredTime().toString());
        receipt.setSealedTime(row.getSealedTime() == null ? null : row.getSealedTime().toString());
        receipt.setSealReason(row.getSealReason());
        receipt.setSourceHold(sourceHolds.attachedReceipt(row));
        receipt.setExecutionAvailable("APPLIED".equals(row.getStatus()));
        receipt.setMessage("SEALED".equals(row.getStatus()) ? "原命令已永久封存，原号不可再用；没有库存转换或产量逆向"
                : "APPLIED".equals(row.getStatus()) ? "原命令已有正式转换结果；请读取execution回执核对精确投入、输出、分配和位置消费"
                : "SOURCE_HELD".equals(receipt.getSourceHold().getStatus())
                ? "完整原文及来源占用原账已保存；未转换库存或记产，不能据此开放执行；取消使用本人来源占用入口"
                : "完整原文已永久保存；来源占用以附带原账状态为准，真实资格/转换事务/下游与三端继续建设");
        return receipt;
    }

    private record Identity(Long tenantId, String factoryCode, Long actorId, String actorCode) {
    }
}
