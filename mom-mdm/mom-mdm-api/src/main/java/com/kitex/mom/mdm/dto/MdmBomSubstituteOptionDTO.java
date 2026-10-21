package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * BOM 子项替代料策略快照。替代比例表示满足 1 个主料需求所需的替代料数量。
 */
@Data
@Schema(description = "BOM 子项替代料策略")
public class MdmBomSubstituteOptionDTO {

    @Schema(description = "替代物料编码")
    private String substituteCode;

    @Schema(description = "替代物料名称")
    private String substituteName;

    @Schema(description = "规格型号")
    private String substituteSpec;

    @Schema(description = "计量单位")
    private String substituteUnit;

    @Schema(description = "替代比例：1 个主料需求对应的替代料用量")
    private BigDecimal substituteRatio;

    @Schema(description = "使用优先级，数值越小越优先")
    private Integer priority;

    @Schema(description = "生效日期")
    private LocalDate effectiveStart;

    @Schema(description = "失效日期")
    private LocalDate effectiveEnd;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "适用客户编码白名单；空表示不限制客户")
    private List<String> applicableCustomerCodes;

    @Schema(description = "认证供应商编码白名单；空表示不限制供应商")
    private List<String> certifiedSupplierCodes;

    @Schema(description = "供应商认证/批准依据编号")
    private String supplierCertificateRef;

    @Schema(description = "供应商认证/批准有效期")
    private LocalDate supplierCertificateValidEnd;

    @Schema(description = "供应商认证权威台账冻结快照")
    private List<MdmSubstituteAuthorizationSnapshotDTO> supplierCertifications;

    @Schema(description = "客户替代批准权威台账冻结快照")
    private List<MdmSubstituteAuthorizationSnapshotDTO> customerApprovals;

    @Schema(description = "最严格检验等级：NORMAL、TIGHTENED")
    private String inspectionLevel;

    @Schema(description = "替代料参考单价，取认证供应商最低有效报价")
    private BigDecimal referenceUnitPrice;

    @Schema(description = "替代料参考单价币种")
    private String currency;

    @Schema(description = "允许使用的库存质量结论；空表示采用WMS通用放行规则")
    private List<String> allowedQualityResults;

    @Schema(description = "合规方式：STANDARD标准授权、DEVIATION偏离许可")
    private String complianceMode;

    @Schema(description = "偏离许可编号")
    private String deviationPermitNo;

    @Schema(description = "偏离许可原因")
    private String deviationReason;

    @Schema(description = "偏离许可有效期")
    private LocalDate deviationValidEnd;
}
