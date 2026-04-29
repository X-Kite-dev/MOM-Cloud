package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 质检标准
 *
 * @author kitex
 * @since 2025-01-15
 */
@Data
public class QmsQualityStandardDTO {

    /**
     * @see InspectionTaskTypeEnum
     */
    @Schema(description = "质检类型")
    private String inspectionType;

    @Schema(description = "质检标准名称")
    private String standardName;

    @Schema(description = "质检物料")
    private String matCode;

    @Schema(description = "质检物料")
    private String matName;

    /**
     * @see com.kitex.mom.qms.enums.InspectionMethodEnum
     */
    @Schema(description = "质检方式")
    private String inspectionMethod;

    @Schema(description = "质检数量")
    private Integer num;

    @Schema(description = "状态")
    private Integer published;

    private List<QmsQualityStandardItemEntity> items;
}
