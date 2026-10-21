package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MesApsLocalScheduleDTO {
    private MesApsLocalScheduleDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class StateBundle {
        private String planNo;
        private String factoryCode;
        private Boolean mesTaskPresent;
        private String executionFingerprint;
        private List<OperationState> operations;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationState {
        private Long produceTaskId;
        private String produceTaskCode;
        private String taskCode;
        private String routeCode;
        /**
         * 实际MES任务出生时冻结的路线节点，不从当前MDM补值。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceRouteNodeCode;
        private String routeName;
        private Integer routeOrder;
        private String status;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualFinishTime;
        /**
         * 原实际时刻的ISO文本；新增只读取证字段，不更改既有执行指纹算法。
         */
        private String actualStartTimeText;
        /**
         * 保留亚秒，不经旧LocalDateTime全局格式化截断。
         */
        private String actualFinishTimeText;
        private BigDecimal completeQuantity;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private Boolean movable;
        private String blockedReason;
    }

    @Data
    @Accessors(chain = true)
    public static class ApplyCommand {
        private String requestId;
        private String planNo;
        private String factoryCode;
        private String expectedExecutionFingerprint;
        private Long replanCaseId;
        private List<OperationUpdate> operations;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationUpdate {
        private String routeCode;
        /**
         * 明确本次路线节点；缺省旧命令只允许原主工序唯一对应。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceRouteNodeCode;
        /**
         * 可选实际任务身份；提供时必须同时匹配，不能只凭主工序覆盖。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Long produceTaskId;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String produceTaskCode;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private String resourceCode;
        private String resourceName;
        private Integer resourceSlotNo;
        private String resourceGroupCode;
        private String resourceGroupName;
        private Integer resourceGroupCapacity;
        private Integer resourceGroupSlotNo;
        private String deviceCode;
        private String deviceName;
        private String employeeCode;
        private String employeeName;
        private String toolingCodes;
        private String toolingNames;
        private String toolingAllocationSnapshot;
        private String toolingLifeUsageSnapshot;
        private String apsOperationSnapshot;
    }

    @Data
    @Accessors(chain = true)
    public static class ApplyReceipt {
        private String requestId;
        private String planNo;
        private Long replanCaseId;
        private Integer updatedOperationCount;
        private String beforeExecutionFingerprint;
        private String afterExecutionFingerprint;
        private LocalDateTime appliedTime;
    }
}
