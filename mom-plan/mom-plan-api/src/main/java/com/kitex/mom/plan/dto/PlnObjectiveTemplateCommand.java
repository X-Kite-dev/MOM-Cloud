package com.kitex.mom.plan.dto;

import lombok.Data;

import java.util.List;

@Data
public class PlnObjectiveTemplateCommand {
    private Long id;
    private String requestId;
    private String templateCode;
    private String templateName;
    private String industryCode;
    private String reason;
    private List<PlnSchedulingCandidateProfileDTO> profiles;
}
