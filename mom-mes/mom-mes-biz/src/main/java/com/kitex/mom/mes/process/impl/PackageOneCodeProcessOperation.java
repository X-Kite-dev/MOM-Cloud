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
import org.springframework.transaction.annotation.Propagation;
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

    private final MesWrapService mesWrapService;
    private final MesWrapRecordService wrapRecordService;

    public PackageOneCodeProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService, MesWrapService mesWrapService, MesWrapRecordService wrapRecordService) {
        super(liveStockService, remoteParamService, matService, feedService);
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
        // 2. 条码规则、错料和重复过站已由统一过站防错门校验。
        if (params.getPassNum() == null || params.getPassNum().compareTo(BigDecimal.ONE) != 0
                || params.getCapacity() == null || params.getCapacity() <= 0) {
            throw new IllegalArgumentException("包装逐件报工须单件合格量1及正包装容量");
        }
        // 3. 构造报告；包装关联后移到统一入口保存并核验报告之后。
        MesTaskReportEntity report = createReport(params, currentUser);
        params.setReportId(report.getId());
        String reportMessage = operatorName(params, currentUser) + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行包装装箱报工，合格数量为：" + report.getReportNum();
        return new MesTaskReportResult().setReport(report).setReportMessage(reportMessage);
    }

    /**
     * 已保存报告的单件内容入待装区；达到容量时，整箱关联和履历同事务完成。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void persistAssociations(MesTaskReportVO params, MesTaskReportResult result) {
        MomUser currentUser = SecurityUtils.getUser();
        createWrapRecord(params);
        String wrapCode = createNewWrapCode(params);
        if (StrUtil.isNotEmpty(wrapCode)) {
            String wrapMessage = operatorName(params, currentUser) + "于" + DateUtil.format(result.getReport().getReportTime(), "yyyy-MM-dd HH:mm:ss") + "完成装箱，生成箱码：" + wrapCode + "，容量：" + params.getCapacity();
            result.setWrapMessage(wrapMessage);
        }
    }

    /**
     * 生成箱码
     *
     * @param params 当前派工报工参数
     * @return 容量已满时的新箱码，否则为null
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
     * @param params 已绑定真实报告的单件参数
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
        if (!wrapRecordService.save(record)) {
            throw new IllegalStateException("包装单件明细必要写失败，整笔报工回滚");
        }
    }

    /**
     * 封箱时把本箱全部单件记录绑定到箱码。已封箱记录必须先拆箱，不能直接反报工。
     */
    private void bindSealedRecords(MesTaskReportVO params, String wrapCode) {
        List<MesWrapRecordEntity> records = wrapRecordService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesWrapRecordEntity>()
                        .eq(MesWrapRecordEntity::getArrangeCode, params.getArrangeCode())
                        .eq(MesWrapRecordEntity::getFactoryCode, params.getFactoryCode())
                        .eq(MesWrapRecordEntity::getTaskCode, params.getTaskCode())
                        .eq(MesWrapRecordEntity::getProcedureTaskCode, params.getProduceTaskCode())
                        .isNotNull(MesWrapRecordEntity::getReportId)
                        .eq(MesWrapRecordEntity::getPackingStatus, MesWrapRecordStatusEnum.STAGED.name())
                        .orderByDesc(MesWrapRecordEntity::getCreateTime)
                        .orderByDesc(MesWrapRecordEntity::getId)
                        .last("LIMIT " + params.getCapacity()));
        if (records.size() != params.getCapacity()) {
            throw new IllegalStateException("待封箱单件数量与包装容量不一致");
        }
        records.forEach(record -> {
            record.setWrapCode(wrapCode);
            record.setPackingStatus(MesWrapRecordStatusEnum.PACKED.name());
            record.setAvailableQuantity(BigDecimal.ZERO);
        });
        if (!wrapRecordService.updateBatchById(records)) {
            throw new IllegalStateException("封箱单件关联必要写失败，整笔报工回滚");
        }
    }

    @Override
    public ReportModeEnum getMode() {
        return ReportModeEnum.PACKAGE_ONE_CODE;
    }
}
