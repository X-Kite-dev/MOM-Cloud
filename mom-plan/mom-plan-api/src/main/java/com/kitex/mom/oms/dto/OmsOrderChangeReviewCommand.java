package com.kitex.mom.oms.dto;

import lombok.Data;

import java.util.List;

/** OMS变更评审请求；每一版命令号永久幂等。 */
@Data
public class OmsOrderChangeReviewCommand {
    private List<Long> reviewIds;
    private String requestId;
    /** 0批准、1驳回、2作废。 */
    private String reviewType;
    private String remark;
}
