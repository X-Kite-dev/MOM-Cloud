package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.RetrospectReqVo;
import com.kitex.mom.mes.dto.RetrospectVo;
import com.kitex.mom.mes.service.MesRetrospectService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
@Tag(description = "mesRetrospect", name = "生产追溯")
@RestController
@RequestMapping("/mesRetrospect")
@RequiredArgsConstructor
public class MesRetrospectController {

    private final MesRetrospectService mesRetrospectService;

    /**
     * 正向追溯
     *
     * @param retrospectReqVo
     * @return
     */
    @GetMapping("/forward")
    public R<RetrospectVo> forward(RetrospectReqVo retrospectReqVo) {
        return R.ok(mesRetrospectService.forward(retrospectReqVo));
    }


    /**
     * 反向追溯
     *
     * @param retrospectReqVo
     * @return
     */
    @GetMapping("/reverse")
    public R<RetrospectVo> reverse(RetrospectReqVo retrospectReqVo) {
        return R.ok(mesRetrospectService.reverse(retrospectReqVo));
    }

    /**
     * 半成品正向追溯
     *
     * @param retrospectReqVo
     * @return
     */
    @GetMapping("/halfForward")
    public R<RetrospectVo> halfForward(RetrospectReqVo retrospectReqVo) {
        return R.ok(mesRetrospectService.halfForward(retrospectReqVo));
    }


    /**
     * 半成品反向追溯
     *
     * @param retrospectReqVo
     * @return
     */
    @GetMapping("/halfReverse")
    public R<RetrospectVo> halfReverse(RetrospectReqVo retrospectReqVo) {
        return R.ok(mesRetrospectService.halfReverse(retrospectReqVo));
    }
}
