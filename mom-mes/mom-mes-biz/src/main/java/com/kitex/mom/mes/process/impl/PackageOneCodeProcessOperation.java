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
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWrapRecordService;
import com.kitex.mom.mes.service.MesWrapService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 包装+一物一码报工实现
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class PackageOneCodeProcessOperation extends AbstractProcessOperation {

    private static final String CODE = "PACKAGE_ONE_CODE";

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
    public Map<String, Object> execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("报工条码不能为空");
        }
        // 2. 一物一码校验
        validateOneCode(params.getTransferCode(), params.getMatCode());
        // 3. 保存装箱记录（保存的一物一码的条码
        createWrapRecord(params);
        // 4. 扫码数量达到最大容量后生成箱码
        String wrapCode = createNewWrapCode(params);
        // 5. 创建并保存报工记录
        MesTaskReportEntity report = createAndSaveReport(params, currentUser);
//        // 6. 冗余绑定箱码
//        arrangeService.update(new LambdaUpdateWrapper<MesTaskArrange>()
//                .eq(MesTaskArrange::getCode, params.getArrangeCode())
//                .set(MesTaskArrange::getWrapCode, wrapCode));
        // 7. 返回处理结果
        Map<String, Object> resultMap = new HashMap<>(2);
        String reportMessage = currentUser.getName() + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行包装装箱报工，合格数量为：" + report.getReportNum();
        resultMap.put("report", report);
        resultMap.put("reportMessage", reportMessage);
        if (StrUtil.isNotEmpty(wrapCode)) {
            String wrapMessage = currentUser.getName() + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "完成装箱，生成箱码：" + wrapCode + "，容量：" + params.getCapacity();
            resultMap.put("wrapMessage", wrapMessage);
        }
        return resultMap;
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
            wrap.setWrapType(params.getTaskType());
            wrap.setFactoryCode(params.getFactoryCode());
            return mesWrapService.addData(wrap);
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
        record.setTransferCode(params.getTransferCode());
        record.setArrangeCode(params.getArrangeCode());
        record.setTaskCode(params.getTaskCode());
        record.setProcedureTaskCode(params.getProduceTaskCode());
        wrapRecordService.save(record);
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
//        report.setQualifiedNum(vo.getPassNum());
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