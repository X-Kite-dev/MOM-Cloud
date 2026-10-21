package com.kitex.mom.mes.extension;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 以确定顺序执行所有适用的报工行业扩展，并冻结实际执行证据。
 */
@Component
public class MesReportExtensionChain {
    private final List<MesReportExtension> extensions;

    public MesReportExtensionChain(List<MesReportExtension> extensions) {
        this.extensions = extensions.stream()
                .sorted(Comparator.comparingInt(MesReportExtension::order)
                        .thenComparing(MesReportExtension::code))
                .toList();
        Set<String> codes = new HashSet<>();
        for (MesReportExtension extension : this.extensions) {
            if (StrUtil.isBlank(extension.code()) || !codes.add(extension.code())) {
                throw new IllegalStateException("报工扩展编码为空或重复: " + extension.code());
            }
        }
    }

    public String validate(MesReportExtensionContext context) {
        List<Map<String, Object>> evidence = extensions.stream()
                .filter(extension -> extension.supports(context))
                .map(extension -> {
                    extension.validate(context);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", extension.code());
                    item.put("version", extension.version());
                    item.put("evidence", extension.evidence(context));
                    return item;
                }).toList();
        return evidence.isEmpty() ? null : JSONUtil.toJsonStr(evidence);
    }

    public void afterPersist(MesReportExtensionContext context, MesTaskReportEntity report) {
        extensions.stream().filter(extension -> extension.supports(context))
                .forEach(extension -> extension.afterPersist(context, report));
    }
}
