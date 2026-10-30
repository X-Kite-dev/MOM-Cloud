package com.kitex.mom.mdm.sop;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 正式SOP公共身份、长度和稳定摘要规则；不信任前端租户或人员字段。
 */
@Component
@RequiredArgsConstructor
public class MdmSopSupport {
    private final MdmAgentActualScopeAccess actualScope;

    public static Long id(String value, String label) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), label + "必须是正整数字符串");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new CheckedException(label + "超出标识范围");
        }
    }

    public static String text(String value, int limit, String label, boolean required) {
        String result = value == null ? "" : value.trim();
        require((!required || !result.isBlank()) && result.length() <= limit
                && result.indexOf('\0') < 0, label + "不能为空或超出" + limit + "字符限制");
        return result;
    }

    public static String hash(Object... parts) {
        return digest(JSONUtil.toJsonStr(Arrays.asList(parts)).getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("当前运行环境不支持SHA-256", ex);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public String access(boolean write) {
        String factory = FactoryContextHolder.getFactoryCode();
        actualScope.require(factory, write ? "write" : "read");
        return factory;
    }

    public String sourceScope(String requestedFactory) {
        String factory = access(false);
        require(Objects.equals(factory, requestedFactory), "SOP来源请求工厂与真实当前工厂不一致");
        return factory;
    }

    public Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    public String actor() {
        return SecurityUtils.getUser().getId().toString();
    }

    public String actorName() {
        return Objects.toString(SecurityUtils.getUser().getName(), "");
    }
}
