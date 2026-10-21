package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** PLAN应用MES完工重新打开事实后的逐来源永久回执。 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("pln_completion_reopen_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "PLAN完工重新打开回执")
public class PlnCompletionReopenReceiptEntity extends BaseEntity<PlnCompletionReopenReceiptEntity> {

    private Long inboxId;
    private Long mesOutboxId;
    private Long reverseRequestId;
    private Long mesTaskId;
    private String mesTaskCode;
    private Integer completionVersion;
    private Long issueId;
    private String issueNo;
    private String statusBefore;
    private String statusAfter;
    private LocalDate finishDateBefore;
    private BigDecimal allocatedQuantity;
    private BigDecimal completedQuantity;
    /** APPLIED已重新打开；SUPERSEDED已被同版本重新完工覆盖。 */
    private String applyResult;
    private String sourceFingerprint;
    private LocalDateTime appliedTime;
    private String factoryCode;
}
