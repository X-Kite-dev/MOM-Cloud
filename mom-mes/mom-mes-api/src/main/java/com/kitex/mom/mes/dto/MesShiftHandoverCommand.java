package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class MesShiftHandoverCommand {
    /**
     * 永久命令号；结果未知时必须沿原号核账。
     */
    private String requestId;
    private Long id;
    private String shiftLabel;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowEnd;
    private List<String> arrangeCodes;
    private String expectedFingerprint;
    private String reason;
    /**
     * 真实MDM班次编码及所属日期，跨午夜仍使用开始日期。
     */
    private String sourceShiftCode;
    private LocalDate sourceShiftDate;
    private String targetShiftCode;
    private LocalDate targetShiftDate;
    /**
     * 一名指定接班负责人，代表接班班组签收，不表示全员签字。
     */
    private String designatedReceiverId;
    /**
     * 班次、人员及现场声明的预览摘要，和账面数量指纹分开核验。
     */
    private String expectedFormalFingerprint;
    /**
     * 现场人工声明，不冒充设备/质量/仓储系统的自动证据。
     */
    private String safetyNote;
    private String equipmentNote;
    private String qualityNote;
    private String materialNote;
    /**
     * 本次新增遗留事项，或从之前交接按原事项ID结转；不复制独立责任。
     */
    private List<LeftoverDraft> leftoverItems;
    private List<String> carryForwardIds;

    @Data
    public static class LeftoverDraft {
        private String clientItemKey;
        private String category;
        private String title;
        private String description;
        private String arrangeCode;
        private String sourceType;
        private String ownerUserId;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime dueAt;
    }
}
