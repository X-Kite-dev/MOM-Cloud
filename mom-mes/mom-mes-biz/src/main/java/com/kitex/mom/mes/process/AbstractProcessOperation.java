package com.kitex.mom.mes.process;

import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.dto.MesWarehouseUseDTO;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.MesWarehouseMatDetailTypeEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractProcessOperation implements ProcessOperation {


    protected final MesWarehouseLiveStockService liveStockService;

    protected final RemoteParamService remoteParamService;

    protected final RemoteMaterialService matService;

    private final MesWarehouseFeedService feedService;

    @Override
    public Map<String, Object> execute(MesTaskReportVO params) {
        log.info(String.format("开始执行工序%s的报工", params.getWorksiteCode()));
        // 1. 报工
        report(params);

        // 2. 物料消耗
        use(params);
        // 3. 在制品入库

        in(params);
        return Map.of();
    }

    @Override
    public MesTaskReportEntity report(MesTaskReportVO params) {
        log.info("开始报工");
        return new MesTaskReportEntity();
    }

    /**
     * 通用物料消耗
     *
     * @param params
     */
    @Override
    public void use(MesTaskReportVO params) {
        log.info("通用物料消耗");

        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
        MesTaskArrangeEntity arrange = params.getTaskArrange();
        MesTaskEntity task = params.getTask();
        // 倒冲则直接在线边库中扣除
        if (backFlushFlag.equals("1")) {
            ProcessProcedureDTO currentProcess = MesTaskUtils.getCurrentProcess(params.getTask(), params.getTaskArrange().getRouteCode());
            // 工序的物料信息
            List<ProcessProcedureDTO.ProcessProcedureMatDTO> mdmProcessProcedureMatList = currentProcess.getMdmProcessProcedureMatList();
            mdmProcessProcedureMatList.forEach(item -> {
                MdmMaterialEntity rawMat = matService.getDetails(item.getMaterialCode()).getData();
                if (Objects.isNull(rawMat) || Objects.equals(rawMat.getStatus(), 0)) {
                    throw new RuntimeException("物料不可用，报工失败");
                }
                MesWarehouseLiveStockInoutDTO out = new MesWarehouseLiveStockInoutDTO();
                out.setBillNo(arrange.getCode());
                out.setProduceTaskCode(arrange.getProduceTaskCode());
                out.setMatCode(rawMat.getMaterialCode());
                out.setMatName(rawMat.getMaterialName());
                out.setMatSpec(rawMat.getSpecificationModel());
                out.setMatUnit(rawMat.getBasicUnit());
                out.setNum(params.getPassNum().multiply(item.getQuantity()));
//                out.setSiteCode(params.getWorksiteCode());
//                out.setSiteName(workCenterEntity.getName());
                out.setMatBatch(arrange.getBatchNo());
                out.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
                out.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
                liveStockService.outStock(out);
            });
        } else {
            // 消耗投料
            // 消耗投料
            feedService.use(new MesWarehouseUseDTO().setReportNum(params.getPassNum()).setArrangeCode(arrange.getCode())
                    .setProcessRouteCode(task.getProcessRouteCode()).setRouteCode(arrange.getRouteCode()).setSnapshot(task.getProcessRouteSnapshot()));
        }


    }


    /**
     * 线边入库
     * 1. 根据当前产品和工序情况判断入库为在制品还是半成品完成，还是成品
     * 2. 入库类型
     * a. 在制品
     * b. 半成品完成
     * c. 成品
     *
     * @param params
     */
    @Override
    public void in(MesTaskReportVO params) {
        log.info("线边入库");
        // 生产任务
        MesTaskEntity task = params.getTask();
        // 派工工单
        MesTaskArrangeEntity arrange = params.getTaskArrange();
//        String nextRouteCode = routeWorking.getNextRouteCode();
        MesWarehouseLiveStockInoutDTO in = new MesWarehouseLiveStockInoutDTO();
        in.setBillNo(arrange.getCode());
        in.setProduceTaskCode(arrange.getProduceTaskCode());
        in.setRouteCode(arrange.getRouteCode());
        in.setRouteName(arrange.getRouteName());
        in.setMatCode(arrange.getMatCode());
        in.setMatName(arrange.getMatName());
        in.setMatSpec(arrange.getMatSpec());
        in.setMatBatch(arrange.getBatchNo());
//        in.setMatUnit(arrange.getBasicUnit());
//            in.setMatBarcode();
        in.setNum(params.getCompleteNum());
        in.setSiteCode(arrange.getRouteCode());
        in.setSiteName(arrange.getRouteName());

        // 如果没有后置工序则认为进行入线边库存
        // 1. 成品计划入库
        // 2. 零件计划
//        if(StrUtil.isBlank(nextRouteCode)){
//            if("PRODUCT".equals(task.getType())){
//                // 成品入库
//                in.setMatDetailType(MesWarehouseMatDetailTypeEnum.PRODUCT.name());
//            }else{
//                in.setMatDetailType(MesWarehouseMatDetailTypeEnum.SEMI_FINISHED.name());
//            }
//        }else{
//            // 在制品入库
//            in.setMatDetailType(MesWarehouseMatDetailTypeEnum.WIP.name());
//        }
        in.setBizType(WarehouseInBizTypeEnum.PROCESS_PRODUCT_IN.getCode());
        in.setFactoryCode(arrange.getFactoryCode());
        liveStockService.inStock(in);
    }
}
