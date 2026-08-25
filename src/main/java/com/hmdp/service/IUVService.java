package com.hmdp.service;

import com.hmdp.dto.Result;
import org.springframework.stereotype.Component;

public interface IUVService {

    Result queryUV(Long shopId);

    void recordUV(Long id);
}
