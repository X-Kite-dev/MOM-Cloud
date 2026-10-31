package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Details;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Query;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Statistics;
import com.kitex.mom.mes.service.impl.MesPlanAttainmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 当前计划到期队列与来源下钻，只读且沿用派工查看权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPlanAttainment")
public class MesPlanAttainmentController {

    private final MesPlanAttainmentService service;

    /**
     * 本接口仅接受上海业务本地ISO时间，拒绝偏移量及超过数据库微秒精度的时间。
     */
    @InitBinder
    void localDateTimeBinding(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDateTime.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text == null || text.isBlank()) {
                    setValue(null);
                    return;
                }
                LocalDateTime value = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (value.getNano() % 1000 != 0) {
                    throw new IllegalArgumentException("时间精度最多六位小数");
                }
                setValue(value);
            }
        });
    }

    @GetMapping("/statistics")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<Statistics> statistics(Query query) {
        return R.ok(service.statistics(query));
    }

    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<Details> details(Query query) {
        return R.ok(service.details(query));
    }
}
