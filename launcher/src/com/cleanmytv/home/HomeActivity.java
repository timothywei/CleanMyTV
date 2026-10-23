package com.cleanmytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CleanMyTV 首页：时间 + 「所有应用」入口 + 可编辑的应用网格。
 *
 * 首页显示策略：黑名单模式（home_removed）。默认全部显示，只记录用户主动移除的。
 * 这样 U 盘 / 商店新装的应用会自动出现在首页。
 */
public class HomeActivity extends Activity {

    static final int BG       = 0xFF11131A;
    static final int TEXT     = 0xFFEDEDED;
    static final int TEXT_DIM = 0xFF9AA0AA;
    static final int CARD     = 0x33FFFFFF;   // 半透明白（约 20%）
    static final int DIVIDER  = 0x1FFFFFFF;   // 很淡的分隔线（约 12% 白）
    static final int ACCENT   = 0xFF2F6BFF;

    static final String PREFS = "cleanyourtv_home";
    static final String KEY_REMOVED = "home_removed";
    /** 自定义顺序，key 用 | 分隔；空 = 按名称排序 */
    static final String KEY_ORDER = "home_order";

    private GridView grid;
    private HomeAdapter adapter;
    private final List<AppEntry> apps = new ArrayList<>();
    private TextView clockView;
    private TextView dateView;
    private TextView allAppsBtn;
    private LinearLayout root;
    private FrameLayout container;
    /** 无悬浮窗权限时，在桌面 Activity 内挂的音量 HUD（与 Service 的悬浮窗互斥，不会同时存在） */
    private VolumeHud localHud;
    private ContentObserver volumeObserver;
    /** 上次焦点位置：仅当 Activity 存活时保留（从应用返回可恢复） */
    private int lastSelected = 0;
    /** 冷启动时跳到最常用应用；之后一律走 lastSelected */
    private boolean firstLaunch = true;
    /** 移动模式（长按确定键进入） */
    private boolean moving = false;
    private int movingPos = -1;
    /** 进入移动模式后的一小段时间内忽略确定键，避免长按产生的 repeat 事件把模式立刻关掉 */
    private long ignoreCenterUntil = 0;
    private static final String TAG = "CleanMyTV";

    /** 焦点恢复参数（供固定 Runnable 使用，避免每次 onResume 产生新对象累积） */
    private boolean focusCold = true;
    private int focusTarget = 0;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            try { refreshApps(); } catch (Throwable ignored) { }
        }
    };

    private final Runnable focusGridTask = new Runnable() {
        @Override public void run() {
            try {
                if (adapter.getCount() == 0) return;
                int pos = Math.max(0, Math.min(focusTarget, adapter.getCount() - 1));
                grid.setSelection(pos);
                lastSelected = pos;
                grid.smoothScrollToPosition(pos);
                // 冷启动强制夺回焦点：系统启动时会把焦点给第一个 focusable 控件（顶部按钮）
                if (focusCold || getCurrentFocus() == null) grid.requestFocus();
            } catch (Throwable ignored) { }
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 20000);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { refreshApps(); }
            }, 600);
        }
    };

    // ---------------- 应用数据 ----------------

    static class AppEntry {
        String pkg;
        String cls;
        String label;
        Drawable icon;
        Intent intent;
        boolean isSystem;
        String key() { return pkg + "/" + cls; }
    }

    /**
     * 遍历所有已安装应用（系统接口 PackageManager），取可启动入口：
     * 先 getLaunchIntentForPackage，取不到再退回该包第一个 MAIN Activity。
     */
    static List<AppEntry> queryLaunchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        HashSet<String> selfPkgs = new HashSet<>();
        selfPkgs.add(ctx.getPackageName());
        selfPkgs.add("com.cleanmytv.home");
        selfPkgs.add("com.ts.launcher");

        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(0);
        } catch (Exception e) {
            installed = new ArrayList<>();
        }

        for (ApplicationInfo ai : installed) {
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (pkg == null || selfPkgs.contains(pkg)) continue;

            Intent li = null;
            ComponentName cn = null;
            try {
                li = pm.getLaunchIntentForPackage(pkg);
                if (li != null) cn = li.getComponent();
            } catch (Exception e) {
                cn = null;
            }
            if (cn == null) {
                try {
                    Intent probe = new Intent(Intent.ACTION_MAIN);
                    probe.setPackage(pkg);
                    List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
                    if (ris != null && !ris.isEmpty() && ris.get(0).activityInfo != null) {
                        cn = new ComponentName(pkg, ris.get(0).activityInfo.name);
                        li = new Intent(Intent.ACTION_MAIN);
                        li.setComponent(cn);
                    }
                } catch (Exception ex) {
                    cn = null;
                }
            }
            if (cn == null || li == null) continue;
            String key = cn.getPackageName() + "/" + cn.getClassName();
            if (!seen.add(key)) continue;

            AppEntry e = new AppEntry();
            e.pkg = cn.getPackageName();
            e.cls = cn.getClassName();
            try {
                e.label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Exception ex) {
                e.label = e.pkg;
            }
            try {
                e.icon = ai.loadIcon(pm);
            } catch (Exception ex) {
                e.icon = null;
            }
            int fl = ai.flags;
            e.isSystem = (fl & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            e.intent = li;
            e.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(e);
        }

        // LEANBACK_LAUNCHER 补充
        try {
            Intent leanback = new Intent(Intent.ACTION_MAIN, null);
            leanback.addCategory("android.intent.category.LEANBACK_LAUNCHER");
            for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || selfPkgs.contains(pkg)) continue;
                String key = pkg + "/" + ri.activityInfo.name;
                if (!seen.add(key)) continue;

                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.cls = ri.activityInfo.name;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.icon = ri.loadIcon(pm);
                e.intent = new Intent(Intent.ACTION_MAIN);
                e.intent.addCategory(Intent.CATEGORY_LAUNCHER);
                e.intent.setComponent(new ComponentName(pkg, ri.activityInfo.name));
                e.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    int fl = ai.flags;
                    e.isSystem = (fl & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } catch (Exception ignored) { }
                out.add(e);
            }
        } catch (Exception ignored) { }

        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 工程/系统底层组件：默认在「所有应用」里隐藏，避免误点。
     * 判定依据是这台机器的实测包名（MediaTek 工程菜单、TIF 测试、VPN 对话框、系统 UI 等）。
     */
    static boolean isDangerous(AppEntry e) {
        if (e == null || e.pkg == null) return false;
        String p = e.pkg.toLowerCase();
        return p.equals("mediatek.factorymenu.ui")
                || p.equals("com.mediatek.tvinput")
                || p.equals("com.android.vpndialogs")
                || p.equals("com.android.systemui")
                || p.equals("com.mediatek.tv.service")
                || p.equals("com.mediatek.network")
                || p.contains("factorymenu")
                || p.contains("xiaomi.mitv.systemui");
    }

    /** 黑名单模式：不在 removed 集合里 = 显示在首页（新装应用自动显示）。 */
    static boolean isOnHome(Context ctx, AppEntry e) {
        return !prefs(ctx).getStringSet(KEY_REMOVED, new HashSet<String>()).contains(e.key());
    }

    static void setOnHome(Context ctx, AppEntry e, boolean on) {
        Set<String> cur = new HashSet<>(prefs(ctx).getStringSet(KEY_REMOVED, new HashSet<String>()));
        if (on) cur.remove(e.key()); else cur.add(e.key());
        prefs(ctx).edit().putStringSet(KEY_REMOVED, cur).apply();
    }

    /** 清空首页：把当前所有可启动应用都加入 removed。 */
    static void clearHome(Context ctx) {
        Set<String> all = new HashSet<>();
        for (AppEntry a : queryLaunchable(ctx)) all.add(a.key());
        prefs(ctx).edit().putStringSet(KEY_REMOVED, all).apply();
    }

    /** 恢复首页为"全部显示"。 */
    static void resetHome(Context ctx) {
        prefs(ctx).edit().remove(KEY_REMOVED).apply();
    }

    // ---------------- UI ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        root.setPadding(pad, dp(24), pad, dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        clockView = new TextView(this);
        clockView.setTextColor(TEXT);
        clockView.setTextSize(32);
        header.addView(clockView, lp());

        dateView = new TextView(this);
        dateView.setTextColor(TEXT_DIM);
        dateView.setTextSize(15);
        LinearLayout.LayoutParams dlp = lp();
        dlp.leftMargin = dp(18);
        header.addView(dateView, dlp);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        allAppsBtn = pill("所有应用");
        header.addView(allAppsBtn, pillLp());

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 分割线已移除：头部与图标区靠留白分隔

        grid = new GridView(this);
        grid.setId(View.generateViewId());
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(18));
        grid.setVerticalSpacing(dp(18));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(14), 0, dp(14));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(true);
        grid.setSoundEffectsEnabled(false);
        grid.setVerticalScrollBarEnabled(false);

        adapter = new HomeAdapter();
        grid.setAdapter(adapter);
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && grid.getSelectedItemPosition() < 0) grid.setSelection(0);
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0) lastSelected = pos;
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (moving) return;   // 移动模式下不启动应用
                if (pos >= 0 && pos < apps.size()) {
                    AppEntry e = apps.get(pos);
                    recordLaunch(e);
                    try { startActivity(e.intent); } catch (Exception ignored) { }
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Log.d(TAG, "onItemLongClick pos=" + pos);
                if (pos >= 0 && pos < apps.size()) {
                    enterMoveMode(pos);
                    return true;
                }
                return false;
            }
        });

        // 网格整体贴底：外层 FrameLayout 吃掉 header 以下的剩余空间，
        // 网格在底部按内容高度排列，屏幕中部空出来留给壁纸
        FrameLayout gridHost = new FrameLayout(this);
        root.addView(gridHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        gridHost.addView(grid, glp);

        // 外层容器：承载壁纸 + 音量浮层
        container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(container);

        // 音量监听交给前台服务：桌面退到后台（打开其他应用）时也能弹 HUD
        ensureVolumeService("onCreate");

        // 没有悬浮窗权限时，Service 那边的 HUD 会处于 disabled，
        // 这里补一个只在桌面内显示的 HUD，避免「按了音量但什么都看不到」
        ensureLocalHud();

        try {
            applyWallpaper();
            BingWallpaper.updateIfNeeded(this, new Runnable() {
                @Override public void run() {
                    try { applyWallpaper(); } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) {
            // 壁纸模块整体兜底：即使它出任何问题，桌面照常可用
        }

        grid.setNextFocusUpId(allAppsBtn.getId());
        allAppsBtn.setNextFocusDownId(grid.getId());

        // 手动接管上下导航，避免 GridView 的 focusSearch 掉到根容器
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (moving) {
                    Log.d(TAG, "moving key code=" + keyCode + " action=" + event.getAction());

                    // apps 列表可能被 refreshApps 重建过，位置失效就安全退出，避免越界崩溃
                    if (movingPos < 0 || movingPos >= apps.size()) {
                        Log.d(TAG, "moving pos invalid -> exit");
                        exitMoveMode();
                        return true;
                    }

                    // 确定键：只认「抬起」，且跳过进入瞬间长按产生的 repeat 事件
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (event.getAction() == KeyEvent.ACTION_UP
                                && SystemClock.uptimeMillis() > ignoreCenterUntil) {
                            exitMoveMode();
                        }
                        return true;   // DOWN / repeat / UP 一律吞掉，避免 GridView 响应
                    }

                    if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

                    int cols = Math.max(1, grid.getNumColumns());
                    int delta = 0;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) delta = -1;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) delta = 1;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) delta = -cols;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) delta = cols;
                    else return false;

                    int target = movingPos + delta;
                    if (target >= 0 && target < apps.size()) {
                        AppEntry tmp = apps.get(movingPos);
                        apps.set(movingPos, apps.get(target));
                        apps.set(target, tmp);
                        movingPos = target;
                        lastSelected = target;
                        adapter.notifyDataSetChanged();
                        grid.setSelection(target);
                    }
                    return true;
                }

                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int pos = grid.getSelectedItemPosition();
                    if (pos >= 0 && pos < grid.getNumColumns()) {
                        allAppsBtn.requestFocus();
                        return true;
                    }
                }
                return false;
            }
        });
        allAppsBtn.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    grid.requestFocus();
                    if (grid.getSelectedItemPosition() < 0) grid.setSelection(0);
                    return true;
                }
                return false;
            }
        });

        allAppsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, AllAppsActivity.class));
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left);
            }
        });

        updateClock();
        refreshApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：开机那次可能失败，用户一操作桌面就重试（startForegroundService 幂等）
        ensureVolumeService("onResume");
        handler.postDelayed(ticker, 1000);

        refreshApps();
        handler.postDelayed(refreshTask, 800);
        handler.postDelayed(refreshTask, 2000);
        handler.postDelayed(refreshTask, 4500);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        try { registerReceiver(pkgReceiver, f); } catch (Exception ignored) { }

        final boolean cold = firstLaunch;
        firstLaunch = false;
        final int target = cold ? mostUsedPos() : lastSelected;

        focusCold = cold;
        focusTarget = target;
        grid.postDelayed(focusGridTask, 300);
        grid.postDelayed(focusGridTask, 1200);
    }

    /** 确保音量前台服务在运行。onCreate / onResume 都会调用（幂等，不会重启服务）。 */
    private void ensureVolumeService(String from) {
        try {
            Intent svc = new Intent(this, VolumeService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            DbgLog.d(this, "HomeActivity." + from + ": startForegroundService ok");
        } catch (Throwable t) {
            DbgLog.d(this, "HomeActivity." + from + ": startForegroundService FAILED " + t);
        }
    }

    /**
     * 没有 SYSTEM_ALERT_WINDOW 权限时，在桌面 Activity 内挂一个音量 HUD。
     *
     * 必须与 Service 里的悬浮窗二选一：悬浮窗会覆盖整个屏幕，如果两边都创建，
     * 桌面上会同时出现两个 HUD 叠在一起。
     */
    private void ensureLocalHud() {
        try {
            boolean hasOverlay;
            try {
                hasOverlay = android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
            } catch (Throwable t) {
                // API 23 以下没有 canDrawOverlays，按「无权限」处理，走桌面内显示
                hasOverlay = false;
            }
            if (hasOverlay) {
                DbgLog.d(this, "HomeActivity: overlay permission present -> HUD owned by service");
                return;
            }
            if (localHud != null) return;

            localHud = new VolumeHud(this, container);
            DbgLog.d(this, "HomeActivity: no overlay permission -> local HUD inside activity");

            volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    try { if (localHud != null) localHud.show(); } catch (Throwable ignored) { }
                }
            };
            ContentResolver cr = getContentResolver();
            for (String k : new String[]{
                    "volume_music_hdmi_arc", "volume_music",
                    "volume_music_speaker", "volume_music_hdmi"}) {
                cr.registerContentObserver(Settings.System.getUriFor(k), false, volumeObserver);
            }
            DbgLog.d(this, "HomeActivity: local volume observer registered");
        } catch (Throwable t) {
            DbgLog.d(this, "HomeActivity: local HUD init FAILED " + t);
        }
    }

    /** 释放桌面内 HUD：注销音量监听并把 View 从容器上摘掉，避免泄漏。 */
    private void releaseLocalHud() {
        try {
            if (volumeObserver != null) {
                getContentResolver().unregisterContentObserver(volumeObserver);
                volumeObserver = null;
            }
        } catch (Throwable ignored) { }
        try {
            if (localHud != null) {
                localHud.destroy();
                localHud = null;
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(refreshTask);          // 清掉 3 个延迟刷新，避免后台空转
        try { grid.removeCallbacks(focusGridTask); } catch (Throwable ignored) { }
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        // 音量监听不在这里注销：悬浮窗模式下，其他应用界面也要显示 HUD
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 桌面内 HUD 属于本 Activity，必须随它一起释放
        releaseLocalHud();
        // VolumeService 是 START_STICKY 常驻服务，这里不停止它
    }

    /** 移动模式下直接拦返回键，不依赖 onBackPressed（Android 14 的返回分发可能绕过它） */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moving && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "BACK -> exitMove");
                exitMoveMode();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 备选入口：菜单键也能进入移动模式（部分遥控器长按确定键手感不稳）
        if (!moving && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            Log.d(TAG, "menu key=" + keyCode);
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < apps.size()) {
                enterMoveMode(pos);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (moving) { exitMoveMode(); return; }
        // 桌面吞掉返回键
    }

    private void refreshApps() {
        // 移动模式期间不重建列表：既避免 movingPos 失效，也不会打断用户正在做的排序
        if (moving) {
            Log.d(TAG, "refreshApps skipped while moving");
            return;
        }

        List<AppEntry> all = queryLaunchable(this);
        List<AppEntry> shown = new ArrayList<>();
        for (AppEntry e : all) {
            if (isOnHome(this, e)) shown.add(e);
        }
        applyOrder(shown);
        apps.clear();
        apps.addAll(shown);
        adapter.notifyDataSetChanged();
    }

    /** 自定义顺序列表；空表示尚未排序（走名称排序）。 */
    private List<String> getOrder() {
        try {
            String s = prefs(this).getString(KEY_ORDER, "");
            List<String> out = new ArrayList<>();
            if (s == null || s.isEmpty()) return out;
            for (String p : s.split("\\|")) if (!p.isEmpty()) out.add(p);
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private void saveOrder() {
        try {
            StringBuilder sb = new StringBuilder();
            for (AppEntry e : apps) {
                if (sb.length() > 0) sb.append('|');
                sb.append(e.key());
            }
            prefs(this).edit().putString(KEY_ORDER, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 按自定义顺序排序；不在顺序表里的应用按名称排到末尾。 */
    private void applyOrder(List<AppEntry> list) {
        final List<String> order = getOrder();
        if (order.isEmpty()) {
            Collections.sort(list, new Comparator<AppEntry>() {
                @Override public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            return;
        }
        final Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < order.size(); i++) idx.put(order.get(i), i);
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                Integer ia = idx.get(a.key());
                Integer ib = idx.get(b.key());
                if (ia != null && ib != null) return ia - ib;
                if (ia != null) return -1;
                if (ib != null) return 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private void enterMoveMode(int pos) {
        moving = true;
        movingPos = pos;
        ignoreCenterUntil = SystemClock.uptimeMillis() + 900;   // 吞掉长按残留的 repeat 事件
        adapter.notifyDataSetChanged();
        grid.setSelection(pos);
        grid.requestFocus();                                    // 确保焦点在网格上，白框才看得见
        Toast.makeText(this, "移动模式：方向键调整位置，确定键完成", Toast.LENGTH_LONG).show();
        Log.d(TAG, "enterMove pos=" + pos);
    }

    private void exitMoveMode() {
        Log.d(TAG, "exitMove");
        moving = false;
        movingPos = -1;
        saveOrder();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "顺序已保存", Toast.LENGTH_SHORT).show();
    }

    Drawable moveBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x662F6BFF);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(2), 0xCCFFFFFF);
        return d;
    }

    private TextView arrowView(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setShadowLayer(2f, 0f, 1f, 0xAA000000);
        t.setVisibility(View.GONE);
        return t;
    }

    /** 记录一次应用启动，供冷启动时统计最常用。 */
    private void recordLaunch(AppEntry e) {
        try {
            SharedPreferences p = prefs(this);
            String k = "launch_" + e.key();
            p.edit().putInt(k, p.getInt(k, 0) + 1).apply();
        } catch (Throwable ignored) { }
    }

    /** 点击次数最多的应用下标；无记录时返回 0。 */
    private int mostUsedPos() {
        try {
            SharedPreferences p = prefs(this);
            int best = 0, bestCount = -1;
            for (int i = 0; i < apps.size(); i++) {
                int c = p.getInt("launch_" + apps.get(i).key(), 0);
                if (c > bestCount) { bestCount = c; best = i; }
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
    }

    /** 加载壁纸背景（委托给 Wallpaper，内部已做全部异常兜底）。 */
    private void applyWallpaper() {
        Wallpaper.apply(this, container);
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(10);
        return p;
    }

    private TextView pill(String text) {
        TextView tv = new TextView(this);
        tv.setId(View.generateViewId());
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(20), dp(9), dp(20), dp(9));
        tv.setFocusable(true);
        tv.setClickable(true);

        StateListDrawable sd = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(ACCENT);
        pressed.setCornerRadius(dp(18));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x33FFFFFF);   // 半透明白，与卡片一致
        normal.setCornerRadius(dp(18));
        GradientDrawable foc = new GradientDrawable();
        foc.setColor(ACCENT);          // 聚焦：与列表选中色一致
        foc.setCornerRadius(dp(18));
        sd.addState(new int[]{android.R.attr.state_focused}, foc);
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{}, normal);
        tv.setBackground(sd);
        return tv;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    Drawable cardBg() {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(ACCENT);
        sel.setCornerRadius(dp(14));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD);
        normal.setCornerRadius(dp(14));
        sd.addState(new int[]{android.R.attr.state_selected}, sel);
        sd.addState(new int[]{android.R.attr.state_focused}, sel);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    private class HomeAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout card;
            LinearLayout content;
            ImageView iv;
            TextView tv;
            TextView arrowL, arrowR;

            if (convertView == null) {
                card = new FrameLayout(HomeActivity.this);

                content = new LinearLayout(HomeActivity.this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                content.setMinimumHeight(dp(104));
                content.setPadding(dp(6), dp(12), dp(6), dp(10));
                content.setLongClickable(true);

                iv = new ImageView(HomeActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                content.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));

                tv = new TextView(HomeActivity.this);
                tv.setTextColor(TEXT);
                tv.setTextSize(12);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(6);
                content.addView(tv, tlp);

                card.addView(content, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // 移动模式提示：左右箭头
                arrowL = arrowView("\u25C0");
                FrameLayout.LayoutParams alpL = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alpL.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                alpL.leftMargin = dp(4);
                card.addView(arrowL, alpL);

                arrowR = arrowView("\u25B6");
                FrameLayout.LayoutParams alpR = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alpR.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                alpR.rightMargin = dp(4);
                card.addView(arrowR, alpR);

                card.setTag(new Object[]{content, iv, tv, arrowL, arrowR});
            } else {
                card = (FrameLayout) convertView;
                Object[] tags = (Object[]) card.getTag();
                content = (LinearLayout) tags[0];
                iv = (ImageView) tags[1];
                tv = (TextView) tags[2];
                arrowL = (TextView) tags[3];
                arrowR = (TextView) tags[4];
            }

            AppEntry e = apps.get(position);
            Drawable ic = IconFactory.get(HomeActivity.this, e.key(), e.icon, dp(56));
            if (ic != null) iv.setImageDrawable(ic);
            tv.setText(e.label);

            boolean isMoving = moving && position == movingPos;
            content.setBackground(isMoving ? moveBg() : cardBg());
            arrowL.setVisibility(isMoving ? View.VISIBLE : View.GONE);
            arrowR.setVisibility(isMoving ? View.VISIBLE : View.GONE);
            card.setContentDescription(e.label);
            return card;
        }
    }
}
