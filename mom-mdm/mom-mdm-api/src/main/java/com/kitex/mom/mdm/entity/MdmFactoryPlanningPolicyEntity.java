package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工厂计划协同策略版本。
 *
 * <p>每个租户、工厂仅允许一个生效版本；生效版本不可原地修改，只能由新版本受控替换。</p>
 */
@Data
@TenantTable
@TableName("mdm_factory_planning_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工厂级ERP/MOM计划权威与同步边界")
public class MdmFactoryPlanningPolicyEntity extends BaseEntity<MdmFactoryPlanningPolicyEntity> {
    @Schema(description = "工厂建模FACTORY节点主键")
    private Long factoryModelId;
    @Schema(description = "冻结的工厂编码")
    private String factoryCode;
    @Schema(description = "工厂内递增版本")
    private Integer versionNo;
    @Schema(description = "MRP权威：ERP_MRP或MOM_MRP")
    private String mrpAuthority;
    @Schema(description = "ERP连接器或系统编码")
    private String erpSystemCode;
    @Schema(description = "ERP侧工厂编码")
    private String externalFactoryCode;
    @Schema(description = "生产订单同步方式")
    private String productionOrderSyncMode;
    @Schema(description = "替代料治理方式")
    private String substituteGovernanceMode;
    @Schema(description = "计划生效时间")
    private LocalDateTime effectiveFrom;
    @Schema(description = "版本状态")
    private String status;
    @Schema(description = "规范策略内容SHA-256")
    private String contentFingerprint;
    @Schema(description = "变更原因")
    private String changeReason;
    @Schema(description = "创建草稿永久幂等号")
    private String draftRequestId;
    @Schema(description = "创建草稿命令SHA-256")
    private String draftRequestFingerprint;
    @Schema(description = "送审并冻结草稿的操作者")
    private String submittedBy;
    @Schema(description = "送审时间")
    private LocalDateTime submittedTime;
    @Schema(description = "发布人；必须与最后编制人不同")
    private String publishedBy;
    @Schema(description = "发布时间")
    private LocalDateTime publishedTime;
    @Schema(description = "发布永久幂等号")
    private String publishRequestId;
    @Schema(description = "发布命令SHA-256")
    private String publishCommandFingerprint;
    @Schema(description = "发布时冻结的影响确认号")
    private String impactConfirmationNo;
    @Schema(description = "预约激活失败原因")
    private String activationFailureReason;
    @Schema(description = "首次启用时冻结的新合同范围及影响证据JSON")
    private String initialActivationSnapshot;
}
