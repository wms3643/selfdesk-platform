/*
 * @Author: jackning 270580156@qq.com
 * @Date: 2025-10-24
 * @LastEditors: jackning 270580156@qq.com
 * @LastEditTime: 2025-10-24
 * @Description: bytedesk.com https://github.com/Bytedesk/bytedesk
 *   Please be aware of the BSL license restrictions before installing Bytedesk IM – 
 *  selling, reselling, or hosting Bytedesk IM as a service is a breach of the terms and automatically terminates your rights under the license.
 *  Business Source License 1.1: https://github.com/Bytedesk/bytedesk/blob/main/LICENSE 
 *  contact: 270580156@qq.com 
 *  联系：270580156@qq.com
 * Copyright (c) 2024 by bytedesk.com, All Rights Reserved. 
 */
package com.bytedesk.service.workgroup_settings;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bytedesk.service.workgroup_settings.event.WorkgroupSettingsCreateEvent;
import com.bytedesk.service.workgroup_settings.event.WorkgroupSettingsUpdateEvent;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Event listener for WorkgroupSettings application events
 */
@Slf4j
@Component
@AllArgsConstructor
public class WorkgroupSettingsEventListener {

    @EventListener
    public void onWorkgroupSettingsCreateEvent(WorkgroupSettingsCreateEvent event) {
        WorkgroupSettingsEntity workgroupSettings = event.getWorkgroupSettings();
        log.info("WorkgroupSettings created: {} - {}", workgroupSettings.getUid(), workgroupSettings.getName());
        
        // Add additional logic here if needed
        // e.g., send notifications, update caches, etc.
    }

    @EventListener
    public void onWorkgroupSettingsUpdateEvent(WorkgroupSettingsUpdateEvent event) {
        WorkgroupSettingsEntity workgroupSettings = event.getWorkgroupSettings();
        log.info("WorkgroupSettings updated: {} - {}", workgroupSettings.getUid(), workgroupSettings.getName());
        
        // Add additional logic here if needed
        // e.g., invalidate caches, sync data, etc.
    }
}
