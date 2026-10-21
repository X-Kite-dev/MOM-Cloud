package com.kitex.mom.iot.dto;
import lombok.Data;
import java.util.List;
@Data public class IotDataScopeCommand {
    private String requestId;
    private String reason;
    private String evidenceRefs;
    private Long deviceId;
    private String sourceKind;
    private List<Long> sourceIds;
    private String fingerprint;
    private Long claimId;
    private Boolean ownershipAttested;
}
