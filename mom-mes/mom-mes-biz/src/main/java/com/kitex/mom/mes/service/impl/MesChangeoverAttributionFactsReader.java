package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 换型归属的本地权威事实读取器，不调用实物命令、不按时间邻近猜派工。
 * 完整原凭证与稳定身份分开：正常接收/报工/完工推进不改变申请基线。
 */
@Component
@RequiredArgsConstructor
public class MesChangeoverAttributionFactsReader {
    private final MesChangeoverExecutionMapper executionMapper;
    private final MesTaskDeviceRecordMapper deviceRecordMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskMapper produceMapper;
    private final MesTaskMapper taskMapper;

    public static String station(MesTaskArrangeEntity row) {
        return StrUtil.isNotBlank(row.getActualWorksiteCode()) ? row.getActualWorksiteCode() : row.getPlannedWorksiteCode();
    }

    public static Long id(String text) {
        require(text != null && text.matches("[1-9][0-9]{0,18}"), "须指定未损失精度的正整数ID文本");
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            throw new CheckedException("ID超出数据库范围");
        }
    }

    /**
     * Long、原时间和数量递归转文本；保留null及源亚秒，不受全局JSON日期格式影响。
     */
    @SuppressWarnings("unchecked")
    public static Object exact(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer)
            return value;
        if (value instanceof Long || value instanceof LocalDateTime) return value.toString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Number) return value;
        if (value instanceof Enum<?> item) return item.name();
        if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        if (value instanceof Collection<?> list)
            return list.stream().map(MesChangeoverAttributionFactsReader::exact).toList();
        Map<String, Object> map = value instanceof Map<?, ?> ? (Map<String, Object>) value : BeanUtil.beanToMap(value);
        Map<String, Object> result = new TreeMap<>();
        map.forEach((key, item) -> result.put(key, exact(item)));
        return result;
    }

    private static Map<String, Object> select(Object value, String... keys) {
        var all = BeanUtil.beanToMap(value);
        Map<String, Object> selected = new TreeMap<>();
        for (String key : keys) selected.put(key, exact(all.get(key)));
        return selected;
    }

    public static String json(Object value) {
        return JSONUtil.toJsonStr(exact(value), JSONConfig.create().setIgnoreNullValue(false));
    }

    public static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256不可用", exception);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * 写侧仅非阻塞行锁；遇到旧执行链持锁，完整原号保留重试。
     */
    public Facts read(Long executionId, Long arrangeId, String factory, boolean lock) {
        Long tenant = TenantContextHolder.getTenantId();
        String tail = lock ? "LIMIT 2 FOR UPDATE NOWAIT" : "LIMIT 2";
        var sources = executionMapper.selectList(new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                .eq(MesChangeoverExecutionEntity::getTenantId, tenant)
                .eq(MesChangeoverExecutionEntity::getFactoryCode, factory)
                .eq(MesChangeoverExecutionEntity::getId, executionId).last(tail));
        var arrangements = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getId, arrangeId).last(tail));
        require(sources.size() == 1 && arrangements.size() == 1, "实际组织内未找到精确换型或派工");
        var source = sources.get(0);
        var arrange = arrangements.get(0);
        var produces = produceMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()).last(tail));
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last(tail));
        var records = source.getDeviceRecordId() == null ? List.<MesTaskDeviceRecordEntity>of()
                : deviceRecordMapper.selectList(new LambdaQueryWrapper<MesTaskDeviceRecordEntity>()
                .eq(MesTaskDeviceRecordEntity::getTenantId, tenant).eq(MesTaskDeviceRecordEntity::getFactoryCode, factory)
                .eq(MesTaskDeviceRecordEntity::getId, source.getDeviceRecordId()).last(tail));
        List<String> gaps = sourceGaps(source, records);
        if (produces.size() != 1 || tasks.size() != 1) gaps.add("派工缺唯一工序任务或工单，不能按编码猜身份");
        if (produces.size() == 1 && tasks.size() == 1) {
            var produce = produces.get(0);
            var task = tasks.get(0);
            if (!Objects.equals(arrange.getTaskCode(), produce.getTaskCode())
                    || !Objects.equals(arrange.getMatCode(), produce.getMatCode())
                    || !Objects.equals(arrange.getMatCode(), task.getMatCode())
                    || !Objects.equals(arrange.getRouteCode(), produce.getRouteCode())
                    || StrUtil.isBlank(arrange.getCode()) || StrUtil.isBlank(arrange.getTaskCode())
                    || StrUtil.isBlank(arrange.getProduceTaskCode()) || StrUtil.isBlank(arrange.getRouteCode())
                    || StrUtil.isBlank(arrange.getMatCode())) gaps.add("原工单/工序/产品身份缺失或不一致");
            if (!positive(arrange.getNoticeNum()) || !positive(produce.getNoticeNum())
                    || arrange.getNoticeNum().compareTo(produce.getNoticeNum()) > 0)
                gaps.add("派工原量须明确为正且不得超过原工序量");
            if ("CLOSE".equals(arrange.getStatus()) || "CLOSE".equals(produce.getStatus())
                    || "CLOSE".equals(task.getStatus()) || StrUtil.isBlank(arrange.getStatus()))
                gaps.add("关闭或状态未知的目标不能申请归属");
        }
        String station = station(arrange);
        if (StrUtil.isBlank(station) || StrUtil.isBlank(arrange.getPlannedDeviceCode())
                || !Objects.equals(station, source.getWorksiteCode())
                || !Objects.equals(arrange.getPlannedDeviceCode(), source.getDeviceCode()))
            gaps.add("实际/明确计划工位和计划设备必须与换型原资源一致");
        if (!"UN_RECEIVE".equals(arrange.getStatus()) && StrUtil.isBlank(arrange.getActualWorksiteCode()))
            gaps.add("已接收目标缺实际工位，不能用计划工位冒充");
        if (arrange.getStartTime() != null && (source.getCompletedTime() == null
                || source.getCompletedTime().isAfter(arrange.getStartTime())))
            gaps.add("换型完成晚于目标派工开工，不能归作开工前换型");
        if (StrUtil.isNotBlank(arrange.getPlannedToolingCodes())) {
            try {
                String text = arrange.getPlannedToolingCodes();
                var tools = text.trim().startsWith("[") ? JSONUtil.toList(text, String.class)
                        : List.of(text.split(","));
                if (!tools.contains(source.getToToolingCode())) gaps.add("目标工装不在派工冻结计划工装范围");
            } catch (RuntimeException exception) {
                gaps.add("目标冻结工装范围格式不可核验");
            }
        }
        Map<String, Object> sourceBody = new TreeMap<>();
        sourceBody.put("execution", exact(source));
        sourceBody.put("deviceRecords", records.stream().map(MesChangeoverAttributionFactsReader::exact).toList());
        Map<String, Object> targetBody = new TreeMap<>();
        targetBody.put("arrange", exact(arrange));
        targetBody.put("produceTasks", produces.stream().map(MesChangeoverAttributionFactsReader::exact).toList());
        targetBody.put("tasks", tasks.stream().map(MesChangeoverAttributionFactsReader::exact).toList());
        Map<String, Object> stable = new TreeMap<>();
        stable.put("source", sourceBody);
        stable.put("arrange", select(arrange, "id", "tenantId", "factoryCode", "code", "taskCode", "produceTaskCode",
                "routeCode", "matCode", "noticeNum", "plannedWorksiteCode", "plannedDeviceCode", "plannedToolingCodes", "apsOperationSnapshot"));
        stable.put("produceTasks", produces.stream().map(row -> select(row, "id", "tenantId", "factoryCode", "code",
                "taskCode", "matCode", "routeCode", "noticeNum", "processRouteId", "processRouteSnapshot", "apsOperationSnapshot")).toList());
        stable.put("tasks", tasks.stream().map(row -> select(row, "id", "tenantId", "factoryCode", "code",
                "matCode", "noticeNum", "processRouteId", "processRouteSnapshot", "apsOperationSnapshot")).toList());
        String sourceSnapshot = json(sourceBody);
        String targetSnapshot = json(targetBody);
        require(bytes(sourceSnapshot) + bytes(targetSnapshot) <= 1024 * 1024, "单份完整原凭证超过1MiB，不能裁剪核定");
        var preview = new MesChangeoverAttributionDTO.Preview().setExecutionId(executionId.toString())
                .setArrangeId(arrangeId.toString()).setSourceSnapshot(sourceSnapshot).setTargetSnapshot(targetSnapshot)
                .setExpectedFingerprint(hash(json(stable))).setEligible(gaps.isEmpty()).setGaps(gaps);
        return new Facts(source, arrange, preview);
    }

    /**
     * 对已完成本地账重新核查时间、原点检和唯一设备记录，人工勾选不能替代这些事实。
     */
    private List<String> sourceGaps(MesChangeoverExecutionEntity source, List<MesTaskDeviceRecordEntity> records) {
        List<String> gaps = new ArrayList<>();
        if (!"COMPLETED".equals(source.getStatus()) || !Boolean.TRUE.equals(source.getSampleEligible()))
            gaps.add("原换型未完成或初筛不可采信：" + Objects.toString(source.getExclusionReason(), "未注明"));
        boolean interval = source.getStartedTime() != null && source.getCompletedTime() != null
                && source.getCompletedTime().isAfter(source.getStartedTime())
                && !source.getCompletedTime().isAfter(LocalDateTime.now());
        if (!interval || source.getDurationSeconds() == null || source.getDurationSeconds() <= 0
                || source.getDurationSeconds() > 86400 || source.getDurationSeconds() != Duration.between(
                source.getStartedTime(), source.getCompletedTime()).getSeconds())
            gaps.add("原开始/完成/整秒缺失、倒置、未来或不守恒");
        if (StrUtil.isBlank(source.getWorksiteCode()) || StrUtil.isBlank(source.getDeviceCode())
                || StrUtil.isBlank(source.getFromToolingCode()) || StrUtil.isBlank(source.getToToolingCode())
                || Objects.equals(source.getFromToolingCode(), source.getToToolingCode())
                || StrUtil.isBlank(source.getStartedBy()) || StrUtil.isBlank(source.getCompletedBy()))
            gaps.add("源工位/设备/前后不同工装/原操作者必须明确");
        try {
            require(StrUtil.isNotBlank(source.getInspectionSnapshot()), "缺原点检正文");
            JSONUtil.parseArray(source.getInspectionSnapshot());
        } catch (RuntimeException exception) {
            gaps.add("原点检JSON不可核验");
        }
        if (records.size() != 1) gaps.add("原设备操作记录缺失或不唯一");
        else {
            var record = records.get(0);
            if (!Boolean.TRUE.equals(record.getChangeMold()) || !Objects.equals(record.getDeviceCode(), source.getDeviceCode())
                    || !Objects.equals(record.getWorksiteCode(), source.getWorksiteCode())
                    || !Objects.equals(record.getMoldCode(), source.getToToolingCode())
                    || !Objects.equals(record.getMoldDetails(), source.getInspectionSnapshot()))
                gaps.add("原设备操作的设备/工位/换模标志/目标工装/点检与计时账不一致");
        }
        return gaps;
    }

    public record Facts(MesChangeoverExecutionEntity source, MesTaskArrangeEntity arrange,
                        MesChangeoverAttributionDTO.Preview preview) {
    }
}
