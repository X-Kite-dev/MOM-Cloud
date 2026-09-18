package com.kitex.mom.mes.process.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.feign.RemoteCustomerPartService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.enums.MesWrapRecordStatusEnum;
import com.kitex.mom.mes.enums.MesWrapStatusEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWrapRecordService;
import com.kitex.mom.mes.service.MesWrapService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 包装+一物一码报工实现
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageOneCodeProcessOperation extends AbstractProcessOperation {

    private final RemoteCustomerPartService partService;
    private final MesWrapService mesWrapService;
    private final MesWrapRecordService wrapRecordService;

    public PackageOneCodeProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService, RemoteCustomerPartService partService, MesWrapService mesWrapService, MesWrapRecordService wrapRecordService) {
        super(liveStockService, remoteParamService, matService, feedService);
        this.partService = partService;
        this.mesWrapService = mesWrapService;
        this.wrapRecordService = wrapRecordService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesTaskReportResult execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("报工条码不能为空");
        }
        // 2. 一物一码校验
        validateOneCode(params.getTransferCode(), params.getMatCode());
        // 3. 预生成报工主记录ID，包装明细和后续库存流水统一关联该报工。
        MesTaskReportEntity report = createReport(params, currentUser);
        params.setReportId(report.getId());
        // 4. 保存装箱记录（保存一物一码条码）
        createWrapRecord(params);
        // 5. 扫码数量达到最大容量后生成箱码
        String wrapCode = createNewWrapCode(params);
        // 6. 返回处理结果
        String reportMessage = operatorName(params, currentUser) + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行包装装箱报工，合格数量为：" + report.getReportNum();
        MesTaskReportResult result = new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
        if (StrUtil.isNotEmpty(wrapCode)) {
            String wrapMessage = operatorName(params, currentUser) + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "完成装箱，生成箱码：" + wrapCode + "，容量：" + params.getCapacity();
            result.setWrapMessage(wrapMessage);
        }
        return result;
    }

    /**
     * 验证一物一码
     *
     * @param transferCode 流转码
     * @param matCode      物料编码
     */
    private void validateOneCode(String transferCode, String matCode) {
        List<MdmCustomerPartEntity> partList = partService.getPartCodeByMatCode(matCode).getData();
        if (CollUtil.isEmpty(partList)) {
            throw new RuntimeException("未匹配到条码信息");
        }

        boolean matchRegular = partList.stream()
                .anyMatch(part -> StrUtil.isNotEmpty(part.getRegular()) &&
                        transferCode.matches(part.getRegular()));

        if (!matchRegular) {
            throw new RuntimeException("扫描报工条码/流转卡错误，请重新扫描");
        }
    }

    /**
     * 生成箱码
     *
     * @param params
     * @return
     */
    private String createNewWrapCode(MesTaskReportVO params) {
        if (Objects.isNull(params.getCompleteNum())) {
            params.setCompleteNum(BigDecimal.ZERO);
        }
        BigDecimal newNum = params.getCompleteNum().add(params.getPassNum());
        // 判断新数量是否是容量的整数倍
        if (newNum.remainder(BigDecimal.valueOf(params.getCapacity())).compareTo(BigDecimal.ZERO) == 0) {
            MesWrapEntity wrap = new MesWrapEntity();
            wrap.setTaskCode(params.getTaskCode());
            wrap.setProcedureTaskCode(params.getProduceTaskCode());
            wrap.setArrangeCode(params.getArrangeCode());
            wrap.setRuleCode(params.getWrapRuleCode());
            wrap.setWrapCapacity(params.getCapacity());
            wrap.setWrapType(params.getTaskType());
            wrap.setFactoryCode(params.getFactoryCode());
            wrap.setStatus(MesWrapStatusEnum.SEALED.name());
            wrap.setCurrentQuantity(BigDecimal.valueOf(params.getCapacity()));
            wrap.setSourceMode(ReportModeEnum.PACKAGE_ONE_CODE.name());
            String wrapCode = mesWrapService.addData(wrap);
            bindSealedRecords(params, wrapCode);
            return wrapCode;
        }
        return null;
    }

    /**
     * 保存装箱记录
     *
     * @param params
     */
    private void createWrapRecord(MesTaskReportVO params) {
        MesWrapRecordEntity record = new MesWrapRecordEntity();
        record.setReportId(params.getReportId());
        record.setTransferCode(params.getTransferCode());
        record.setArrangeCode(params.getArrangeCode());
        record.setTaskCode(params.getTaskCode());
        record.setProcedureTaskCode(params.getProduceTaskCode());
        record.setFactoryCode(params.getFactoryCode());
        record.setQuantity(BigDecimal.ONE);
        record.setAvailableQuantity(BigDecimal.ZERO);
        record.setPackingStatus(MesWrapRecordStatusEnum.STAGED.name());
        wrapRecordService.save(record);
    }

    /**
     * 封箱时把本箱全部单件记录绑定到箱码。已封箱记录必须先拆箱，不能直接反报工。
     */
    private void bindSealedRecords(MesTaskReportVO params, String wrapCode) {
        List<MesWrapRecordEntity> records = wrapRecordService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesWrapRecordEntity>()
                        .eq(MesWrapRecordEntity::getArrangeCode, params.getArrangeCode())
                        .isNotNull(MesWrapRecordEntity::getReportId)
                        .eq(MesWrapRecordEntity::getPackingStatus, MesWrapRecordStatusEnum.STAGED.name())
                        .orderByDesc(MesWrapRecordEntity::getCreateTime)
                        .orderByDesc(MesWrapRecordEntity::getId))
                .stream()
                .limit(params.getCapacity())
                .toList();
        if (records.size() != params.getCapacity()) {
            throw new IllegalStateException("待封箱单件数量与包装容量不一致");
        }
        records.forEach(record -> {
            record.setWrapCode(wrapCode);
            record.setPackingStatus(MesWrapRecordStatusEnum.PACKED.name());
            record.setAvailableQuantity(BigDecimal.ZERO);
        });
        wrapRecordService.updateBatchById(records);
    }

    @Override
    public ReportModeEnum getMode() {
        return ReportModeEnum.PACKAGE_ONE_CODE;
    }
}
