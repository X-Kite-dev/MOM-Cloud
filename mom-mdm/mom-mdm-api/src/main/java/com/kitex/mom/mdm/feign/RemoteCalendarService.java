package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * 工作日历远程服务
 */
@FeignClient(contextId = "remoteCalendarService", value = Constants.Name)
public interface RemoteCalendarService {

    /**
     * 查询指定日期范围内的工作日历列表（含上班/休息标记）
     *
     * @param start 开始日期（含），格式 yyyy-MM-dd
     * @param end   结束日期（含），格式 yyyy-MM-dd
     * @return 日历条目列表，status: 0=上班 1=休息
     */
    @GetMapping("/mdmCalendar/listByRange")
    R<List<MdmCalendarEntity>> listByRange(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end);
}
