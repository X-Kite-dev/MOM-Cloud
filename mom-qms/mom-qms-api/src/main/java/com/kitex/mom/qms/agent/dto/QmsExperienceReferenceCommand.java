package com.kitex.mom.qms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 人工明确认定适用边界后引用历史经验，不改变根因、措施、验证和关闭状态。
 */
public record QmsExperienceReferenceCommand(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{8,100}") String requestId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String capaId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String experienceId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String documentId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String documentContentHash,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCaseFingerprint,
        @NotBlank @Size(max = 1000) String applicabilityReview,
        @NotBlank @Size(max = 1000) String differences,
        @NotBlank @Size(max = 1000) String exclusionReview) {
}
