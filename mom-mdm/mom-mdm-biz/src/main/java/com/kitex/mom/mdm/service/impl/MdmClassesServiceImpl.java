package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.mapper.MdmClassesMapper;
import com.kitex.mom.mdm.service.MdmClassesService;
import com.kitex.mom.mdm.utils.ShiftCalculator;
import com.kitex.mom.mdm.vo.excel.MdmClassesExcelVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 班次
 *
 * @author kitex
 * @date 2024-11-01 17:00:25
 */
@Service
public class MdmClassesServiceImpl extends ServiceImpl<MdmClassesMapper, MdmClassesEntity>
        implements MdmClassesService {

    /**
     * 导入Excel
     *
     * @param mdmCalendarList
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmClassesExcelVO> mdmCalendarList, BindingResult bindingResult) {

        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        Map<String, MdmClassesEntity> mdmCalendarMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmClassesEntity::getShiftCode, Function.identity(), (a, b) -> a));
        List<MdmClassesEntity> saveList = new ArrayList<>();
        for (MdmClassesExcelVO excelVO : mdmCalendarList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验编码是否重复
            if (mdmCalendarMap.containsKey(excelVO.getShiftCode().trim())) {
                errorMsg.add("班次编码已存在");
            }
            // 状态为空默认开启
            if (ObjUtil.isEmpty(excelVO.getStatus())) {
                excelVO.setStatus(1);
            }

            validateTimeRanges((List<MdmClassesEntity>) mdmCalendarMap.values(), excelVO.getStartTime(), excelVO.getEndTime());

            if (CollUtil.isEmpty(errorMsg)) {
                // 数据合法情况
                MdmClassesEntity mdmCalendar = insertExcel(excelVO);
                saveList.add(mdmCalendar);
                mdmCalendarMap.put(excelVO.getShiftCode(), mdmCalendar);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }

        }
        // 保存信息
        this.saveBatch(saveList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "导入成功");
    }

    /**
     * 获取当前班次
     *
     * @return
     */
    @Override
    public MdmClassesEntity getBanci() {
        // TODO 区间判定 设置一个时间区间如半小时 那临近下个班次前半小时进行查询获取班次 则查询结果为下个班次
        return baseMapper.getInfoByNow(LocalTime.now());
    }

    /**
     * 获取班次规则
     *
     * @return R 对象列表
     */
    @Override
    public String getClassesRule(LocalDateTime dateTime) {
        List<MdmClassesEntity> list = this.list();
        return ShiftCalculator.getShift(list, dateTime);
    }

    /**
     * 新增班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    @Override
    public Boolean saveClasses(MdmClassesEntity mdmClasses) {
        validateTimeRanges(this.list(new LambdaQueryWrapper<MdmClassesEntity>().eq(MdmClassesEntity::getStatus, true)), mdmClasses.getStartTime(), mdmClasses.getEndTime());
        return this.save(mdmClasses);
    }

    /**
     * 修改班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    @Override
    public Boolean updateClasses(MdmClassesEntity mdmClasses) {
        validateTimeRanges(this.list(new LambdaQueryWrapper<MdmClassesEntity>().ne(MdmClassesEntity::getId, mdmClasses.getId())
                        .eq(MdmClassesEntity::getStatus, true)),
                mdmClasses.getStartTime(), mdmClasses.getEndTime());
        return this.updateById(mdmClasses);
    }

    /**
     * 根据当前时间获取包含的班次
     *
     * @param dateTime 时间
     * @param number   获取当前班次接下来的几个班次
     * @return R 对象列表
     */
    @Override
    public List<MdmClassesEntity> getClassesBydate(LocalDateTime dateTime, Integer number) {
        List<MdmClassesEntity> allShifts = this.list();
        LocalDateTime localTime = dateTime != null ? dateTime : LocalDateTime.now();
        LocalTime currentTime = localTime.toLocalTime();

        // 1. 获取当前班次
        LambdaQueryWrapper<MdmClassesEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MdmClassesEntity::getStatus, true)
                .and(wrapper -> wrapper
                        .nested(w -> w
                                .le(MdmClassesEntity::getStartTime, currentTime)
                                .gt(MdmClassesEntity::getEndTime, currentTime)
                        )
                        .or()
                        .nested(w -> w
                                .apply("start_time > end_time")
                                .and(sw -> sw
                                        .le(MdmClassesEntity::getStartTime, currentTime)
                                        .or()
                                        .gt(MdmClassesEntity::getEndTime, currentTime)
                                )
                        )
                );

        MdmClassesEntity currentShift = this.getOne(queryWrapper);
        if (currentShift == null) {
            return Collections.emptyList();
        }

        // 设置当前班次的班次代码，使用传入的时间
        currentShift.setShiftCode(ShiftCalculator.getShift(allShifts, localTime));

        if (number == 1) {
            return Collections.singletonList(currentShift);
        }

        // 2. 按开始时间排序所有有效班次
        List<MdmClassesEntity> sortedShifts = allShifts.stream()
                .filter(shift -> Boolean.TRUE.equals(shift.getStatus()))
                .sorted(Comparator.comparing(MdmClassesEntity::getStartTime))
                .collect(Collectors.toList());

        // 3. 找到当前班次在排序列表中的位置
        int currentIndex = -1;
        for (int i = 0; i < sortedShifts.size(); i++) {
            if (sortedShifts.get(i).getStartTime().equals(currentShift.getStartTime())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) {
            return Collections.singletonList(currentShift);
        }

        // 4. 构建结果列表
        List<MdmClassesEntity> result = new ArrayList<>();
        result.add(currentShift);

        // 5. 添加后续班次
        int count = 1;
        int index = currentIndex;
        LocalDateTime nextDateTime = localTime;
        // 凌晨仍属于前一天开始的跨日班，后续班次须从该起始日继续推进。
        if (currentShift.getStartTime().isAfter(currentShift.getEndTime())
                && currentTime.isBefore(currentShift.getEndTime())) {
            nextDateTime = nextDateTime.minusDays(1);
        }

        while (count < number) {
            // 获取下一个索引，如果超出列表长度则回到开头
            index = (index + 1) % sortedShifts.size();

            MdmClassesEntity nextShift = sortedShifts.get(index);
            MdmClassesEntity copy = new MdmClassesEntity();
            BeanUtils.copyProperties(nextShift, copy);

            // 计算下一个班次的开始时间对应的完整日期时间
            LocalTime currentEndTime = result.get(result.size() - 1).getEndTime();
            LocalTime nextStartTime = copy.getStartTime();

            // 如果当前班次是跨天的，或者下一个班次的开始时间小于当前班次的结束时间，说明需要加一天
            if (result.get(result.size() - 1).getStartTime().isAfter(result.get(result.size() - 1).getEndTime()) ||
                    nextStartTime.isBefore(currentEndTime)) {
                nextDateTime = nextDateTime.plusDays(1);
            }

            // 精确按下一班开始时刻计算，短班次也不能被偏移跳过。
            LocalDateTime shiftDateTime = LocalDateTime.of(
                    nextDateTime.toLocalDate(),
                    nextShift.getStartTime()
            );
            copy.setShiftCode(ShiftCalculator.getShift(allShifts, shiftDateTime));

            result.add(copy);
            count++;
        }

        return result;
    }

    /**
     * 从带日期的班次编码起，查询包含该班次的连续序列。
     *
     * @param classCode 班次开始日和配置编码
     * @param number    包含起始班次的序列长度
     * @return 对应的有效班次序列
     */
    @Override
    public List<MdmClassesEntity> getClassesByCode(String classCode, Integer number) {
        if (classCode == null || number == null || number < 1) {
            return Collections.emptyList();
        }
        List<MdmClassesEntity> active = list(new LambdaQueryWrapper<MdmClassesEntity>()
                .eq(MdmClassesEntity::getStatus, true));
        // 日期只取前缀，班次编码可以包含数字。先匹配系统生成的六位日期，再兼容八位日期。
        for (String pattern : List.of("uuMMdd", "uuuuMMdd")) {
            int length = pattern.length();
            if (classCode.length() <= length) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(classCode.substring(0, length),
                        DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));
            } catch (DateTimeParseException invalid) {
                continue;
            }
            String shiftCode = classCode.substring(length);
            var matching = active.stream().filter(shift -> shiftCode.equals(shift.getShiftCode())).toList();
            if (matching.isEmpty()) {
                continue;
            }
            if (matching.size() != 1 || matching.get(0).getStartTime() == null) {
                throw new CheckedException("班次配置重复或缺少开始时间");
            }
            // 日期属于班次开始日。跨午夜班次也从实际开始时刻查询后续序列。
            var shifts = getClassesBydate(date.atTime(matching.get(0).getStartTime()), number);
            String canonical = date.format(DateTimeFormatter.ofPattern("yyMMdd")) + shiftCode;
            if (shifts.isEmpty() || !canonical.equals(shifts.get(0).getShiftCode())) {
                return Collections.emptyList();
            }
            return shifts;
        }
        return Collections.emptyList();
    }

    private void validateTimeRanges(List<MdmClassesEntity> list, LocalTime startTime, LocalTime endTime) {
        for (MdmClassesEntity mdmClasses : list) {
            if (hasTimeConflict(startTime, endTime, mdmClasses)) {
                throw new CheckedException(String.format(
                        "新的时间范围 %s-%s 与已存在的时间范围 %s-%s 存在冲突",
                        startTime, endTime,
                        mdmClasses.getStartTime(), mdmClasses.getEndTime()
                ));
            }
        }
    }

    private boolean hasTimeConflict(LocalTime newStart, LocalTime newEnd, MdmClassesEntity existingClass) {
        LocalTime existingStart = existingClass.getStartTime();
        LocalTime existingEnd = existingClass.getEndTime();

        // 如果新时间范围的结束时间等于现有范围的开始时间，或新范围的开始时间等于现有范围的结束时间，则允许
        if (newEnd.equals(existingStart) || newStart.equals(existingEnd)) {
            return false;
        }

        // 检查时间范围是否重叠
        return isTimeInRange(newStart, existingStart, existingEnd) || isTimeInRange(newEnd, existingStart, existingEnd);
    }

    private boolean isTimeInRange(LocalTime timeToCheck,
                                  LocalTime start,
                                  LocalTime end) {
        if (start.equals(end)) {
            return timeToCheck.equals(start);
        }

        // 处理跨天的情况
        if (start.isAfter(end)) {
            return !timeToCheck.isBefore(start) || !timeToCheck.isAfter(end);
        }
        // 普通情况
        return !timeToCheck.isBefore(start) && !timeToCheck.isAfter(end);
    }

    private MdmClassesEntity insertExcel(MdmClassesExcelVO excelVO) {
        MdmClassesEntity mdmCalendar = new MdmClassesEntity();
        BeanUtils.copyProperties(excelVO, mdmCalendar);
        // 转换status字段从Integer到Boolean
        if (excelVO.getStatus() != null) {
            mdmCalendar.setStatus(excelVO.getStatus() == 1);
        }
        return mdmCalendar;
    }

}
