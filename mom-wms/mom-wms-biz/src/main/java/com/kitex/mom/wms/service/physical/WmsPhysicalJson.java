package com.kitex.mom.wms.service.physical;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Command;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.*;
import java.util.*;

import static com.kitex.mom.wms.service.physical.WmsPhysicalRules.*;

/**
 * 本域严格JSON读取器；拒绝未知/缺失字段及数字转文本，不改全局Jackson配置。
 */
@Component
@RequiredArgsConstructor
public class WmsPhysicalJson {
	private final ObjectMapper mapper;

	/**
	 * 读取严格V1完整命令并核对业务ID、数量和随机原号；不接受隐式数字转换。
	 */
	public Command command(JsonNode node) {
		Command c = read(node, Command.class);
		commandCheck(c);
		return c;
	}

	/**
	 * 内部旧命令只扩定向父字段，拒绝JSON数字冒充新业务ID或未知属性。
	 */
	public com.kitex.mom.wms.dto.WmsQualityStatusCommand rehold(JsonNode n) {
		require(n != null && n.isObject(), "定向HOLD必须为对象");
		Set<String> fields = new HashSet<>();
		for (var f : com.kitex.mom.wms.dto.WmsQualityStatusCommand.class.getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			fields.add(f.getName());
			JsonNode v = n.get(f.getName());
			if (f.getType() == String.class && v != null && !v.isNull()) {
				require(v.isTextual() && v.textValue().indexOf(0) < 0, "定向HOLD业务字段必须为无NUL文本");
			}
		}
		n.fieldNames().forEachRemaining(f -> require(fields.contains(f), "定向HOLD存在未知属性"));
		require(n.hasNonNull("reholdOfPhysicalOperationId") && n.hasNonNull("reholdOfPhysicalRequestId"),
				"定向HOLD缺少父ID或父原号");
		try {
			return mapper.treeToValue(n, com.kitex.mom.wms.dto.WmsQualityStatusCommand.class);
		} catch (Exception e) {
			throw new CheckedException("定向HOLD命令无法读取");
		}
	}

	private void commandCheck(Command c) {
		WmsPhysicalRules.command(c);
	}

	/**
	 * 序列化本域永久原文；编码失败拒绝落账。
	 */
	public String encode(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new CheckedException("实物原文编码失败");
		}
	}

	/**
	 * 按明确类型读取数据库原文；缺失属性、未知属性或类型异常失败关闭。
	 */
	public <T> T decode(String text, Class<T> type) {
		try {
			return read(mapper.readTree(text), type);
		} catch (CheckedException e) {
			throw e;
		} catch (Exception e) {
			throw new CheckedException("实物永久原文无法读取");
		}
	}

	/**
	 * 在Jackson绑定前校验完整record结构，返回指定领域类型。
	 */
	public <T> T read(JsonNode node, Class<T> type) {
		shape(node, type);
		try {
			return mapper.treeToValue(node, type);
		} catch (Exception e) {
			throw new CheckedException("实物原文类型无效");
		}
	}

	private void shape(JsonNode n, Type type) {
		require(n != null, "原文缺少字段");
		if (n.isNull()) {
			return;
		}
		if (type == String.class) {
			require(n.isTextual() && n.textValue().indexOf(0) < 0, "业务ID、数量及原文必须是无NUL的JSON文本");
			return;
		}
		if (type == int.class) {
			require(n.isIntegralNumber() && n.canConvertToInt() && n.intValue() == 1, "版本必须是JSON整数1");
			return;
		}
		if (type instanceof ParameterizedType p) {
			if (p.getRawType() == List.class) {
				require(n.isArray(), "片段集合必须是数组");
				n.forEach(v -> shape(v, p.getActualTypeArguments()[0]));
				return;
			}
			if (p.getRawType() == Map.class) {
				require(n.isObject(), "身份必须是键值对象");
				n.elements().forEachRemaining(v -> {
							require(v.isTextual() && v.textValue().indexOf(0) < 0, "身份值必须是无NUL文本");
						}
				);
				return;
			}
		}
		if (type instanceof Class<?> c && c.isRecord()) {
			require(n.isObject(), "原文必须是对象");
			Set<String> names = new HashSet<>();
			for (RecordComponent f : c.getRecordComponents()) {
				names.add(f.getName());
				shape(n.get(f.getName()), f.getGenericType());
			}
			n.fieldNames().forEachRemaining(name -> require(names.contains(name), "原文存在未知字段：" + name));
		}
	}

}
