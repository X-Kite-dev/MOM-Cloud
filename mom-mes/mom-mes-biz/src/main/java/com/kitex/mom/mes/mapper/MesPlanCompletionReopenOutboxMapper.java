package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/** MES完工重新打开PLAN回写Outbox访问层。 */
@Mapper
public interface MesPlanCompletionReopenOutboxMapper
        extends MomBaseMapper<MesPlanCompletionReopenOutboxEntity> {
}
