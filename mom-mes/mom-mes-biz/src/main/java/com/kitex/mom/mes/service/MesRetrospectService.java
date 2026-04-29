package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.RetrospectReqVo;
import com.kitex.mom.mes.dto.RetrospectVo;

/**
 * 生产追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
public interface MesRetrospectService {

    /**
     * 正向追溯
     *
     * @param retrospectVo
     * @return
     */
    RetrospectVo forward(RetrospectReqVo retrospectVo);

    /**
     * 正向追溯
     *
     * @param retrospectVo
     * @return
     */
    RetrospectVo reverse(RetrospectReqVo retrospectVo);

    /**
     * 半成品正向追溯
     *
     * @param retrospectVo
     * @return
     */
    RetrospectVo halfForward(RetrospectReqVo retrospectVo);

    /**
     * 半成品反向追溯
     *
     * @param retrospectVo
     * @return
     */
    RetrospectVo halfReverse(RetrospectReqVo retrospectVo);
}
