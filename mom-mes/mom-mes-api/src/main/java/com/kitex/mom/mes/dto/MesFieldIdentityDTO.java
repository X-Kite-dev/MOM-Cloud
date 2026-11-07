package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 现场刷卡仅为已登录本人提供交接命令的附加认证；不提供登录或卡号授权。
 */
public final class MesFieldIdentityDTO {
	private MesFieldIdentityDTO() {
	}

	/**
	 * 一次挑战绑定的原交接命令，禁止携带其他业务用途。
	 */
	@Data
	public static class ChallengeCommand {
		/**
		 * 原交接动作：SUBMIT、ACCEPT、REJECT或WITHDRAW。
		 */
		private String action;
		/**
		 * 原交接完整命令，包含结果未知时必须保留的永久请求号。
		 */
		private MesShiftHandoverCommand command;
	}

	/**
	 * 厂商证明只在本次请求内验签，不写数据库及日志。
	 */
	@Data
	public static class AuthenticateCommand extends ChallengeCommand {
		/**
		 * 服务端生成的一次性短期挑战号。
		 */
		private String challengeId;
		/**
		 * 厂商RS256签名证明；禁止使用卡号或客户端自报成功代替。
		 */
		private String proof;
	}

	/**
	 * 返回给受控读卡SDK的完整绑定依据。
	 *
	 * @param challengeId          服务端随机挑战号
	 * @param tenantId             当前实名租户文本ID
	 * @param factoryCode          已核授权的实际工厂
	 * @param actorId              已登录本人文本ID，须与证明sub一致
	 * @param action               原交接动作
	 * @param requestId            原交接请求号
	 * @param commandSha256        服务端规范化原命令摘要
	 * @param expiresAtEpochSecond 挑战失效UTC秒
	 * @param mode                 REAL真实证明或SIMULATION显式模拟
	 */
	public record Challenge(String challengeId, String tenantId, String factoryCode, String actorId,
	                        String action, String requestId, String commandSha256,
	                        long expiresAtEpochSecond, String mode) {
	}

	/**
	 * 认证账与交接账分开返回；accepted不表示业务成功。
	 *
	 * @param authenticationStatus 固定AUTHENTICATION_ACCEPTED，仅表示附加认证受理
	 * @param challengeId          已受理的原挑战号
	 * @param mode                 冻结的真实或模拟通道
	 * @param handoverReceipt      原交接服务的实际结果
	 */
	public record Result(String authenticationStatus, String challengeId, String mode, Object handoverReceipt) {
	}
}
