package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesInspectionRecordWorkbenchDTO.*;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.mapper.MesInspectionGenRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Bounded read-only record view; production allowance remains in the real quality gate. */
@Service
@RequiredArgsConstructor
public class MesInspectionRecordQuery {
    private static final String CONTRACT = "MES_INSPECTION_RECORD_WORKBENCH_V1";
    private final MesInspectionGenRecordMapper records;
    private final MesActualFactoryAccessService access;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageView page(Query input) {
        String factory = access.require("read");
        Query q = normalize(input);
        Long tenant = TenantContextHolder.getTenantId();
        Long total = records.workbenchCount(tenant, factory, q);
        require(total != null && total >= 0, "质检记录总量不可读取");
        var rows = records.workbenchRows(tenant, factory, q, ((long)q.getCurrent() - 1) * q.getSize(), q.getSize());
        require(rows != null && rows.size() <= q.getSize() && rows.size() <= total, "质检记录分页不可读取或超限");
        var ids = new HashSet<Long>();
        for (var row : rows) {
            require(row != null && ids.add(row.getId()), "质检记录分页缺件或重复");
            scoped(row, tenant, factory);
        }
        return new PageView(CONTRACT, tenant.toString(), factory, actor(), LocalDateTime.now(), q,
                total.toString(), rows.stream().map(this::view).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(Long id) {
        String factory = access.require("read");
        require(id != null && id > 0, "质检记录编号无效");
        Long tenant = TenantContextHolder.getTenantId();
        var row = records.workbenchDetail(tenant, factory, id);
        require(row != null && Objects.equals(row.getId(), id), "当前组织不存在指定质检记录");
        scoped(row, tenant, factory);
        return new Detail(CONTRACT, tenant.toString(), factory, actor(), LocalDateTime.now(), view(row));
    }

    private Query normalize(Query input) {
        require(input != null && input.getCurrent() != null && input.getCurrent() >= 1 && input.getCurrent() <= 100000
                && input.getSize() != null && input.getSize() >= 1 && input.getSize() <= 100, "质检记录分页范围无效");
        Query q = new Query().setCurrent(input.getCurrent()).setSize(input.getSize())
                .setTaskCode(code(input.getTaskCode())).setProduceTaskCode(code(input.getProduceTaskCode()))
                .setInspectionType(code(input.getInspectionType())).setGateStatus(code(input.getGateStatus()))
                .setGenerationStatus(Objects.requireNonNullElse(code(input.getGenerationStatus()), "ALL"));
        require(q.getInspectionType() == null || Set.of("FIRST", "MIDDLE", "LAST").contains(q.getInspectionType()), "检验阶段无效");
        require(q.getGateStatus() == null || Set.of("PENDING", "PASSED", "FAILED", "CANCELED", "ERROR").contains(q.getGateStatus()), "同步状态无效");
        require(Set.of("ALL", "GENERATED", "NOT_GENERATED").contains(q.getGenerationStatus()), "生成状态无效");
        return q;
    }
    private String code(String raw) {
        if (raw == null) return null;
        require(raw.length() <= 200 && raw.chars().noneMatch(Character::isISOControl), "质检记录编码长度或格式无效");
        return raw.isBlank() ? null : raw.trim();
    }
    private void scoped(MesInspectionGenRecordEntity row, Long tenant, String factory) {
        require(row.getId() != null && row.getId() > 0 && Objects.equals(row.getTenantId(), tenant)
                && Objects.equals(row.getFactoryCode(), factory), "质检记录不属于当前实际组织");
    }
    private static String actor() { return SecurityUtils.getUser().getId().toString(); }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private Row view(MesInspectionGenRecordEntity r) {
        return new Row(
                    text(r.getId()),
                    text(r.getTenantId()),
                    text(r.getFactoryCode()),
                    text(r.getTaskCode()),
                    text(r.getProduceTaskCode()),
                    text(r.getRuleCode()),
                    text(r.getRuleName()),
                    text(r.getInspectionId()),
                    text(r.getInspectionType()),
                    text(r.getSourceRequestId()),
                    text(r.getGenFlag()),
                    text(r.getGenRemark()),
                    text(r.getGenTime()),
                    text(r.getInspectionStatus()),
                    text(r.getInspectionResult()),
                    text(r.getGateStatus()),
                    text(r.getQmsTotalNum()),
                    text(r.getQmsUsableNum()),
                    text(r.getQmsHandleNum()),
                    text(r.getQmsHandleResult()),
                    text(r.getBlockedCount()),
                    text(r.getSyncMessage()),
                    text(r.getLastSyncTime()),
                    text(r.getLastBlockedTime()));
    }
    private static void require(boolean valid, String message) { if (!valid) throw new CheckedException(message); }
}
