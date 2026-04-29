package com.kitex.mom.qms.mapper;

import com.kitex.mom.qms.vo.QmsHistorySpcVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * spc控制图
 *
 * @author kitex
 * @since 2025-04-28 09:18:17
 */
@Mapper
public interface SpcMapper {

    List<QmsHistorySpcVO> qualityHistory(@Param("id") Long id);
}
