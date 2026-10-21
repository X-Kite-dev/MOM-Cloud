package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesTaskInWmsDTO;
import com.kitex.mom.mes.entity.MesTaskInWmsCommandEntity;
import com.kitex.mom.mes.mapper.MesTaskInWmsCommandMapper;
import com.kitex.mom.mes.mapper.MesTaskInWmsReceiptMapper;
import com.kitex.mom.mes.service.MesTaskInWmsCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 三个独立阶段：登记原号、提交发送标记、原号核账。
 * 入库事务持有原请求锁时，markOutbound只更新已登记的行，不再次取该请求锁，避免自等待。
 * OUTBOUND_STARTED永不按超时回退；取消仅在原请求锁内确认未开始发送且无MES回执。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTask:update')")
public class MesTaskInWmsCommandServiceImpl
        extends ServiceImpl<MesTaskInWmsCommandMapper, MesTaskInWmsCommandEntity>
        implements MesTaskInWmsCommandService {
    public static final String VERSION = "MES_TASK_IN_WMS_COMMAND_V2";
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformCodec codec;
    private final MesTaskInWmsReceiptMapper receipts;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MesTaskInWmsCommandEntity register(MesTaskInWmsDTO command) {
        Context context = context(command);
        require(Boolean.TRUE.equals(baseMapper.tryRequest(command.getRequestId())), "原入库号仍在执行，请只读核账");
        MesTaskInWmsCommandEntity existing = read(command, context);
        if (existing != null) return existing;
        require(receipts.permanentRequest(context.tenant(), command.getRequestId()) == null,
                "原入库号已存在旧回执，不能补造V2发送历史");
        MesTaskInWmsCommandEntity row = new MesTaskInWmsCommandEntity();
        row.setTenantId(context.tenant());
        row.setFactoryCode(context.factory());
        row.setActorId(context.actor());
        row.setRequestId(command.getRequestId());
        row.setCommandVersion(VERSION);
        row.setCommandSnapshot(context.raw());
        row.setCommandFingerprint(codec.fingerprint(VERSION, context.raw()));
        row.setStatus("REGISTERED");
        require(baseMapper.insert(row) == 1, "原入库命令登记失败，未启动业务");
        MesTaskInWmsCommandEntity saved = read(command, context);
        require(saved != null && Objects.equals(saved.getId(), row.getId()), "原入库命令未完整保存");
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public MesTaskInWmsCommandEntity original(MesTaskInWmsDTO command) {
        return read(command, context(command));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void markOutbound(MesTaskInWmsDTO command) {
        Context context = context(command);
        MesTaskInWmsCommandEntity row = read(command, context);
        require(row != null && !"CANCELLED_BEFORE_DISPATCH".equals(row.getStatus()), "原命令未登记或已安全结束，禁止发送");
        if ("OUTBOUND_STARTED".equals(row.getStatus())) return;
        require(baseMapper.transition(row.getId(), context.tenant(), "REGISTERED", "OUTBOUND_STARTED") == 1,
                "发送边界未独立保存，禁止调用远端");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void cancelIfUnsent(MesTaskInWmsDTO command) {
        Context context = context(command);
        if (!Boolean.TRUE.equals(baseMapper.tryRequest(command.getRequestId()))) return;
        MesTaskInWmsCommandEntity row = read(command, context);
        if (row == null || !"REGISTERED".equals(row.getStatus())
                || receipts.permanentRequest(context.tenant(), command.getRequestId()) != null) return;
        require(baseMapper.transition(row.getId(), context.tenant(), "REGISTERED", "CANCELLED_BEFORE_DISPATCH") == 1,
                "未发送终态未保存，请继续保留原号");
    }

    private MesTaskInWmsCommandEntity read(MesTaskInWmsDTO command, Context context) {
        MesTaskInWmsCommandEntity row = baseMapper.original(context.tenant(), command.getRequestId());
        if (row != null) {
            require(Objects.equals(row.getFactoryCode(), context.factory()) && Objects.equals(row.getActorId(), context.actor())
                            && VERSION.equals(row.getCommandVersion()) && Objects.equals(row.getCommandSnapshot(), context.raw())
                            && Objects.equals(row.getCommandFingerprint(), codec.fingerprint(VERSION, context.raw())),
                    "原入库号的本人、工厂或完整原文不一致");
        }
        return row;
    }

    private Context context(MesTaskInWmsDTO command) {
        String factory = access.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        Long actor = SecurityUtils.getUser().getId();
        require(command != null && VERSION.equals(command.getCommandVersion()) && tenant != null && actor != null
                        && tenant.toString().equals(command.getExpectedTenantId()) && actor.toString().equals(command.getExpectedActorId())
                        && factory.equals(command.getExpectedFactoryCode()), "入库命令必须绑定实际认证本人、租户和工厂");
        require(command.getRequestId() != null && command.getRequestId().matches("IW2:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        && command.getId() != null && command.getId() > 0 && command.getInWmsNum() != null
                        && command.getInWmsNum().signum() > 0, "V2入库须新协议原号、真实工单与正数数量");
        require(command.getWarehouseCode() != null && !command.getWarehouseCode().isBlank()
                        && command.getWarehouseCode().length() <= 100 && command.getWarehouseName() != null
                        && !command.getWarehouseName().isBlank() && command.getWarehouseName().length() <= 200
                        && command.getRemark() != null && command.getRemark().length() <= 2000
                        && command.getExpectedSourceFingerprint() != null
                        && command.getExpectedSourceFingerprint().matches("[0-9a-f]{64}")
                        && command.getInWmsNum().toPlainString().matches("(0|[1-9][0-9]{0,19})(\\.[0-9]{1,8})?"),
                "原仓库、名称、备注、数量精度或来源指纹不符合入库命令合同");
        require(Boolean.TRUE.equals(baseMapper.installed()), "请先部署V2成品入库永久命令升级及保护目录");
        TreeMap<String, Object> values = new TreeMap<>();
        values.put("commandVersion", VERSION);
        values.put("expectedTenantId", tenant.toString());
        values.put("expectedFactoryCode", factory);
        values.put("expectedActorId", actor.toString());
        values.put("requestId", command.getRequestId());
        values.put("id", command.getId().toString());
        values.put("inWmsNum", command.getInWmsNum().toPlainString());
        values.put("warehouseCode", command.getWarehouseCode());
        values.put("warehouseName", command.getWarehouseName());
        values.put("remark", command.getRemark());
        values.put("expectedSourceFingerprint", command.getExpectedSourceFingerprint());
        String raw = codec.snapshot(values);
        require(raw.length() <= 65536, "完整入库原文不能超过64Ki字符");
        return new Context(tenant, factory, actor, raw);
    }

    private record Context(Long tenant, String factory, Long actor, String raw) {
    }
}
