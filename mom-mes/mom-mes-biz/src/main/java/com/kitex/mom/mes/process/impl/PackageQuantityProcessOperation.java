package com.kitex.mom.mes.process.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWrapService;
import com.kitex.mom.wms.api.feign.RemoteContainerBarcodeService;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 包装+数量报工实现
 * 逻辑错误，生产包工的箱码为什么会在排工单上。这样会导致一个派工单只有一个箱码。或者流转卡
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageQuantityProcessOperation extends AbstractProcessOperation {

    private static final String CODE = "PACKAGE_QUANTITY";

    private final MesTaskArrangeService arrangeService;
    private final MesWrapService wrapService;
    private final RemoteContainerBarcodeService containerBarcodeService;

    public PackageQuantityProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService, MesTaskArrangeService arrangeService, MesWrapService wrapService, RemoteContainerBarcodeService containerBarcodeService) {
        super(liveStockService, remoteParamService, matService, feedService);
        this.arrangeService = arrangeService;
        this.wrapService = wrapService;
        this.containerBarcodeService = containerBarcodeService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesTaskReportVO params) {
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
        // 2. 创建并保存报工记录
        MesTaskReportEntity report = createAndSaveReport(params, currentUser);
        // 3. 冗余绑定箱码
        arrangeService.update(new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode())
                .set(MesTaskArrangeEntity::getWrapCode, params.getTransferCode()));
        // 4. 返回处理结果
        Map<String, Object> resultMap = new HashMap<>(2);
        String reportMessage = currentUser.getName() + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行箱码扫描数量报工，箱码：" + params.getWrapCode() + "，装箱数量：" + report.getReportNum();
        resultMap.put("report", report);
        resultMap.put("reportMessage", reportMessage);
        return resultMap;
    }

    /**
     * 创建并保存报工记录
     *
     * @param vo 报工VO对象
     * @return 报工记录
     */
    private MesTaskReportEntity createAndSaveReport(MesTaskReportVO vo, MomUser currentUser) {
        MesTaskReportEntity report = new MesTaskReportEntity();
        report.setArrangeCode(vo.getArrangeCode());
        report.setTaskCode(vo.getTaskCode());
        report.setProduceTaskCode(vo.getProduceTaskCode());
        report.setMatCode(vo.getMatCode());
        report.setMatName(vo.getMatName());
        report.setMatSpec(vo.getMatSpec());
        report.setTransferCode(vo.getTransferCode());
        report.setReportNum(vo.getPassNum());
        report.setType(vo.getType());
        report.setReportTime(LocalDateTime.now());
        report.setWorkerCode(currentUser.getUsername());
        report.setWorkerName(currentUser.getName());
        report.setDeviceCode(vo.getDeviceCode());
        report.setDeviceName(vo.getDeviceName());
        return report;
    }


    @Override
    public String getCode() {
        return CODE;
    }
} 