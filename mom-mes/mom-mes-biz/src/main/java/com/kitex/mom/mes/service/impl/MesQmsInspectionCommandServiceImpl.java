package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesQmsInspectionCommandEntity;
import com.kitex.mom.mes.mapper.MesQmsInspectionCommandMapper;
import com.kitex.mom.mes.service.MesQmsInspectionCommandService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * QMS工序质检出站命令的独立事务账。
 *
 * <p>每个阶段都独立提交，避免QMS已创建而MES外层事务回滚后丢失曾发送事实。
 * 远端失败或超时保持OUTBOUND_STARTED，只有沿相同原号重放并取得明确任务主键才完成。</p>
 */
@Service
@RequiredArgsConstructor
public class MesQmsInspectionCommandServiceImpl
        extends ServiceImpl<MesQmsInspectionCommandMapper, MesQmsInspectionCommandEntity>
        implements MesQmsInspectionCommandService {

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MesQmsInspectionCommandEntity register(InspectionProcedureCreateParam command, String factoryCode) {
        Context context = context(command, factoryCode);
        require(Boolean.TRUE.equals(baseMapper.tryRequest(context.tenant(), context.requestId())),
                "工序质检原号正在执行，请沿原号稍后核账");
        MesQmsInspectionCommandEntity existing = read(context);
        if (existing != null) {
            return existing;
        }
        MesQmsInspectionCommandEntity row = new MesQmsInspectionCommandEntity();
        row.setTenantId(context.tenant());
        row.setFactoryCode(context.factoryCode());
        row.setRequestId(context.requestId());
        row.setSourceTaskCode(command.getSourceTaskCode());
        row.setCommandVersion(InspectionProcedureSourceContract.VERSION);
        row.setCommandSnapshot(context.snapshot());
        row.setCommandFingerprint(context.fingerprint());
        row.setStatus("REGISTERED");
        row.setAttemptCount(0);
        row.setDelFlag("0");
        row.setCreateBy("SYSTEM");
        require(baseMapper.insert(row) == 1, "工序质检永久原命令登记失败，未调用QMS");
        MesQmsInspectionCommandEntity saved = read(context);
        require(saved != null && Objects.equals(saved.getId(), row.getId()), "工序质检原命令未完整保存");
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MesQmsInspectionCommandEntity beginAttempt(InspectionProcedureCreateParam command, String factoryCode) {
        Context context = context(command, factoryCode);
        require(Boolean.TRUE.equals(baseMapper.tryRequest(context.tenant(), context.requestId())),
                "工序质检原号正在执行，请勿另号发送");
        MesQmsInspectionCommandEntity row = read(context);
        require(row != null, "工序质检原命令尚未独立登记，禁止调用QMS");
        if ("QMS_CONFIRMED".equals(row.getStatus())) {
            return row;
        }
        require(baseMapper.beginAttempt(row.getId(), context.tenant(), context.fingerprint()) == 1,
                "工序质检发送边界未独立保存，禁止调用QMS");
        return requiredRead(context);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MesQmsInspectionCommandEntity confirm(InspectionProcedureCreateParam command, String factoryCode, Long qmsTaskId) {
        Context context = context(command, factoryCode);
        require(qmsTaskId != null && qmsTaskId > 0, "QMS未明确返回真实质检任务主键");
        require(Boolean.TRUE.equals(baseMapper.tryRequest(context.tenant(), context.requestId())),
                "工序质检原号正在确认，请沿原号重查");
        MesQmsInspectionCommandEntity row = requiredRead(context);
        if ("QMS_CONFIRMED".equals(row.getStatus())) {
            require(Objects.equals(row.getQmsTaskId(), qmsTaskId), "同一质检原号返回了不同QMS任务主键");
            return row;
        }
        require(baseMapper.confirm(row.getId(), context.tenant(), context.fingerprint(), qmsTaskId) == 1,
                "QMS成功回执未永久保存，请保留原号核账");
        MesQmsInspectionCommandEntity saved = requiredRead(context);
        require("QMS_CONFIRMED".equals(saved.getStatus()) && Objects.equals(saved.getQmsTaskId(), qmsTaskId),
                "QMS成功回执回读不一致");
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void recordFailure(InspectionProcedureCreateParam command, String factoryCode, Throwable failure) {
        Context context = context(command, factoryCode);
        if (!Boolean.TRUE.equals(baseMapper.tryRequest(context.tenant(), context.requestId()))) {
            return;
        }
        MesQmsInspectionCommandEntity row = read(context);
        if (row == null || !"OUTBOUND_STARTED".equals(row.getStatus())) {
            return;
        }
        String message = rootMessage(failure);
        require(baseMapper.recordFailure(row.getId(), context.tenant(), context.fingerprint(), message) == 1,
                "工序质检出站异常摘要未保存");
    }

    private MesQmsInspectionCommandEntity requiredRead(Context context) {
        MesQmsInspectionCommandEntity result = read(context);
        require(result != null, "工序质检永久原号不存在");
        return result;
    }

    private MesQmsInspectionCommandEntity read(Context context) {
        MesQmsInspectionCommandEntity row = baseMapper.original(context.tenant(), context.requestId());
        if (row != null) {
            require(Objects.equals(row.getFactoryCode(), context.factoryCode())
                            && Objects.equals(row.getCommandVersion(), InspectionProcedureSourceContract.VERSION)
                            && Objects.equals(row.getCommandSnapshot(), context.snapshot())
                            && Objects.equals(row.getCommandFingerprint(), context.fingerprint()),
                    "工序质检原号的工厂或完整创建参数不一致");
        }
        return row;
    }

    private Context context(InspectionProcedureCreateParam command, String factoryCode) {
        Long tenant = TenantContextHolder.getTenantId();
        require(Boolean.TRUE.equals(baseMapper.installed()), "请先部署V3工序质检永久出站账");
        require(tenant != null && tenant > 0 && factoryCode != null && !factoryCode.isBlank()
                        && factoryCode.length() <= 100 && command != null,
                "工序质检出站须真实租户、工厂和完整参数");
        String requestId = command.getSourceRequestId();
        require(requestId != null && requestId.startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX)
                        && requestId.length() <= 200 && command.getSourceTaskCode() != null
                        && !command.getSourceTaskCode().isBlank() && command.getSourceTaskCode().length() <= 100,
                "新工序质检须使用V2永久原号并指定真实MES工序任务");
        String fingerprint = InspectionProcedureSourceContract.fingerprint(command);
        require(command.getSourceRequestFingerprint() == null
                        || Objects.equals(command.getSourceRequestFingerprint(), fingerprint),
                "工序质检调用参数与已有来源摘要不一致");
        command.setSourceRequestFingerprint(fingerprint);
        String snapshot = InspectionProcedureSourceContract.snapshot(command);
        require(snapshot.length() <= 65536, "工序质检完整原命令超过64Ki字符");
        return new Context(tenant, factoryCode, requestId, snapshot, fingerprint);
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure == null ? null : failure;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? (current == null ? "未知远端异常" : current.getClass().getSimpleName()) : current.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private record Context(Long tenant, String factoryCode, String requestId, String snapshot, String fingerprint) {
    }
}
