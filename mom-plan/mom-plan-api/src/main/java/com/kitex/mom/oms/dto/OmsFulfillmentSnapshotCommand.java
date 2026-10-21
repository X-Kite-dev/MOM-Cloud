package com.kitex.mom.oms.dto;

import lombok.Data;

/** 未知结果重试必须复用原请求号和原原因。 */
public class OmsFulfillmentSnapshotCommand {

    @Data
    public static class Capture {
        private String requestId;
        private String reason;
    }
}
