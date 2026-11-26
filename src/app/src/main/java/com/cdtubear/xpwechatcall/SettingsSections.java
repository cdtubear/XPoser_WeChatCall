package com.cdtubear.xpwechatcall;

/**
 * 设置页分区顺序的唯一来源，避免标题、页面和下标三处各写一份。
 */
final class SettingsSections {

    static final int DIAGNOSTIC = 4;

    static final int[] CATEGORIES = {
        R.id.cat_service,
        R.id.cat_connection,
        R.id.cat_voip,
        R.id.cat_trigger,
        R.id.cat_diagnostic,
        R.id.cat_about
    };

    static final int[] PAGES = {
        R.id.page_service,
        R.id.page_connection,
        R.id.page_voip,
        R.id.page_trigger,
        R.id.page_diagnostic,
        R.id.page_about
    };

    private SettingsSections() {
    }
}
