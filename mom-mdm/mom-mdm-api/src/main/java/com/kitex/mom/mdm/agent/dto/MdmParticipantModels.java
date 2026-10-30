package com.kitex.mom.mdm.agent.dto;

import java.util.List;

/**
 * 工厂协作参与人安全合同，所有业务长ID以文本传输。
 */
public final class MdmParticipantModels {
    private MdmParticipantModels() {
    }

    /**
     * purpose仅允许OPERATIONS/HANDOVER；分页先做完整授权交集，再计算真实总数。
     */
    public record Search(String factoryCode, String purpose, String keyword, long current, long size) {
    }

    /**
     * 完整校验明确选择的1至20个ID；任意无权或失效均拒绝整个请求。
     */
    public record Verify(String factoryCode, String purpose, List<String> userIds) {
    }

    /**
     * 身份和办理权限来自账号服务，工厂资格来自当前启用的工厂绑定。
     */
    public record Participant(String userId, String username, String displayName, String departmentId,
                              String factoryCode, String purpose, List<String> authorities, String fingerprint) {
    }

    /**
     * 不返回截断后的虚假总数；授权候选过大时明确要求缩小绑定范围。
     */
    public record ParticipantPage(long current, long size, long total, List<Participant> records) {
    }
}
