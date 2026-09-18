package com.kitex.mom.mes.process.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 流转卡报工实现
 * 逻辑错误，一个生产工单会有多个流转卡。由第一个工序进行绑定流转卡，后续工序扫码时需要校验流转卡的工序位置和工单
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class TransferCardProcessOperation extends AbstractProcessOperation {

    private final MesTaskService mesTaskService;

    public TransferCardProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService, MesTaskService mesTaskService) {
        super(liveStockService, remoteParamService, matService, feedService);
        this.mesTaskService = mesTaskService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesTaskReportResult execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("请扫描流转卡");
        }
        // 2. 获取流转卡信息
        // 流转卡是否绑定其他工单 如果绑定了，则无法流转 报工失败
        List<MesTaskEntity> bindList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTransferCode, params.getTransferCode()));
        // 如果没有绑定过 直接绑定
        if (CollUtil.isEmpty(bindList)) {
            MesTaskEntity mesTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, params.getTaskCode()));
            if (StrUtil.isNotEmpty(mesTask.getTransferCode())) {
                throw new RuntimeException(String.format("该任务已绑定流转卡[%s]，请勿重复绑定", mesTask.getTransferCode()));
            }
            mesTask.setTransferCode(params.getTransferCode());
            mesTaskService.updateById(mesTask);
        } else {
            // 如果已经绑定了且是这个工单的 则允许报工
            MesTaskEntity mesTask = bindList.stream().filter(item -> item.getCode().equals(params.getTaskCode())).findFirst().orElse(null);
            if (Objects.isNull(mesTask)) {
                throw new RuntimeException("该流转卡已绑定其他工单，报工失败");
            }
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
