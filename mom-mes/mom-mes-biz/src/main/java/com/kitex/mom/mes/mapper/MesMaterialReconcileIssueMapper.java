package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.dto.MesMaterialReconcileEvidenceDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MesMaterialReconcileIssueMapper extends MomBaseMapper<MesMaterialReconcileIssueEntity> {
    List<MesMaterialReconcileEvidenceDTO> selectEvidence();
}
