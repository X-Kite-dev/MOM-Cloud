package com.kitex.mom.mes.batch;

import com.kitex.mom.mes.dto.BatchGeneratorTypeDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BatchGeneratorService {

    private final Map<String, BatchGenerator> batchGeneratorMap;

    @Autowired
    public BatchGeneratorService(List<BatchGenerator> batchGenerators) {
        this.batchGeneratorMap = batchGenerators.stream()
                .collect(Collectors.toMap(
                        BatchGenerator::getGeneratorType,
                        generator -> generator
                ));
    }

    /**
     * 获取所有批次生成器
     *
     * @return Map<生成器类型, 生成器实例>
     */
    public Map<String, BatchGenerator> getAllBatchGenerators() {
        return batchGeneratorMap;
    }

    /**
     * 获取所有批次生成器类型列表
     *
     * @return 批次生成器类型列表
     */
    public List<BatchGeneratorTypeDTO> getAllBatchGeneratorTypes() {
        return batchGeneratorMap.values().stream()
                .map(generator -> {
                    BatchGeneratorTypeDTO dto = new BatchGeneratorTypeDTO();
                    dto.setCode(generator.getGeneratorType());
                    dto.setName(generator.getGeneratorName());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 根据类型获取批次生成器
     *
     * @param type 生成器类型
     * @return 批次生成器实例
     */
    public BatchGenerator getBatchGenerator(String type) {
        return batchGeneratorMap.get(type);
    }

}
