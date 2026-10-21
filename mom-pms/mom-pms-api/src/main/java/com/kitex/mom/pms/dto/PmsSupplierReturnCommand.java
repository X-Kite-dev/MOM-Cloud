package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 采购到货后退供的业务命令。
 */
public final class PmsSupplierReturnCommand {
    private PmsSupplierReturnCommand() {
    }

    @Data
    @Accessors(chain = true)
    public static class Submit {
        private Long returnId;
        private String requestId;
    }

    @Data
    @Accessors(chain = true)
    public static class Review {
        private Long returnId;
        private String requestId;
        @Schema(description = "APPROVE或REJECT")
        private String decision;
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class Cancel {
        private Long returnId;
        private String requestId;
        private String reason;
    }
}
