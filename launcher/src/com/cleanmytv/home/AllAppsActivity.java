package com.cleanmytv.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有应用。
 * - 默认只列第三方应用，「显示系统应用」开关可放开
 * - 「清空首页」一键把首页图标全清掉
 * - 编辑模式：确定键 = 加入 / 移出首页
 */
public class AllAppsActivity extends Activity {

    private GridView grid;
    private Adapter adapter;
    private final List<HomeActivity.AppEntry> apps = new ArrayList<>();
    private boolean editMode = false;
    private boolean showSystem = false;
    private boolean showDangerous = false;

    private TextView editBtn;
    private TextView sysBtn;
    private TextView dangerBtn;
    private TextView hint;
    private TextView hintOps;
    private LinearLayout root;
    private int lastScroll = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HomeActivity.BG);
        int pad = dp(48);
        root.setPadding(pad, dp(22), pad, dp(16));

        // ---- 第一行：标题 + 四个操作 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("所有应用");
        title.setTextColor(HomeActivity.TEXT);
        title.setTextSize(30);
        header.addView(title);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        sysBtn = pill("系统应用：隐藏");
        sysBtn.setId(View.generateViewId());
        header.addView(sysBtn, pillLp());

        dangerBtn = pill("危险应用：隐藏");
        dangerBtn.setId(View.generateViewId());
        header.addView(dangerBtn, pillLp());

        TextView clearBtn = pill("清空首页图标");
        clearBtn.setId(View.generateViewId());
        header.addView(clearBtn, pillLp());

        editBtn = pill("编辑：关");
        editBtn.setId(View.generateViewId());
        header.addView(editBtn, pillLp());

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 第二行：首页图标数 + 操作提示 ----
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = dp(10);

        hint = new TextView(this);
        hint.setTextSize(14);
        hint.setTextColor(HomeActivity.TEXT_DIM);
        row2.addView(hint);

        View sp2 = new View(this);
        row2.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));

        hintOps = new TextView(this);
        hintOps.setTextSize(14);
        hintOps.setTextColor(HomeActivity.TEXT_DIM);
        row2.addView(hintOps);

        root.addView(row2, row2lp);

        View divider = new View(this);
        divider.setBackgroundColor(HomeActivity.DIVIDER);
        LinearLayout.LayoutParams divlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divlp.topMargin = dp(10);
        divlp.bottomMargin = dp(2);
        root.addView(divider, divlp);

        // ---- 网格 ----
        grid = new GridView(this);
        grid.setId(View.generateViewId());
        grid.setNumColumns(6);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(12), 0, dp(12));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(true);
        grid.setSoundEffectsEnabled(false);
        grid.setVerticalScrollBarEnabled(false);

        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && grid.getSelectedItemPosition() < 0) grid.setSelection(0);
            }
        });

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= apps.size()) return;
                HomeActivity.AppEntry e = apps.get(pos);
                if (editMode) toggle(e);
                else { try { startActivity(e.intent); } catch (Exception ignored) { } }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < apps.size()) { toggle(apps.get(pos)); return true; }
                return false;
            }
        });

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        // 本页不使用壁纸，保持纯深色背景

        grid.setNextFocusUpId(editBtn.getId());
        editBtn.setNextFocusDownId(grid.getId());

        // 手动接管上下导航
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int pos = grid.getSelectedItemPosition();
                    if (pos >= 0 && pos < grid.getNumColumns()) {
                        editBtn.requestFocus();
                        return true;
                    }
                }
                return false;
            }
        });
        editBtn.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    grid.requestFocus();
                    if (grid.getSelectedItemPosition() < 0) grid.setSelection(0);
                    return true;
                }
                return false;
            }
        });

        editBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editMode = !editMode;
                updateUi();
                adapter.notifyDataSetChanged();
            }
        });

        sysBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSystem = !showSystem;
                updateUi();
                reload();
                toTop();
            }
        });

        dangerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDangerous = !showDangerous;
                updateUi();
                reload();
                toTop();
            }
        });

        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                HomeActivity.clearHome(AllAppsActivity.this);
                adapter.notifyDataSetChanged();
                Toast.makeText(AllAppsActivity.this,
                        "已清空首页图标\n回首页后可通过「编辑」重新挑选", Toast.LENGTH_LONG).show();
            }
        });

        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        restoreScroll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { lastScroll = grid.getFirstVisiblePosition(); } catch (Throwable ignored) { }
    }

    /** 恢复上次滚动位置（首次进入为 0） */
    private void restoreScroll() {
        grid.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (adapter.getCount() == 0) return;
                    int pos = Math.max(0, Math.min(lastScroll, adapter.getCount() - 1));
                    grid.setSelection(pos);
                    if (!grid.hasFocus()) grid.requestFocus();
                } catch (Throwable ignored) { }
            }
        }, 220);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_right);
    }

    private void toTop() {
        grid.postDelayed(new Runnable() {
            @Override public void run() {
                if (adapter.getCount() == 0) return;
                grid.setSelection(0);
                grid.smoothScrollToPosition(0);
                if (!grid.hasFocus()) grid.requestFocus();
            }
        }, 220);
    }

    private void toggle(HomeActivity.AppEntry e) {
        boolean on = HomeActivity.isOnHome(this, e);
        HomeActivity.setOnHome(this, e, !on);
        Toast.makeText(this, (on ? "已移出首页：" : "已加入首页：") + e.label,
                Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void updateUi() {
        editBtn.setText(editMode ? "编辑：开" : "编辑：关");
        sysBtn.setText(showSystem ? "系统应用：显示" : "系统应用：隐藏");
        dangerBtn.setText(showDangerous ? "危险应用：显示" : "危险应用：隐藏");
        hintOps.setText(editMode
                ? "确定键：加入 / 移出首页      返回键：回桌面"
                : "确定键：打开应用      返回键：回桌面");
    }

    private void reload() {
        apps.clear();
        List<HomeActivity.AppEntry> all = HomeActivity.queryLaunchable(this);
        int homeCount = 0;
        for (HomeActivity.AppEntry e : all) {
            if (HomeActivity.isOnHome(this, e)) homeCount++;
            if (!showSystem && e.isSystem) continue;
            if (!showDangerous && HomeActivity.isDangerous(e)) continue;
            apps.add(e);
        }
        hint.setText("首页已显示 " + homeCount + " 个图标");
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(10);
        return p;
    }

    private TextView pill(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(HomeActivity.TEXT);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(18), dp(9), dp(18), dp(9));
        tv.setFocusable(true);
        tv.setClickable(true);

        StateListDrawable sd = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(HomeActivity.ACCENT);
        pressed.setCornerRadius(dp(18));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x33FFFFFF);   // 半透明白，与卡片一致
        normal.setCornerRadius(dp(18));
        GradientDrawable foc = new GradientDrawable();
        foc.setColor(HomeActivity.ACCENT);   // 聚焦：与列表选中色一致
        foc.setCornerRadius(dp(18));
        sd.addState(new int[]{android.R.attr.state_focused}, foc);
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{}, normal);
        tv.setBackground(sd);
        return tv;
    }

    private Drawable cardBg(boolean dim) {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(HomeActivity.ACCENT);
        sel.setCornerRadius(dp(14));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(dim ? 0x1A000000 : HomeActivity.CARD);
        normal.setCornerRadius(dp(14));
        sd.addState(new int[]{android.R.attr.state_selected}, sel);
        sd.addState(new int[]{android.R.attr.state_focused}, sel);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout ll;
            ImageView iv;
            TextView tv;

            if (convertView == null) {
                ll = new LinearLayout(AllAppsActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER);
                ll.setPadding(dp(6), dp(12), dp(6), dp(10));
                ll.setLongClickable(true);

                iv = new ImageView(AllAppsActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ll.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));

                tv = new TextView(AllAppsActivity.this);
                tv.setTextColor(HomeActivity.TEXT);
                tv.setTextSize(12);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(8);
                ll.addView(tv, tlp);

                ll.setTag(new Object[]{iv, tv});
            } else {
                ll = (LinearLayout) convertView;
                Object[] tags = (Object[]) ll.getTag();
                iv = (ImageView) tags[0];
                tv = (TextView) tags[1];
            }

            HomeActivity.AppEntry e = apps.get(position);
            boolean on = HomeActivity.isOnHome(AllAppsActivity.this, e);
            Drawable ic = IconFactory.get(AllAppsActivity.this, e.key(), e.icon, dp(56));
            if (ic != null) iv.setImageDrawable(ic);
            tv.setText(on ? e.label + " ✓" : e.label);
            ll.setBackground(cardBg(editMode && !on));
            ll.setContentDescription(e.label);
            return ll;
        }
    }
}
