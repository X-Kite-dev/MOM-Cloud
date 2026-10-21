package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ERP 对 MOM 建单请求返回的生产订单映射。
 *
 * <p>该命令只允许给已发布的 MOM 正式计划合同补充 ERP 生产订单号。服务端不会原地修改合同，
 * 而是核验完整映射后生成新的不可变合同版本。</p>
 */
@Data
@Schema(description = "ERP生产订单映射回执")
public class PlnErpProductionOrderReceiptCommand {
    /** ERP 回执永久幂等号。 */
    private String requestId;
    /** 对应 PRODUCTION_ORDER_REQUEST 的请求号。 */
    private String sourcePostingRequestId;
    private String erpSystemCode;
    private String externalFactoryCode;
    private String contractNo;
    private Integer sourceContractVersion;
    private String receiptNo;
    private LocalDateTime receiptTime;
    private List<LineMapping> mappings;

    /** 正式合同自制行与 ERP 生产订单的一一映射。 */
    @Data
    public static class LineMapping {
        private String lineNo;
        private String erpProductionOrderNo;
    }
}
