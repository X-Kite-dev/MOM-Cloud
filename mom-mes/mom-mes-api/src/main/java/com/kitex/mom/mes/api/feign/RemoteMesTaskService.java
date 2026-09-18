package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.dto.MesDurationEvidenceDTO;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesQualityDispositionDTO;
import com.kitex.mom.mes.dto.MesPlanSourceMappingDTO;
import com.kitex.mom.mes.dto.MesPlanWithdrawalDTO;
import com.kitex.mom.mes.dto.MesSourceQuantityMappingDTO;
import com.kitex.mom.mes.dto.PlanMesQueryDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteMesTaskService", value = Constants.Name)
public interface RemoteMesTaskService {

    /**
     * 根据生产计划生成生产工单
     *
     * @param receive 计划信息
     * @return R
     */
    @PostMapping("/mesTask/add")
    R<Boolean> add(@RequestBody MesTaskReceiveDTO receive);

    @PostMapping("/mesTask/rework")
    R<String> addRework(@RequestBody MesTaskReceiveDTO receive);

    @PostMapping("/mesTask/rework/inspection-result")
    R<Void> syncReworkInspection(@RequestBody MesReworkInspectionResultDTO result);

    @PostMapping("/mesTask/quality-disposition")
    R<Void> recordQualityDisposition(@RequestBody MesQualityDispositionDTO command);

    @GetMapping("/mesTask/internal/plan-source-mapping")
    R<MesPlanSourceMappingDTO> planSourceMapping(@RequestParam(value = "taskCode", required = false) String taskCode,
                                                 @RequestParam(value = "produceTaskCode", required = false) String produceTaskCode,
                                                 @RequestParam("factoryCode") String factoryCode);

    @PostMapping("/mesTask/internal/plan-withdraw")
    @NoToken
    R<MesPlanWithdrawalDTO.Receipt> withdrawPlan(@RequestBody MesPlanWithdrawalDTO.Command command);

    @GetMapping("/mesTask/internal/source-quantity-mapping")
    R<MesSourceQuantityMappingDTO> sourceQuantityMapping(@RequestParam("requestId") String requestId,
                                                          @RequestParam("factoryCode") String factoryCode);

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    @GetMapping("/mesTask/getByOrders")
    R<List<MesTaskReportOmsVO>> getByOrders(@RequestParam("orders") List<String> orders);


    /**
     * 生产情况
     */
    @GetMapping("/mesTask/plan/list")
    R<List<MesTaskEntity>> planMesList(@SpringQueryMap PlanMesQueryDTO planMesQueryDTO);

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    @GetMapping("/mesTask/omsLife")
    R<List<MesTaskEntity>> omsLife(@RequestParam("planNo") String planNo);

    /**
     * 更新生产工单（用于重排后同步计划时间）
     *
     * @param mesTask 工单实体（planNo + planStartDate + planEndDate）
     * @return R
     */
    @PostMapping("/mesTask/update")
    R<Void> update(@RequestBody MesTaskEntity mesTask);

    @InternalCall
    @GetMapping("/mesApsSchedule/internal/operation-states")
    R<MesApsLocalScheduleDTO.StateBundle> apsOperationStates(@RequestParam("planNo") String planNo,
                                                             @RequestParam("factoryCode") String factoryCode);

    @InternalCall
    @PostMapping("/mesApsSchedule/internal/apply")
    R<MesApsLocalScheduleDTO.ApplyReceipt> applyApsLocalSchedule(
            @RequestBody MesApsLocalScheduleDTO.ApplyCommand command);

    @InternalCall
    @PostMapping("/mesDurationEvidence/internal/query")
    R<MesDurationEvidenceDTO.Bundle> durationEvidence(@RequestBody MesDurationEvidenceDTO.Query query);

}
