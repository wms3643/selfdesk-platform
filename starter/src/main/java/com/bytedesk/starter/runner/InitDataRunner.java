/*
 * @Author: jackning 270580156@qq.com
 * @Date: 2024-01-29 16:17:36
 * @LastEditors: jackning 270580156@qq.com
 * @LastEditTime: 2025-04-12 22:36:33
 * @Description: bytedesk.com https://github.com/Bytedesk/bytedesk
 *   Please be aware of the BSL license restrictions before installing Bytedesk IM – 
 *  selling, reselling, or hosting Bytedesk IM as a service is a breach of the terms and automatically terminates your rights under the license.
 *  Business Source License 1.1: https://github.com/Bytedesk/bytedesk/blob/main/LICENSE 
 *  contact: 270580156@qq.com 
 *  联系：270580156@qq.com
 * Copyright (c) 2024 by bytedesk.com, All Rights Reserved. 
 */
package com.bytedesk.starter.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import com.bytedesk.core.config.properties.BytedeskProperties;
import com.bytedesk.core.constant.BytedeskConsts;
import com.bytedesk.core.utils.LicenseValidator;
import com.bytedesk.core.utils.LicenseValidator.LicenseInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * @author bytedesk.com on 2019/4/21
 */
@Slf4j
@Component
public class InitDataRunner {

    @Value("${application.version}")
    private String version;
    
    @Value("${server.port}")
    private String port;

    private final BytedeskProperties bytedeskProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public InitDataRunner(BytedeskProperties bytedeskProperties,
                          StringRedisTemplate stringRedisTemplate) {
        this.bytedeskProperties = bytedeskProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 异步验证许可证有效性（本地 RSA 验签，不依赖远程服务）。
     * 验证结果写入 Redis 缓存，供 BytedeskPropertiesController 读取。
     */
    public void validateLicenseOnStartup() {
        String licenseKey = bytedeskProperties.getOriginalAppkey();
        String cacheKey = BytedeskConsts.LICENSE_VALID_CACHE_PREFIX + licenseKey;
        try {
            LicenseInfo info = LicenseValidator.validateOnStartup(licenseKey);
            boolean valid = info != null && info.isValid();
            cacheLicenseStatus(cacheKey, String.valueOf(valid));
            log.info("License startup validation complete: valid={}", valid);
        } catch (Exception e) {
            log.warn("License startup validation unavailable: {}", e.getClass().getSimpleName());
            cacheLicenseStatus(cacheKey, "false");
        }
    }

    private void cacheLicenseStatus(String cacheKey, String value) {
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, value);
        } catch (Exception e) {
            log.warn("License status cache unavailable: {}", e.getClass().getSimpleName());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("InitDataRunner executing...");
        log.info("bytedesk v{} started, http://127.0.0.1:{}", version, port);
        log.info("ApplicationReadyEvent received. Application is fully started and ready to serve on port {}", port);

        // 同类内部调用不会经过 @Async 代理；显式放到后台，许可证或 Redis 故障不得阻断启动。
        java.util.concurrent.CompletableFuture.runAsync(this::validateLicenseOnStartup);
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        log.warn("ContextClosedEvent received. Application context is shutting down.");
    }

}
