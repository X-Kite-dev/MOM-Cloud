package com.kitex.mom.mes.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 永久原号、实际归属与原观察的追加说明；不提供更新或删除入口。
 */
@Data
public class MesDispatchHistoryNoteEntity {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long tenantId;
    private String factoryCode;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String requestId;
    private String requestHash;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long actorId;
    private String actorCode;
    private String note;
    private String sourceFingerprint;
    private String sourceSnapshot;
    private LocalDateTime createTime;
    private String delFlag;
}
