package com.kitex.mom.mes.process.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流转卡报工实现。多流转卡绑定、工单归属和逐卡工序顺序由统一过站防错门管理。
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class TransferCardProcessOperation extends AbstractProcessOperation {

    public TransferCardProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService) {
        super(liveStockService, remoteParamService, matService, feedService);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesTaskReportResult execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("请扫描流转卡");
        }
        MesTaskReportEntity report = createReport(params, currentUser);
        // 4. 返回处理结果
        String reportMessage = operatorName(params, currentUser) + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行流转卡报工，合格数量为：" + report.getReportNum();
        return new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
    }

    @Override
    public ReportModeEnum getMode() {
        return ReportModeEnum.TRANSFER_CARD;
    }
}
