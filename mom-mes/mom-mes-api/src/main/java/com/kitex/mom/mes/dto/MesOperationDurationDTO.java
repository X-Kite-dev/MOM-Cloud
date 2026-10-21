package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * APS业务回评专用的完整连续生产段证据；不是人工人时或纯加工遥测。
 */
public final class MesOperationDurationDTO {
    private MesOperationDurationDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "须与实名请求实际工厂完全相同")
        private String factoryCode;
        @Schema(description = "已由MES执行状态取得的精确工序任务编码，唯一且最多500个")
        private List<String> produceTaskCodes;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "实际租户ID，十进制文本")
        private String tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "生产段证据版本，不改变旧APS评分或永久指纹")
        private String evidenceVersion;
        @Schema(description = "本次一致性查询开始时刻，未来时间不得作为已结束事实")
        private LocalDateTime queriedTime;
        @Schema(description = "全部工序证据，缺失来源也显式占一行，不截断")
        private List<Operation> operations;
    }

    @Data
    @Accessors(chain = true)
    public static class Operation {
        @Schema(description = "源工序原租户ID，含失败证据")
        private String tenantId;
        @Schema(description = "源工序原工厂")
        private String factoryCode;
        @Schema(description = "精确工序任务编码")
        private String produceTaskCode;
        @Schema(description = "工序任务ID，文本")
        private String produceTaskId;
        @Schema(description = "真实物理工单编码")
        private String taskCode;
        @Schema(description = "实际产品编码")
        private String productCode;
        @Schema(description = "实际工序编码")
        private String processCode;
        @Schema(description = "工序任务当前状态")
        private String mesStatus;
        @Schema(description = "原工序量，精确文本")
        private String originalQuantity;
        @Schema(description = "当前工序完成量，精确文本，不等于质量放行/入库")
        private String completedQuantity;
        @Schema(description = "原下达时APS工序冻结快照，历史缺字段不回填")
        private String apsOperationSnapshot;
        @Schema(description = "VERIFIED/IN_PROGRESS/DATA_GAP")
        private String evidenceStatus;
        @Schema(description = "所有不可核验原因")
        private List<String> gaps;
        @Schema(description = "完整派工事实与全部生产段，含明确排除的未开工关闭派工")
        private List<Arrangement> arrangements;
        @Schema(description = "已核验生产段合计秒数；未知为null")
        private String runSegmentSeconds;
        @Schema(description = "第一派工开始至最后派工完成的墙钟秒数；未知为null")
        private String wallClockSeconds;
        @Schema(description = "各派工内部生产段之间的暂停间隙秒数；不解释为停机原因")
        private String pauseGapSeconds;
        @Schema(description = "顺序派工之间的间隙秒数，不混入段内暂停")
        private String interDispatchGapSeconds;
        @Schema(description = "各区间整秒截断后的墙钟精度余差，独立展示，不归为暂停/停机")
        private String precisionRemainderSeconds;
        @Schema(description = "源工序原实际开工，失败证据也保留，不从派工补造")
        private String actualStartTime;
        @Schema(description = "源工序原实际完工，失败证据也保留，不从派工补造")
        private String actualFinishTime;
        @Schema(description = "稳定完整证据SHA-256；不包含动态查询时刻")
        private String evidenceFingerprint;
        @Schema(description = "V2独立工装换型归属证明，不改变旧校准或开完工评分")
        private MesChangeoverAttributionDTO.Evidence changeoverEvidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Arrangement {
        @Schema(description = "派工ID，文本")
        private String id;
        @Schema(description = "原租户ID，文本")
        private String tenantId;
        @Schema(description = "原工厂")
        private String factoryCode;
        @Schema(description = "派工编码")
        private String code;
        @Schema(description = "原物理工单编码")
        private String taskCode;
        @Schema(description = "原工序任务编码")
        private String produceTaskCode;
        @Schema(description = "原工序编码")
        private String processCode;
        @Schema(description = "原产品编码")
        private String productCode;
        @Schema(description = "派工状态")
        private String status;
        @Schema(description = "实际开始，ISO原时刻文本保留亚秒")
        private String startTime;
        @Schema(description = "实际完成，ISO原时刻文本保留亚秒")
        private String finishTime;
        @Schema(description = "当前有效完成量，文本")
        private String completedQuantity;
        @Schema(description = "派工原下达量，精确文本；原量异常也完整冻结")
        private String originalQuantity;
        @Schema(description = "实际工位，缺失不能从计划工位猜测")
        private String stationCode;
        @Schema(description = "派工冻结设备，非IoT物理设备证明")
        private String deviceCode;
        @Schema(description = "派工冻结APS工序快照，不覆写历史")
        private String apsOperationSnapshot;
        @Schema(description = "仅无执行事实的CLOSE派工可排除；非null显示排除原因")
        private String exclusionReason;
        @Schema(description = "该派工完整生产段")
        private List<Segment> segments;
    }

    @Data
    @Accessors(chain = true)
    public static class Segment {
        @Schema(description = "段ID，文本")
        private String id;
        @Schema(description = "原租户ID，文本")
        private String tenantId;
        @Schema(description = "原工厂")
        private String factoryCode;
        @Schema(description = "原派工ID，文本")
        private String arrangeId;
        @Schema(description = "原派工编码")
        private String arrangeCode;
        @Schema(description = "原物理工单编码")
        private String taskCode;
        @Schema(description = "原工序任务编码")
        private String produceTaskCode;
        @Schema(description = "原工序编码")
        private String processCode;
        @Schema(description = "原产品编码")
        private String productCode;
        @Schema(description = "原段号，应从1连续")
        private Integer segmentNo;
        @Schema(description = "段类别，当前仅RUN")
        private String type;
        @Schema(description = "段状态")
        private String status;
        @Schema(description = "原开始时刻，ISO文本保留亚秒")
        private String startTime;
        @Schema(description = "原结束时刻，ISO文本保留亚秒")
        private String endTime;
        @Schema(description = "落账整秒时长，文本；不补造亚秒时长")
        private String durationSeconds;
        @Schema(description = "关闭原因，前段PAUSE/末段FINISH")
        private String closeReason;
        @Schema(description = "原样本资格")
        private Boolean eligible;
        @Schema(description = "原排除原因")
        private String exclusionReason;
        @Schema(description = "实际生产段工位")
        private String stationCode;
        @Schema(description = "原段冻结设备编码")
        private String deviceCode;
    }
}
