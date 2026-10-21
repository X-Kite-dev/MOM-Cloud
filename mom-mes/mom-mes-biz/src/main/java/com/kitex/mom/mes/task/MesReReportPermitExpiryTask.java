package com.kitex.mom.mes.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.mapper.MesReportReverseRequestMapper;
import com.kitex.mom.mes.service.impl.MesReReportPermitExpiryWorker;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 周期关闭已到期但尚未用完的聚合报工重报许可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesReReportPermitExpiryTask {

    private final MesReportReverseRequestMapper requestMapper;
    private final MesReReportPermitExpiryWorker worker;

    @XxlJob("mes_re_report_permit_expire")
    public void expire() {
        LocalDateTime observedTime = LocalDateTime.now();
        List<MesReportReverseRequestEntity> due = requestMapper.selectPage(new Page<>(1, 200),
                new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                        .eq(MesReportReverseRequestEntity::getStatus, "APPROVED")
                        .eq(MesReportReverseRequestEntity::getReReportPermitStatus, "ACTIVE")
                        .le(MesReportReverseRequestEntity::getReReportExpireTime, observedTime)
                        .orderByAsc(MesReportReverseRequestEntity::getReReportExpireTime)
                        .orderByAsc(MesReportReverseRequestEntity::getId)).getRecords();
        int expired = 0;
        for (MesReportReverseRequestEntity request : due) {
            try {
                boolean changed = request.getTenantId() == null
                        ? worker.expireOne(request.getId(), observedTime)
                        : TenantBroker.applyAs(request.getTenantId(),
                        ignored -> worker.expireOne(request.getId(), observedTime));
                if (changed) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                log.error("受控重报许可到期处理失败，requestId={}", request.getId(), exception);
            }
        }
        log.info("受控重报许可到期扫描完成，扫描数量={}，关闭数量={}", due.size(), expired);
    }
}
