package com.cdtubear.xpwechatcall;

/**
 * 设置页的总览入口、详情分组、标题与页面的唯一来源。
 * 页面不再用横向分类 tab，而是“总览 + 分组详情”。
 */
final class SettingsSections {

    static final int OVERVIEW = 0;
    static final int CONNECTION = 1;
    static final int CALL = 2;
    static final int DEVICE = 3;
    static final int ABOUT = 4;

    static final int[] ROWS = {
        R.id.row_connection,
        R.id.row_voip,
        R.id.row_device,
        R.id.row_about
    };

    static final int[][] PAGES = {
        {R.id.page_overview},
        {R.id.page_connection, R.id.page_trigger},
        {R.id.page_voip},
        {R.id.page_service, R.id.page_diagnostic},
        {R.id.page_about}
    };

    static final int[] TITLES = {
        R.string.tab_settings,
        R.string.settings_title_connection,
        R.string.settings_title_call,
        R.string.settings_title_device,
        R.string.settings_title_about
    };

    static final int[] SUBTITLES = {
        R.string.settings_subtitle_overview,
        R.string.settings_subtitle_connection,
        R.string.settings_subtitle_call,
        R.string.settings_subtitle_device,
        R.string.settings_subtitle_about
    };

    private SettingsSections() {
    }

    static boolean isConfigPage(int page) {
        return page == CONNECTION || page == CALL;
    }

    static int pageForRowIndex(int index) {
        return index + 1;
    }
}
