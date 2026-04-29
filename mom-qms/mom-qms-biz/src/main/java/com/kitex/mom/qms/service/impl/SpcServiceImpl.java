package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.qms.dto.QmsHistorySpcDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.enums.CheckTypeEnum;
import com.kitex.mom.qms.mapper.SpcMapper;
import com.kitex.mom.qms.service.QualityStandardItemService;
import com.kitex.mom.qms.service.QualityStandardService;
import com.kitex.mom.qms.service.SpcService;
import com.kitex.mom.qms.vo.QmsHistorySpcVO;
import groovy.util.logging.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * spc控制图
 *
 * @author kitex
 * @since 2025-04-28 09:18:17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpcServiceImpl implements SpcService {

    private final QualityStandardService qualityStandardService;

    private final QualityStandardItemService qualityStandardItemService;

    private final SpcMapper spanMapper;

    // 计算平均值
    public static double calculateAverageRange(List<Double> ranges) {
        double sum = 0;
        for (double range : ranges) {
            sum += range;
        }
        return sum / ranges.size();
    }

    // 计算标准差
    public static double calculateStandardDeviation(List<Double> data, double mean) {
        double sumSquaredDiff = 0;
        for (double value : data) {
            sumSquaredDiff += Math.pow(value - mean, 2);
        }
        return Math.sqrt(sumSquaredDiff / (data.size() - 1));
    }

    // 计算CP值
    public static double calculateCp(double lowerSpecLimit, double upperSpecLimit, double stdDev) {
        return (upperSpecLimit - lowerSpecLimit) / (6 * stdDev);
    }

    // 计算CPK值
    public static double calculateCpk(double lowerSpecLimit, double upperSpecLimit, double mean, double stdDev) {
        double cpu = (upperSpecLimit - mean) / (3 * stdDev);
        double cpl = (mean - lowerSpecLimit) / (3 * stdDev);
        return Math.min(cpu, cpl);
    }

    /**
     * 根据产品编码查询产品质检项（只获取范围质检项）
     *
     * @param matCode 产品编码
     */
    @Override
    public List<QmsQualityStandardItemEntity> qualityItem(String matCode) {
        List<Long> qualityStandardIds = qualityStandardService.list(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                        .eq(QmsQualityStandardEntity::getMatCode, matCode))
                .stream().map(QmsQualityStandardEntity::getId).collect(Collectors.toList());
        if (qualityStandardIds.isEmpty()) {
            return new ArrayList<>();
        }
        return qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .in(QmsQualityStandardItemEntity::getStandardId, qualityStandardIds)
                .eq(QmsQualityStandardItemEntity::getCheckType, CheckTypeEnum.RANG.name()));
    }

    /**
     * 根据质检项查询质检记录
     */
    @Override
    public Map<String, Object> qualityHistory(Long id) {
        Map<String, Object> map = new HashMap<>();
        // 查询质检标准的上下限
        QmsQualityStandardItemEntity qmsQualityStandardItemEntity = qualityStandardItemService.getById(id);
        map.put("upper", qmsQualityStandardItemEntity.getUpperLimit());
        map.put("lower", qmsQualityStandardItemEntity.getLowerLimit());
        // 查询质检历史
        List<QmsHistorySpcVO> list = spanMapper.qualityHistory(id);
        if (list != null && !list.isEmpty()) {
            map.put("historyList", list);
        }
        return map;
    }

    /**
     * x-bar控制图
     */
    @Override
    public Map<String, Object> chart(QmsHistorySpcDTO qmsHistorySpcDTO) {
        // 构造返回map
        Map<String, Object> map = new HashMap<>();
        // 质检数据预处理
        Double[] inspectionData = this.inputDataHandler(qmsHistorySpcDTO.getInputData());

        int subgroupSize = 4; // 国标推荐spc子组大小

        // 组装返回的数据
        List<Double> xAxisData = new ArrayList<>(); // x轴
        List<Double> xBarYAxisData = new ArrayList<>(); // x-bar控制图y轴数据
        List<Double> rYAxisData = new ArrayList<>(); // r控制图y轴数据

        // 分组并计算子组平均值和极差
        for (int i = 0; i < inspectionData.length; i += subgroupSize) {
            int end = Math.min(i + subgroupSize, inspectionData.length);
            double sum = 0;
            double max = Double.MIN_VALUE;
            double min = Double.MAX_VALUE;
            for (int j = i; j < end; j++) {
                sum += inspectionData[j];
                if (inspectionData[j] > max) {
                    max = inspectionData[j];
                }
                if (inspectionData[j] < min) {
                    min = inspectionData[j];
                }
            }
            double subgroupAverage = sum / (end - i);
            double subgroupRange = max - min;
            xAxisData.add((double) (i / subgroupSize + 1)); // 子组编号
            xBarYAxisData.add(subgroupAverage);
            rYAxisData.add(subgroupRange);
        }

        // 计算X - bar控制图的总体平均值和控制限
        double xBarOverallAverage = 0;
        for (double avg : xBarYAxisData) {
            xBarOverallAverage += avg;
        }
        xBarOverallAverage /= xBarYAxisData.size();

        double averageRange = calculateAverageRange(rYAxisData);
        double a2 = 0.729; // A2常数，子组大小为4时的值
        double xBarUCL = xBarOverallAverage + a2 * averageRange;
        double xBarCL = xBarOverallAverage;
        double xBarLCL = xBarOverallAverage - a2 * averageRange;

        // 计算R控制图的控制限
        double d3 = 0; // D3常数，子组大小为4时的值
        double d4 = 2.282; // D4常数，子组大小为4时的值
        double rUCL = d4 * averageRange;
        double rCL = averageRange;
        double rLCL = d3 * averageRange;

        // 使用DecimalFormat格式化输出
        DecimalFormat df = new DecimalFormat("#.0");

        // 组装x轴数据
        map.put("x", xAxisData);
        // 组装x-bar控制图返回的数据
        Map<String, Object> xyMap = new HashMap<>();
        xyMap.put("XBarY", xBarYAxisData);
        xyMap.put("UCL", df.format(xBarUCL));
        xyMap.put("CL", df.format(xBarCL));
        xyMap.put("LCL", df.format(xBarLCL));
        map.put("xy", xyMap);

        // 组装R控制图返回的数据
        Map<String, Object> xrMap = new HashMap<>();
        xrMap.put("R", rYAxisData);
        xrMap.put("UCL", df.format(rUCL));
        xrMap.put("CL", df.format(rCL));
        xrMap.put("LCL", df.format(rLCL));
        map.put("ry", xrMap);

        return map;
    }

    /**
     * 数据分布直方图
     */
    @Override
    public Map<String, Object> hist(QmsHistorySpcDTO qmsHistorySpcDTO) {
        Map<String, Object> map = new HashMap<>();
        Double[] inspectionData = this.inputDataHandler(qmsHistorySpcDTO.getInputData());
        // 生成数据分布直方图
        int binCount = 4;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double value : inspectionData) {
            if (value < min) {
                min = value;
            }
            if (value > max) {
                max = value;
            }
        }

        double binWidth = (max - min) / binCount;

        int[] frequency = new int[binCount];
        List<String> xAxisData = new ArrayList<>();
        List<Integer> yAxisData = new ArrayList<>();

        for (double value : inspectionData) {
            int binIndex = (int) ((value - min) / binWidth);
            if (binIndex == binCount) {
                binIndex--; // 处理边界情况
            }
            frequency[binIndex]++;
        }

        DecimalFormat df = new DecimalFormat("#.0");
        for (int i = 0; i < binCount; i++) {
            double lowerBound = min + i * binWidth;
            double upperBound = min + (i + 1) * binWidth;
            xAxisData.add(df.format(lowerBound) + " - " + df.format(upperBound));
            yAxisData.add(frequency[i]);
        }

        map.put("x", xAxisData);
        map.put("y", yAxisData);
        return map;
    }

    /**
     * 统计信息
     */
    @Override
    public Map<String, Object> stat(QmsHistorySpcDTO qmsHistorySpcDTO) {
        Map<String, Object> map = new HashMap<>();
        Double[] inspectionData = this.inputDataHandler(qmsHistorySpcDTO.getInputData());
        List<Double> inspectionList = Arrays.asList(inspectionData);
        double lowerSpecLimit = qmsHistorySpcDTO.getLower(); // 下规格限
        double upperSpecLimit = qmsHistorySpcDTO.getUpper(); // 上规格限
        // 计算SPC统计信息
        double mean = calculateAverageRange(inspectionList);
        double stdDev = calculateStandardDeviation(inspectionList, mean);
        double cp = calculateCp(lowerSpecLimit, upperSpecLimit, stdDev);
        double cpk = calculateCpk(lowerSpecLimit, upperSpecLimit, mean, stdDev);

        // 使用DecimalFormat格式化输出
        DecimalFormat df = new DecimalFormat("0.0");
        map.put("mean", df.format(mean));
        map.put("std", df.format(stdDev));
        map.put("cp", df.format(cp));
        map.put("cpk", df.format(cpk));
        return map;
    }

    /**
     * 数据预处理
     */
    private Double[] inputDataHandler(String data) {
        String[] inputData = data.split(",");
        Double[] inspectionData = new Double[inputData.length];

        for (int i = 0; i < inputData.length; i++) {
            inspectionData[i] = Double.parseDouble(inputData[i].trim());
        }
        return inspectionData;
    }
}
