package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "当前派工投料与可退线边库存定位信息")
public class MesWarehouseFeedLeftDTO {

    @Schema(description = "生产工单编码")
    private String taskCode;

    @Schema(description = "派工单编码")
    private String arrangeCode;

    @Schema(description = "工序任务编码")
    private String produceTaskCode;

    private String productCode;

    private String productName;

    private String batchNo;

    private String routeCode;

    private String routeName;

    private String matCode;

    private String reservationNo;

    private String requirementMatCode;

    private Boolean substituteFlag;

    private BigDecimal substituteRatio;

    private String matName;

    private String matSpec;

    private String matUnit;

    private String status;

    private BigDecimal leftNum;

    private String judgeStatus;

    private Date lastUsedTime;

    private Boolean isLock;

    private String reason;

    private BigDecimal backNum;

    @Schema(description = "线边库位编码")
    private String siteCode;

    @Schema(description = "线边库位名称")
    private String siteName;

    @Schema(description = "线边仓库编码")
    private String warehouseCode;

    @Schema(description = "线边仓库名称")
    private String warehouseName;

    @Schema(description = "数据所属工厂编码；仅由服务端填充")
    private String factoryCode;

}
