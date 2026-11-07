package com.kitex.mom.mes.service.impl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 仅配置现场交接证明的公钥信任；不保存私钥、卡密或登录凭据，默认关闭。
 * 每一信任项须精确匹配实际租户和工厂，重复范围拒绝启用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mes.field-identity")
public class MesFieldIdentityProperties {
	/**
	 * 显式启用附加认证入口；旧实名交接入口不受该开关影响。
	 */
	private boolean enabled;
	/**
	 * 单次挑战有效秒数，限定30至180秒。
	 */
	private int challengeSeconds = 90;
	/**
	 * 按实际组织配置的独立信任项，不提供通配或默认租户。
	 */
	private List<Trust> trusts = new ArrayList<>();

	/**
	 * 厂商公钥及设备用途的组织级信任合同。
	 */
	@Data
	public static class Trust {
		/**
		 * 实际租户ID，不能为空。
		 */
		private Long tenantId;
		/**
		 * 实际工厂编码，不能为空。
		 */
		private String factoryCode;
		/**
		 * REAL或SIMULATION；模拟必须显式配置独立组织范围。
		 */
		private String mode = "REAL";
		/**
		 * 厂商签名者iss精确值。
		 */
		private String issuer;
		/**
		 * 本应用aud精确值。
		 */
		private String audience;
		/**
		 * 厂商公钥kid精确值，不从JWT提供的URL读取密钥。
		 */
		private String keyId;
		/**
		 * X.509 PEM RSA公钥，至少2048位。
		 */
		private String publicKeyPem;
		/**
		 * 允许的厂商设备标识精确列表，空列表拒绝所有设备。
		 */
		private List<String> deviceIds = new ArrayList<>();
	}
}
