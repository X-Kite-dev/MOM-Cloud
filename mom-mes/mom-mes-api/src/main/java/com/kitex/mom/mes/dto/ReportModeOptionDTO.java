package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.enums.ReportModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 前端可消费的报工模式选项。
 */
@Data
@AllArgsConstructor
@Schema(description = "报工模式选项")
public class ReportModeOptionDTO {

    @Schema(description = "稳定模式编码")
    private String code;

    @Schema(description = "模式名称")
    private String name;

    @Schema(description = "是否必须扫描标识")
    private boolean identifierRequired;

    @Schema(description = "是否为包装报工")
    private boolean packaging;

    @Schema(description = "生产对象粒度：UNIT/BATCH/TRAVELER/PACKAGE")
    private String objectGranularity;

    @Schema(description = "数量规则：EXACT_ONE/POSITIVE_QUANTITY")
    private String quantityPolicy;

    @Schema(description = "标识规则：NONE/ONCE_PER_OPERATION/NEW_PACKAGE")
    private String identifierPolicy;

    @Schema(description = "支持的触发方式；设备触发不是另一套工艺模式")
    private List<String> supportedTriggers;

    public static ReportModeOptionDTO from(ReportModeEnum mode) {
        String granularity;
        String quantityPolicy;
        String identifierPolicy;
        switch (mode) {
            case ONE_CODE -> {
                granularity = "UNIT";
                quantityPolicy = "EXACT_ONE";
                identifierPolicy = "ONCE_PER_OPERATION";
            }
            case QUANTITY -> {
                granularity = "BATCH";
                quantityPolicy = "POSITIVE_QUANTITY";
                identifierPolicy = "NONE";
            }
            case TRANSFER_CARD -> {
                granularity = "TRAVELER";
                quantityPolicy = "POSITIVE_QUANTITY";
                identifierPolicy = "ONCE_PER_OPERATION";
            }
            case PACKAGE_ONE_CODE -> {
                granularity = "PACKAGE";
                quantityPolicy = "EXACT_ONE";
                identifierPolicy = "ONCE_PER_OPERATION";
            }
            case PACKAGE_QUANTITY -> {
                granularity = "PACKAGE";
                quantityPolicy = "POSITIVE_QUANTITY";
                identifierPolicy = "NEW_PACKAGE";
            }
            default -> throw new IllegalStateException("未定义报工模式元数据: " + mode);
        }
        return new ReportModeOptionDTO(mode.getCode(), mode.getName(), mode.isIdentifierRequired(),
                mode.isPackaging(), granularity, quantityPolicy, identifierPolicy,
                List.of("MANUAL", "AUTO_EVENT", "API"));
    }
}
