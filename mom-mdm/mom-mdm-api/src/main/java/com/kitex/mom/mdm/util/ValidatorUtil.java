package com.kitex.mom.mdm.util;

import java.util.regex.Pattern;

/**
 * 校验工具类
 */
public class ValidatorUtil {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(^\\d{15}$)|(^\\d{18}$)|(^\\d{17}(\\d|X|x)$)");
    private ValidatorUtil() {
    }

    /**
     * 校验邮箱
     *
     * @param value 待校验字符串
     * @return 是否为合法邮箱
     */
    public static boolean isEmail(String value) {
        if (value == null) {
            return Boolean.TRUE;
        }
        String trimmed = value.trim();
        return EMAIL_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 校验手机号（中国大陆）
     *
     * @param value 待校验字符串
     * @return 是否为合法手机号
     */
    public static boolean isMobile(String value) {
        if (value == null) {
            return Boolean.TRUE;
        }
        String trimmed = value.trim();
        return MOBILE_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 校验身份证号（15位或18位，18位最后一位可为X/x）
     *
     * @param value 待校验字符串
     * @return 是否为合法身份证号
     */
    public static boolean isIdCard(String value) {
        if (value == null) {
            return Boolean.TRUE;
        }
        String trimmed = value.trim();
        return ID_CARD_PATTERN.matcher(trimmed).matches();
    }
}
