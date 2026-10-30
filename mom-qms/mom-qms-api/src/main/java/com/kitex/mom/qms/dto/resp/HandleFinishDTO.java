package com.kitex.mom.qms.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HandleFinishDTO {

    private String requestId;

    private Long id;

    private Integer handleNum;

    private String handleResult;

    private String handleRemark;

    /** 普通MES工序处置冻结的连续前件；旧自维护来源不使用。 */
    private Long expectedEventId;

    private String expectedFingerprint;

    /** 内部无条件让步的可追溯授权引用，不替代外部文件真实性核验。 */
    private String authorizationRef;

    private Boolean concessionUnrestricted;
}
