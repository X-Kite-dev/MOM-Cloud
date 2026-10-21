package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesReportRequestCloseEntity;
import com.kitex.mom.mes.mapper.MesReportCommandMapper;
import com.kitex.mom.mes.mapper.MesReportRequestCloseMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesReportCommandService;
import com.kitex.mom.mes.service.MesReportRequestCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;

/** 原号封存与成功报工互斥；不读取当前夹具关系或修改生产数量。 */
@Service
@RequiredArgsConstructor
public class MesReportRequestCloseServiceImpl extends ServiceImpl<MesReportRequestCloseMapper, MesReportRequestCloseEntity>
        implements MesReportRequestCloseService {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesReportCommandMapper commandMapper;
    private final MesTaskReportMapper reportMapper;

    @Override
    @Transactional(readOnly = true)
    public MesReportRequestCloseEntity findOwn(String requestId) {
        String factory = factoryAccess.require("read");
        MesReportRequestCloseEntity row = baseMapper.findPermanent(TenantContextHolder.getTenantId(), requestId);
        require(row == null || (Objects.equals(row.getFactoryCode(), factory)
                && Objects.equals(row.getActorId(), SecurityUtils.getUser().getId())),
                "当前工厂下未找到本人原号封存证据");
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesReportRequestCloseEntity seal(MesReportCommandService.CapturedCommand captured, String reason) {
        String factory = factoryAccess.require("write");
        var actor = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        require(captured != null && Objects.equals(captured.tenantId(), tenant)
                && Objects.equals(captured.factoryCode(), factory) && Objects.equals(captured.actorId(), actor.getId())
                && Objects.equals(captured.actorCode(), actor.getUsername()) && captured.replayReportId() == null
                && List.of("STATION", "MOBILE").contains(captured.channel()), "原号封存归属或入口不正确");
        require(reason != null && !reason.isBlank() && reason.length() <= 500, "请填写不超过500字符的原号封存原因");
        String normalizedReason = reason.trim();
        commandMapper.lockRequest(JSONUtil.toJsonStr(List.of("MES_REPORT_REQUEST_LOCK_V1", tenant.toString(), captured.requestId())));
        commandMapper.lockRequest("MES_REPORT_REQUEST_LOCK_V2:" + tenant + ":"
                + HexFormat.of().formatHex(captured.requestId().getBytes(StandardCharsets.UTF_8)));
        require(commandMapper.findPermanent(tenant, captured.requestId()) == null
                && reportMapper.countHistoricalRequest(tenant, captured.requestId()) == 0,
                "原号已有生产事实或未知历史，不能封存为未受理，请查询原结果");
        MesReportRequestCloseEntity existing = findOwn(captured.requestId());
        if (existing != null) {
            require(Objects.equals(existing.getCommandSnapshot(), captured.snapshot())
                    && Objects.equals(existing.getRequestFingerprint(), captured.fingerprint())
                    && Objects.equals(existing.getChannel(), captured.channel())
                    && Objects.equals(existing.getReason(), normalizedReason), "封存原号的正文或原因已变化，禁止覆盖原证据");
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        String arrangeCode = JSONUtil.parseObj(captured.snapshot()).getStr("arrangeCode");
        require(arrangeCode != null && !arrangeCode.isBlank() && arrangeCode.length() <= 100, "封存原命令缺少精确派工号");
        MesReportRequestCloseEntity row = new MesReportRequestCloseEntity().setFactoryCode(factory)
                .setRequestId(captured.requestId()).setCommandVersion(captured.version())
                .setRequestFingerprint(captured.fingerprint()).setCommandSnapshot(captured.snapshot())
                .setChannel(captured.channel()).setArrangeCode(arrangeCode).setActorId(actor.getId())
                .setActorCode(actor.getUsername()).setStatus("CLOSED_UNACCEPTED")
                .setReason(normalizedReason).setClosedTime(now);
        row.setTenantId(tenant);
        row.setCreateBy(actor.getUsername());
        row.setCreateTime(now);
        row.setDelFlag("0");
        require(baseMapper.insert(row) == 1, "原号封存保存失败，不能清除终端原号");
        return row;
    }

    /** 永久证据只能通过领域入口追加，禁止所有通用增删改。 */
    @Override
    public MesReportRequestCloseMapper getBaseMapper() {
        throw new CheckedException("原号封存账禁止通用CRUD");
    }

    @Override
    public boolean saveBatch(Collection<MesReportRequestCloseEntity> rows, int size) {
        throw new CheckedException("原号封存账禁止通用批量追加");
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<MesReportRequestCloseEntity> rows, int size) {
        throw new CheckedException("原号封存账禁止覆盖");
    }

    @Override
    public boolean updateBatchById(Collection<MesReportRequestCloseEntity> rows, int size) {
        throw new CheckedException("原号封存账禁止更新");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
