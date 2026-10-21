package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnMpsOperationEntity;
import com.kitex.mom.plan.entity.PlnMpsVersionEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** MPS单需求全量、跨需求滚动版本的受控命令。 */
public class PlnMpsCommand {

    /** 工厂 MPS 策略发布命令。 */
    @Data
    @Accessors(chain = true)
    public static class Policy {
        private String requestId;
        private String factoryCode;
        private Integer horizonDays;
        private Integer freezeDays;
        private Boolean enforceMps;
        /** RCCP产能门禁：OFF、WARN或BLOCK。 */
        private String rccpMode;
        /** 最大允许负荷率，百分比。 */
        private BigDecimal rccpMaxLoadRate;
    }
    /** 候选版本中的一条需求计划目标。 */
    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long demandId;
        /** 同一需求内从1开始且连续的跨期桶序号。 */
        private Integer bucketNo;
        /** 本时间桶承担的需求数量；同一需求所有桶之和必须等于需求全量。 */
        private BigDecimal planQuantity;
        private LocalDate plannedDate;
        private Integer mrpPriority;
        /**
         * 显式选择的RCCP产能资源。为空时采用默认工艺路线；非空时必须能在该产品的
         * 启用工艺路线中核验，禁止客户端提交任意产线编码。
         */
        private String capacityResourceCode;
    }
    /** MPS 滚动候选版本提交命令。 */
    @Data
    @Accessors(chain = true)
    public static class Submit {
        private String requestId;
        private String factoryCode;
        private LocalDate horizonStart;
        private LocalDate horizonEnd;
        private String reason;
        private List<Line> lines;
    }
    /** MPS 候选版本异岗复核命令。 */
    @Data
    @Accessors(chain = true)
    public static class Review {
        private String requestId;
        private Long versionId;
        private String evidenceFingerprint;
        private Boolean approved;
        private String reason;
    }
    /** 编制人取消待复核版本命令。 */
    @Data
    @Accessors(chain = true)
    public static class Cancel {
        private String requestId;
        private Long versionId;
        private String reason;
    }
    /** 已批准但跨模块执行失败的 MPS 版本续跑命令。 */
    @Data
    @Accessors(chain = true)
    public static class Retry {
        /** MPS 版本主键。 */
        private Long versionId;
        /** 本次人工续跑的永久请求号。 */
        private String requestId;
        /** 续跑依据与现场确认说明。 */
        private String reason;
    }
    /** 版本、需求行和不可变操作履历的聚合详情。 */
    @Data
    @Accessors(chain = true)
    public static class Detail {
        private PlnMpsVersionEntity version;
        private List<PlnMpsLineEntity> lines;
        private List<PlnMpsOperationEntity> operations;
    }
}
