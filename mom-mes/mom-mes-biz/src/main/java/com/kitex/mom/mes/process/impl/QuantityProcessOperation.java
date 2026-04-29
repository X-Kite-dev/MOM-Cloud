package com.kitex.mom.mes.process.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 数量报工实现
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class QuantityProcessOperation extends AbstractProcessOperation {

    private static final String CODE = "QUANTITY";

    public QuantityProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService matService, MesWarehouseFeedService feedService) {
        super(liveStockService, remoteParamService, matService, feedService);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesTaskReportVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 实现数量报工逻辑
        // 1. 验证参数
        if (Objects.isNull(params.getPassNum())) {
            throw new RuntimeException("报工数量不能为空");
        }
        if (StrUtil.isEmpty(params.getMatCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        // 3. 更新报工状态
        MesTaskReportEntity report = createAndSaveReport(params, currentUser);
        // 4. 返回处理结果
        Map<String, Object> resultMap = new HashMap<>(2);
        String reportMessage = currentUser.getName() + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行数量报工，合格数量为：" + report.getReportNum();
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