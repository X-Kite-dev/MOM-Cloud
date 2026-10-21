package com.kitex.mom.mes.dto;

/**
 * 原请求回执优先；expectedRevision保护处理/复核/重开之间的并发。
 */
public record MesShiftLeftoverCommand(String requestId, String id, Integer expectedRevision,
                                      String reason, String evidence, String newOwnerUserId) {
}
