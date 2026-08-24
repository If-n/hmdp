package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;//保存查询结果内容
    private Long minTime;//保存下一次查询起始位置
    private Integer offset;//保存下一次查询偏移量
}
