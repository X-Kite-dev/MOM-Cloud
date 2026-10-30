package com.kitex.mom.mes.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Java与PostgreSQL共用的字面标识锁键。UTF-8十六进制消除JSON空格/转义、分隔符和代理对差异。
 * 先把所有标识转换成此键再排序，不能按Java UTF-16字符或SQL区域排序分别取锁。
 */
public final class MesIdentifierBindingLock {
    private MesIdentifierBindingLock() {
    }

    public static String key(Long tenant, String factory, String identifier) {
        return "MES_NODE_IDENTIFIER_LOCK_V2:" + tenant + ":" + hex(factory) + ":" + hex(identifier);
    }

    private static String hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
}
