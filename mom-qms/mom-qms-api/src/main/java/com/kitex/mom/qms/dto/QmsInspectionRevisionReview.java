package com.kitex.mom.qms.dto;

/** 审核意见；不携带可冒用的审核人或执行许可。 */
public record QmsInspectionRevisionReview(String decision, String note) {}
