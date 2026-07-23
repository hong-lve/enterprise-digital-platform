package com.company.dataops.console.security;

import com.company.dataops.console.entity.TwoFactorSettingEntity;
import com.company.dataops.console.mapper.TwoFactorSettingMapper;
import org.springframework.stereotype.Component;

/**
 * Single-row sys_two_factor_setting - the global "does the whole system
 * require 2FA right now" switch, editable at runtime from 系统管理/安全设置
 * (SystemSecurityController) with no redeploy. Read on every login, not
 * cached - it's one row by primary key, and correctness (a toggle taking
 * effect immediately) matters far more than shaving a single-row lookup off
 * the login path.
 */
@Component
public class TwoFactorSettingService {
    private static final int SETTING_ID = 1;

    private final TwoFactorSettingMapper mapper;

    public TwoFactorSettingService(TwoFactorSettingMapper mapper) {
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        TwoFactorSettingEntity setting = mapper.selectById(SETTING_ID);
        return setting != null && Boolean.TRUE.equals(setting.getEnabled());
    }

    public void setEnabled(boolean enabled) {
        TwoFactorSettingEntity setting = new TwoFactorSettingEntity();
        setting.setId(SETTING_ID);
        setting.setEnabled(enabled);
        mapper.updateById(setting);
    }
}
