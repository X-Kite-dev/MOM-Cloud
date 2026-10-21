package com.kitex.mom.mes.dto;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * MES生产工单接收请求签名器。
 *
 * <p>所有上游在调用MES前使用同一算法生成稳定请求号和完整载荷指纹，避免各模块
 * 自行拼接摘要导致同一请求在重试时出现不同口径。</p>
 */
public final class MesTaskReceiveRequestSigner {

    private MesTaskReceiveRequestSigner() {
    }

    public static MesTaskReceiveDTO seal(MesTaskReceiveDTO command, String requestId) {
        if (command == null || StrUtil.isBlank(requestId) || requestId.trim().length() > 100) {
            throw new IllegalArgumentException("MES工单接收命令和1至100位请求号不能为空");
        }
        command.setSourceRequestId(requestId.trim());
        command.setSourceRequestFingerprint(null);
        command.setSourceRequestFingerprint(sha256(JSONUtil.toJsonStr(command)));
        return command;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("MES工单接收请求指纹生成失败", exception);
        }
    }
}
