package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 移动端弱网报工授权凭证。
 *
 * <p>凭证只授权指定人员在指定工厂、派工和生产标识范围内暂存报工命令，
 * 不能替代恢复网络后的实时业务校验。令牌明文只在签发时返回，服务端仅保存摘要。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "移动端弱网报工短时授权凭证")
public class MesOfflineReportPermitDTO {

    @Schema(description = "授权凭证号")
    private String permitNo;

    @Schema(description = "一次性返回的随机令牌；客户端必须安全保存")
    private String permitToken;

    @Schema(description = "授权签发时间")
    private LocalDateTime issuedAt;

    @Schema(description = "授权失效时间")
    private LocalDateTime expiresAt;

    @Schema(description = "授权派工单号")
    private String arrangeCode;

    @Schema(description = "授权现场原始标识")
    private String transferCode;

    @Schema(description = "允许选择的实际工位")
    private List<String> allowedWorksiteCodes;

    @Schema(description = "冻结的执行策略指纹")
    private String contractFingerprint;
}
