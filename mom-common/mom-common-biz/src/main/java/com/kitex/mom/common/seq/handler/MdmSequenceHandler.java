package com.kitex.mom.common.seq.handler;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.sequence.builder.DbSeqBuilder;
import com.kitex.common.sequence.properties.BaseSequenceProperties;
import com.kitex.common.sequence.sequence.Sequence;
import com.kitex.mom.common.seq.entity.MdmSeqConfigEntity;
import com.kitex.mom.common.seq.service.MdmSeqConfigService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@AllArgsConstructor
public class MdmSequenceHandler {

    private final MdmSeqConfigService sqConfigService;

    private final DataSource dataSource;

    private final BaseSequenceProperties properties;

    private final Map<String, Sequence> sequenceMap = new HashMap<String, Sequence>();


    public Sequence createSeq(String type) {
        // 根据type 查询需要够早的bean类型
        if (!sequenceMap.containsKey(type)) {
            init();
        }
        if (!sequenceMap.containsKey(type)) {
            throw new CheckedException(String.format("不存在该类型[%s]的序列号", type));
        }
        return sequenceMap.get(type);
    }

    void init() {

        List<MdmSeqConfigEntity> list = sqConfigService
                .list(Wrappers.<MdmSeqConfigEntity>lambdaQuery().eq(MdmSeqConfigEntity::getEnable, Boolean.TRUE));
        sequenceMap.clear();
        list.forEach(item -> {
            Sequence sequence = DbSeqBuilder.create().dataSource(dataSource).step(properties.getStep())
                    .retryTimes(properties.getDb().getRetryTimes())
                    .tableName(properties.getDb().getTableName())
                    .bizName(() -> {
                        if (item.getDayNew()) {
                            return String.format("%s_%s", item.getBizName(), DateUtil.today());
                        } else {
                            return String.format("%s", item.getBizName());
                        }
                    }).prefixName(() -> {
                        if (StrUtil.isEmpty(item.getPrefix())) {
                            return "";
                        } else {
                            return String.format("%s", item.getPrefix());
                        }
                    })
                    .seqLength(item::getNumLength)
                    .build();
            sequenceMap.put(item.getBizName(), sequence);
        });

    }

}
