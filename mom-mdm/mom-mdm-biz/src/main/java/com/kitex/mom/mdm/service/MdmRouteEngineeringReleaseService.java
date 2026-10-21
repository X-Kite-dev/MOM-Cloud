package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringReleaseEntity;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringCommandEntity;
import lombok.Data;
import java.util.List;

/** 工程版本预检、不可变封存、实名异人审核及原号恢复。 */
public interface MdmRouteEngineeringReleaseService extends IService<MdmRouteEngineeringReleaseEntity> {
    /** 读取实际工厂路线及正式BOM候选。 */
    MdmRouteEngineeringDTO.Context context(Long routeId);
    /** 完整重采权威来源并报告工程阻断项。 */
    MdmRouteEngineeringDTO.Preview preview(MdmRouteEngineeringDTO.PreviewQuery query);
    /** 执行并永久记录一次明确生命周期命令。 */
    MdmRouteEngineeringDTO.Receipt command(MdmRouteEngineeringDTO.Command command);
    /** 同请求锁内先恢复已成功原账，无账时永久封存原号，禁止后来重新执行。 */
    MdmRouteEngineeringDTO.Receipt seal(MdmRouteEngineeringDTO.Command command);
    /** 完整读取路线内版本，超过500项要求收窄业务范围。 */
    List<MdmRouteEngineeringReleaseEntity> releases(Long routeId);
    /** 读取精确工程原文及有界生命周期审计。 */
    Detail detail(Long id);
    /** 仅原实名用户恢复其永久命令回执。 */
    MdmRouteEngineeringDTO.Receipt receipt(String requestId);
    /** 当前组织和产品的全部已发布生产候选；不在列表阶段承诺来源仍未漂移。 */
    List<MdmRouteEngineeringDTO.ProductionCandidate> productionCandidates(String productCode);
    /** 锁定精确版本并重采当前来源，只有原冻结包逐字节一致时才返回生产包。 */
    MdmRouteEngineeringDTO.ProductionPackage resolveForProduction(MdmRouteEngineeringDTO.ProductionQuery query);
    /** 原生产工程版本及稳定节点下可选择的已批准返工覆盖版本。 */
    List<MdmRouteEngineeringDTO.ReworkOverrideCandidate> reworkOverrideCandidates(Long baseReleaseId,
                                                                                   String baseContentHash,
                                                                                   String targetRouteNodeCode);
    /** 精确重采并返回返工覆盖包；不接受“最新版本”替代。 */
    MdmRouteEngineeringDTO.ReworkOverridePackage resolveForReworkOverride(
            MdmRouteEngineeringDTO.ReworkOverrideQuery query);

    /** 版本详情；生产消费边界始终明确。 */
    @Data
    class Detail {
        /** 原封存版本。 */
        private MdmRouteEngineeringReleaseEntity release;
        /** 当前生命周期修订指纹。 */
        private String revisionHash;
        /** 生命周期最多四次命令，不允许静默丢失审计。 */
        private List<MdmRouteEngineeringCommandEntity> commands;
        /** 工程批准不能替代MES/APS消费接入。 */
        private String executionNotice = MdmRouteEngineeringDTO.EXECUTION_NOTICE;
    }
}
