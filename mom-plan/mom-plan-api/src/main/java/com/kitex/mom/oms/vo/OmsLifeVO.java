package com.kitex.mom.oms.vo;

import com.kitex.mom.plan.vo.OmsLifePlanVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单生命周期VO
 *
 * @author kitex
 * @date 2025-05-12 16:48:23
 */
@Data
@Schema(description = "订单生命周期VO")
public class OmsLifeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 计划
     */
    @Schema(description = "计划")
    private List<OmsLifePlanVO> planList;

    /**
     * 生产
     */
    @Schema(description = "生产")
    private List<OmsLifeMesTaskVO> mesList;

    /**
     * 采购
     */
    @Schema(description = "采购")
    private List<OmsLifePmsPlanVO> pmsList;

    /**
     * 发货
     */
    @Schema(description = "发货")
    private List<OmsLifeWmsDeliverVO> deliverList;

    @Schema(description = "跨域履约摘要")
    private OmsFulfillmentSummaryVO fulfillmentSummary;

    @Schema(description = "按业务先后排列的履约里程碑")
    private List<OmsFulfillmentMilestoneVO> fulfillmentMilestones;
}
