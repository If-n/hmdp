package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IUVService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/uv")
public class UVController {

    @Resource
    private IUVService UVService;

    @GetMapping("/shop/{shopId}")
    public Result queryUV(@PathVariable("shopId") Long shopId){
        return UVService.queryUV(shopId);
    }
}
