package com.kitex.mom.mes.agent.dto;

import com.kitex.mom.mes.dto.MesFormalSopNodeDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 工单原冻结正式SOP证据，不改变旧知识引用合同，不将旧附件升级为批准版。
 */
public final class MesFormalSopEvidenceDTO {
    private MesFormalSopEvidenceDTO() {
    }

    /**
     * 精确当前工厂/工单，租户和用户只取实际认证上下文。
     */
    public record Query(@NotBlank @Size(max = 100) String factoryCode,
                        @NotBlank @Size(max = 100) String taskCode) {
    }

    /**
     * @param schemaVersion                固定MES_FORMAL_SOP_EVIDENCE_V1
     * @param tenantId                     原租户文本ID
     * @param factoryCode                  原工厂
     * @param taskId                       原工单文本ID
     * @param taskCode                     原工单号
     * @param routeSnapshotHash            原完整路线UTF-8观察摘要
     * @param formalNodes                  已核自身持久指纹的正式节点清单
     * @param legacyRouteNodeCodes         原无正式SOP证明的节点；无节点身份的旧记录标LEGACY_PROCESS而不猜新节点
     * @param gaps                         原缺口及历史参考边界
     * @param complete                     已完整读取原节点范围，非部分截断结果
     * @param currentSourceRecheckRequired 引用者须通过MDM精确verify核当前安全撤回/权限，不把历史来源状态当今日状态
     */
    public record Evidence(String schemaVersion, String tenantId, String factoryCode, String taskId, String taskCode,
                           String routeSnapshotHash, List<MesFormalSopNodeDTO> formalNodes,
                           List<String> legacyRouteNodeCodes, List<String> gaps, boolean complete,
                           boolean currentSourceRecheckRequired) {
    }
}
