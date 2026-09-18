package com.kitex.mom.mes.process.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWrapService;
import com.kitex.mom.wms.api.feign.RemoteContainerBarcodeService;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 包装+数量报工实现。每次容器扫码形成独立包装主档及内容履历，不在派工单上覆盖箱码。
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageQuantityProcessOperation extends AbstractProcessOperation {

    private final MesWrapService wrapService;
    private final RemoteContainerBarcodeService containerBarcodeService;

    public PackageQuantityProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService, MesWrapService wrapService, RemoteContainerBarcodeService containerBarcodeService) {
        super(liveStockService, remoteParamService, matService, feedService);
        this.wrapService = wrapService;
        this.containerBarcodeService = containerBarcodeService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesTaskReportResult execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 实现包装+数量报工逻辑
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("箱码不能为空");
        }
        MesWrapEntity wrap = wrapService.getOne(new LambdaQueryWrapper<MesWrapEntity>()
                .eq(MesWrapEntity::getWrapCode, params.getTransferCode()));
        WmsContainerBarcodeEntity barcode = containerBarcodeService.queryBarcode(params.getTransferCode()).getData();
        if (Objects.isNull(wrap) && Objects.isNull(barcode)) {
            throw new RuntimeException("箱码不存在，报工失败");
        }
        if (Objects.nonNull(barcode)) {
            if (Objects.equals(barcode.getIsUsed(), 1)) {
                throw new RuntimeException("该容器码已被占用，请扫描其他容器码");
            }
        }
        if (Objects.nonNull(wrap)) {
            throw new RuntimeException("该箱码已形成生产包装，不能重复占用");
        }
        // 2. 创建并保存报工记录
        MesTaskReportEntity report = createReport(params, currentUser);
        params.setReportId(report.getId());
        // 3. 建立独立包装主档与可追溯内容关系
        MesWrapEntity productionWrap = new MesWrapEntity()
                .setWrapCode(params.getTransferCode())
                .setRuleCode(params.getWrapRuleCode())
                .setWrapCapacity(params.getCapacity())
                .setTaskCode(params.getTaskCode())
                .setProcedureTaskCode(params.getProduceTaskCode())
                .setArrangeCode(params.getArrangeCode())
                .setWrapType(params.getTaskType())
                .setFactoryCode(params.getFactoryCode());
        wrapService.registerQuantityPackage(productionWrap, report.getId(), params.getTransferCode(), params.getPassNum());
        // 4. 返回处理结果
        String reportMessage = operatorName(params, currentUser) + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行箱码扫描数量报工，箱码：" + params.getTransferCode() + "，装箱数量：" + report.getReportNum();
        return new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
    }


    @Override
    public ReportModeEnum getMode() {
        return ReportModeEnum.PACKAGE_QUANTITY;
    }
}
