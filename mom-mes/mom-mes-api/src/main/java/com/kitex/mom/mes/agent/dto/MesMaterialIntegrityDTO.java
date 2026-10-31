package com.kitex.mom.mes.agent.dto;

import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 原耗料、恢复凭据与投料已用量的只读证明，不表示WMS实物库存盘平。 */
public final class MesMaterialIntegrityDTO {
    private MesMaterialIntegrityDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "独立取证协议")
        private String version;
        @Schema(description = "当前实际租户")
        private String tenantId;
        @Schema(description = "当前实际工厂")
        private String factoryCode;
        @Schema(description = "唯一原MES工单编码")
        private String taskCode;
        @Schema(description = "独立只读快照的完成时刻")
        private LocalDateTime observedAt;
        @Schema(description = "CONSISTENT/ANOMALY/DATA_GAP")
        private String status;
        @Schema(description = "受限完整范围可核定；true仍可能包含已证实异常")
        private Boolean scopeComplete;
        @Schema(description = "原四域耗料条数，不按组合份额重复计数")
        private Integer sourceCount;
        @Schema(description = "原工单投料行条数")
        private Integer feedCount;
        @Schema(description = "完整范围检查，不由展示条数推断")
        private List<MesReportQuantityConsistencyDTO.Check> checks;
        @Schema(description = "异常/缺证优先，最多50条原耗料")
        private List<Source> sources;
        @Schema(description = "异常/缺证优先，最多50条原投料对账")
        private List<Feed> feeds;
        @Schema(description = "展示是否受50条上限约束，不是计算截断")
        private Boolean detailTruncated;
        @Schema(description = "无法判定的原账缺口")
        private List<String> dataGaps;
        @Schema(description = "完整受限范围的确定性异常身份，不随展示裁剪")
        private List<String> riskKeys;
        @Schema(description = "原业务证据引用")
        private List<String> evidenceRefs;
    }

    @Data
    @Accessors(chain = true)
    public static class Source {
        @Schema(description = "原四域追溯行，GOOD/NG主键域保持分开")
        private MesMaterialConsumptionTraceDTO.Row original;
        @Schema(description = "精确原来源可归属的恢复量，实际物料原单位；未知为空")
        private String restoredQuantity;
        @Schema(description = "原量减已核实恢复量；未知为空，不从used_num反推")
        private String netQuantity;
        @Schema(description = "已核验的原恢复/纠正凭据主键")
        private List<String> restorationRefs;
        @Schema(description = "已证实偏差")
        private List<String> issues;
        @Schema(description = "本条缺证")
        private List<String> dataGaps;
    }

    @Data
    @Accessors(chain = true)
    public static class Feed {
        @Schema(description = "原投料行主键")
        private String feedId;
        @Schema(description = "原派工编码")
        private String arrangeCode;
        @Schema(description = "原实际物料编码")
        private String materialCode;
        @Schema(description = "原物料批次")
        private String batchNo;
        @Schema(description = "原实际单位，不跨单位合计")
        private String unit;
        @Schema(description = "原投料行当前投料量，字符串小数")
        private String feedQuantity;
        @Schema(description = "原投料行当前已用量，未知为空")
        private String recordedUsedQuantity;
        @Schema(description = "由四域原量与恢复凭据核定的净耗，未知为空")
        private String evidencedNetQuantity;
        @Schema(description = "已证实偏差")
        private List<String> issues;
        @Schema(description = "本条缺证")
        private List<String> dataGaps;
    }
}
