package com.kitex.mom.wms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SalesLedgerPageDto {

    @Schema(name = "产品编码")
    private String productCode;

    @Schema(name = "产品名称")
    private String productName;

    @Schema(name = "数量")
    private String deliveredNum;

    @Schema(name = "仓库编码")
    private String warehouseCode;

    @Schema(name = "仓库名称")
    private String warehouseName;


    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(name = "查询条件")
    private LocalDate startTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(name = "查询条件")
    private LocalDate endTime;

    @Schema(name = "查询条件")
    private LocalDate[] dateRange;


    /**
     * 默认本月
     */
    public LocalDate getStartTime() {
        if (this.startTime == null) {
            LocalDate now = LocalDate.now();
            return now.withDayOfMonth(1);
        }
        return startTime;
    }

    public LocalDate getEndTime() {
        if (this.endTime == null) {
            return LocalDate.now();
        }
        return endTime;
    }
}
