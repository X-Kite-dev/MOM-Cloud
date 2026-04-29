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
import com.kitex.mom.mes.process.AbstractProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一物一码报工实现
 * <p>
 * 报工方式编码：ONE_CODE
 * 报工方式描述：工位进行一物一码过站模式。通过扫码标识对应的二维码已经通过该工序进行过站验证，并返回报工结果。
 * </p>
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class OneCodeProcessOperation extends AbstractProcessOperation {

    private static final String CODE = "ONE_CODE";

    @Resource
    private RemoteCustomerPartService partService;

    private MomUser currentUser;

    public OneCodeProcessOperation(MesWarehouseLiveStockService liveStockService, RemoteParamService remoteParamService, RemoteMaterialService materialService, MesWarehouseFeedService feedService) {
        super(liveStockService, remoteParamService, materialService, feedService);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesTaskReportVO params) {
        currentUser = SecurityUtils.getUser();
        // 1. 验证参数
        if (StrUtil.isEmpty(params.getTransferCode())) {
            throw new RuntimeException("报工条码/流转卡不能为空");
        }
        if (StrUtil.isEmpty(params.getMatCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        // 2. 一物一码校验 验证规则
        validateOneCode(params.getTransferCode(), params.getMatCode());

        // 3. 报工
        MesTaskReportEntity report = report(params);
        // 4.  物料消耗
        use(params);
        // 5. 在制品入库
        in(params);

        // 4. 返回处理结果
        Map<String, Object> resultMap = new HashMap<>(2);
        String reportMessage = currentUser.getName() + "于" + DateUtil.format(report.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行一物一码报工，合格数量为：" + report.getReportNum();
        resultMap.put("report", report);
        resultMap.put("reportMessage", reportMessage);
        return resultMap;
    }

    /**
     * 进行报工 一物一码记录条码的报工记录
     *
     * @param vo
     * @return
     */
    @Override
    public MesTaskReportEntity report(MesTaskReportVO vo) {
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

    /**
     * 物料消耗
     *
     * @param params
     */
    @Override
    public void use(MesTaskReportVO params) {
        // 判断当前物料是否进行投料。如果投料则进行消耗对应投料的物料
        super.use(params);
    }

    /**
     * 验证一物一码
     * <p>
     * 验证一物一码是否符合产品的规则
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


    @Override
    public String getCode() {
        return CODE;
    }
} 