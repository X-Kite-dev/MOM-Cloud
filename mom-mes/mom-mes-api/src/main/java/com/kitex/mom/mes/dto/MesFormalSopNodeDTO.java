package com.kitex.mom.mes.dto;

import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import lombok.Data;

import java.util.List;

/**
 * MES下达时精确冻结的正式SOP节点证据；历史空值不能补成今日正式版本。
 */
@Data
public class MesFormalSopNodeDTO {
    /**
     * 固定MES_FORMAL_SOP_NODE_V1，区别于旧附件引用。
     */
    private String schemaVersion;
    /**
     * 原MDM完整下达取证清单的指纹。
     */
    private String sourceManifestHash;
    /**
     * 文本租户身份。
     */
    private String tenantId;
    /**
     * 原工厂编码。
     */
    private String factoryCode;
    /**
     * 正式来源的工艺路线编码。
     */
    private String routeCode;
    /**
     * 不可改写的原稳定路线节点。
     */
    private String routeNodeCode;
    /**
     * 原主工序编码。
     */
    private String processCode;
    /**
     * 本节点完整正式来源，精确版本、原件及批准证据，不是当前最新版。
     */
    private List<MdmFormalSopDTO.Source> sources;
    /**
     * 原解析时明确的无正式绑定等缺口。
     */
    private List<String> gaps;
    /**
     * 本节点完整身份、来源、缺口的稳定指纹。
     */
    private String manifestHash;
}
