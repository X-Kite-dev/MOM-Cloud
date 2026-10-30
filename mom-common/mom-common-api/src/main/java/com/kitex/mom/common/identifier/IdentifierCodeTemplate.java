package com.kitex.mom.common.identifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物料标识编码模板的公共语法与渲染器。流水号由调用方持久化分配。
 */
public final class IdentifierCodeTemplate {
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._-]*");
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SEQUENCE = Pattern.compile("SEQ:([1-9]|1[0-2])");
    private static final Set<String> PARTS = Set.of(
            "TENANT", "FACTORY", "MATERIAL", "TYPE", "ATTRIBUTE", "GROUP");
    private static final Set<String> DATES = Set.of(
            "yyyyMM", "yyyyMMdd", "yyMMdd", "yyyyMMddHHmmss");

    private IdentifierCodeTemplate() {
    }

    public record Context(String tenant, String factory, String material,
                          String type, String attribute, String group) {
    }

    public static void validate(String template) {
        if (template == null || template.isBlank() || template.length() > 200) {
            throw new IllegalArgumentException("标识编码模板长度须为1至200字符");
        }
        Matcher matcher = TOKEN.matcher(template);
        int position = 0;
        int sequenceCount = 0;
        while (matcher.find()) {
            validateLiteral(template.substring(position, matcher.start()));
            String name = matcher.group(1);
            if (SEQUENCE.matcher(name).matches()) {
                sequenceCount++;
            } else if (name.startsWith("DATE:")) {
                if (!DATES.contains(name.substring(5))) {
                    throw new IllegalArgumentException("标识编码模板日期格式无效");
                }
            } else if (!PARTS.contains(name)) {
                throw new IllegalArgumentException("标识编码模板包含未知占位符: " + name);
            }
            position = matcher.end();
        }
        validateLiteral(template.substring(position));
        if (sequenceCount != 1) {
            throw new IllegalArgumentException("标识编码模板必须且只能包含一个流水号占位符");
        }
    }

    public static String render(String template, Context context, LocalDateTime time, long sequence) {
        validate(template);
        if (context == null || time == null || sequence <= 0) {
            throw new IllegalArgumentException("标识编码缺少上下文、时间或有效流水号");
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder code = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            code.append(template, position, matcher.start());
            String name = matcher.group(1);
            String value;
            if (name.startsWith("SEQ:")) {
                int width = Integer.parseInt(name.substring(4));
                value = String.format(Locale.ROOT, "%0" + width + "d", sequence);
            } else if (name.startsWith("DATE:")) {
                value = time.format(DateTimeFormatter.ofPattern(name.substring(5), Locale.ROOT));
            } else {
                value = part(context, name);
                if (value == null || !PART.matcher(value).matches()) {
                    throw new IllegalArgumentException("标识编码的" + name + "值为空或包含非法字符");
                }
            }
            code.append(value);
            position = matcher.end();
        }
        code.append(template, position, template.length());
        if (code.length() > 100) {
            throw new IllegalArgumentException("生成的标识编码超过100字符");
        }
        return code.toString();
    }

    private static void validateLiteral(String literal) {
        if (!LITERAL.matcher(literal).matches()) {
            throw new IllegalArgumentException("标识编码模板只允许字母、数字、点、横线、下划线和受支持的占位符");
        }
    }

    private static String part(Context context, String name) {
        return switch (name) {
            case "TENANT" -> context.tenant();
            case "FACTORY" -> context.factory();
            case "MATERIAL" -> context.material();
            case "TYPE" -> context.type();
            case "ATTRIBUTE" -> context.attribute();
            case "GROUP" -> context.group();
            default -> throw new IllegalArgumentException("未知标识编码字段: " + name);
        };
    }
}
