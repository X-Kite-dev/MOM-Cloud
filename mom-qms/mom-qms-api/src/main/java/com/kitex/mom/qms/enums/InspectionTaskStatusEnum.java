package com.kitex.mom.qms.enums;

import com.kitex.mom.qms.dto.resp.QmsInspectionStatusVO;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 质检任务状态
 *
 * @author kitex
 * @date 2022/12/2
 */
@Getter
@AllArgsConstructor
public enum InspectionTaskStatusEnum {

    /**
     * 待检测
     */
    WAIT("待检测"),
    /**
     * 执行中
     */
    RUNNING("执行中"),
    /**
     * 未处理
     */
    UNHANDLED("未处理"),
    /**
     * 已完成
     */
    FINISHED("已完成");

    private final String name;


    public static InspectionTaskStatusEnum findByName(String name) {
        return Arrays.stream(InspectionTaskStatusEnum.values())
                .filter(e -> Objects.equals(name.toUpperCase(), e.name())).findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的加严等级"));
    }

    public static List<QmsInspectionStatusVO> convertToStatusList() {
        List<QmsInspectionStatusVO> statusList = new ArrayList<>();
        for (InspectionTaskStatusEnum status : InspectionTaskStatusEnum.values()) {
            // 使用枚举的名字作为 code，使用 name 属性作为 name，num 设置为 0
            statusList.add(new QmsInspectionStatusVO(status.name(), status.getName(), 0));
        }
        return statusList;
    }

}
