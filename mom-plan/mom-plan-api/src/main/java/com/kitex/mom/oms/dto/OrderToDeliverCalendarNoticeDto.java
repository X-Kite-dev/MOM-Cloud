package com.kitex.mom.oms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OrderToDeliverCalendarNoticeDto {

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "发货时间")
    private LocalDate time;

    @Schema(description = "发货数量")
    private BigDecimal deliverNum;

    @Schema(description = "已发货数量")
    private BigDecimal deliveredNum;

    @Schema(description = "生产计划数量")
    private BigDecimal mesPlanNum;

    @Schema(description = "已生产数量")
    private BigDecimal mesNum;

    @Schema(description = "发货通知")
    private String deliverNotice;

    @Schema(description = "生产通知")
    private String mesNotice;

    public String getDeliverNotice() {
        if (this.deliverNum != null && this.deliveredNum != null) {
            return "发货 " + this.deliveredNum + "/" + this.deliverNum;
        }
        return "发货 0/" + this.deliverNum;
    }

    public String getMesNotice() {
        if (this.mesPlanNum != null && this.mesNum != null) {
            return "生产计划 " + this.mesNum + "/" + this.mesPlanNum;
        }
        return "生产计划 0/" + this.mesPlanNum;
    }
}
