package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesAndonRecordMapper extends MomBaseMapper<MesAndonRecordEntity> {

    /**
     * 安灯处理记录
     *
     * @param page
     * @param query
     * @return
     */
    IPage<AndonNoticeResp> noticePage(Page<AndonNoticeResp> page, @Param("query") AndonNoticeResp query, @Param("staffCode") String staffCode, @Param("states") List<String> stateList);

}