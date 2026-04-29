package com.kitex.mom.mdm.task;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mdm.dto.MdmCalendarDTO;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.enums.CalendarEnum;
import com.kitex.mom.mdm.service.MdmCalendarService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarTask {

    private final MdmCalendarService mdmCalendarService;

    @XxlJob("calendar")
    public void calendar() {
        // 获取当前年份
        LocalDate currentDate = LocalDate.now();
        int currentYear = currentDate.getYear();
        log.info(currentYear + "开始发货异常预警定时任务 start==>");
        // 获取当前年份的1月初
        LocalDate startOfYear = LocalDate.of(currentYear, 1, 1);
        // 获取当前年份的12月末
        LocalDate endOfYear = LocalDate.of(currentYear, 12, 31);
        Map<LocalDate, LocalDate> collect = mdmCalendarService.list(new LambdaQueryWrapper<MdmCalendarEntity>().between(MdmCalendarEntity::getTime, startOfYear, endOfYear))
                .stream()
                .collect(Collectors.toMap(MdmCalendarEntity::getTime, MdmCalendarEntity::getTime,
                        (a, b) -> a));

        String getAccessTokenUrl = String.format("https://unpkg.com/holiday-calendar@1.1.3/data/CN/%s.json", currentYear);
        HttpResponse response = HttpRequest.get(getAccessTokenUrl).execute();
        List<MdmCalendarDTO> dates = JSONUtil.parseObj(response.body()).getBeanList("dates", MdmCalendarDTO.class);
        List<MdmCalendarEntity> saveList = new ArrayList<>();
        for (MdmCalendarDTO mdmCalendarDTO : dates) {
            LocalDate localDate = collect.get(mdmCalendarDTO.getDate());
            if (localDate == null) {
                MdmCalendarEntity mdmCalendarEntity = new MdmCalendarEntity();
                mdmCalendarEntity.setTime(mdmCalendarDTO.getDate());
                mdmCalendarEntity.setStatus(CalendarEnum.getLabelByCode(mdmCalendarDTO.getType()));
                saveList.add(mdmCalendarEntity);
            }
        }
        mdmCalendarService.saveBatch(saveList);
        log.info("结束发货异常预警定时任务 ==>end");
    }
}
