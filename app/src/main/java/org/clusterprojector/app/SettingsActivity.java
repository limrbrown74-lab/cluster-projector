package org.clusterprojector.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置页：投屏方式切换（首页同步生效）、打赏支持、关于、免责声明、开源许可。
 */
public class SettingsActivity extends Activity {

    /** 首页投屏方式常量，与首页容器一一对应。 */
    public static final int MODE_APP_CAST = 0;
    public static final int MODE_DASHBOARD = 1;
    public static final int MODE_AVM_MAP = 2;

    public static final String PREFS_NAME = "cluster_projector_prefs";
    public static final String KEY_CAST_MODE = "cast_mode";

    private RadioGroup modeGroup;
    private LinearLayout supportContent;

    public static int readCastMode(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_CAST_MODE, MODE_APP_CAST);
    }

    public static void writeCastMode(Context context, int mode) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_CAST_MODE, mode).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button backBtn = findViewById(R.id.settingsBackBtn);
        backBtn.setOnClickListener(v -> finish());

        modeGroup = findViewById(R.id.modeGroup);
        RadioButton modeAppCast = findViewById(R.id.modeAppCast);
        RadioButton modeDashboard = findViewById(R.id.modeDashboard);
        RadioButton modeAvmMap = findViewById(R.id.modeAvmMap);

        int current = readCastMode(this);
        if (current == MODE_DASHBOARD) {
            modeDashboard.setChecked(true);
        } else if (current == MODE_AVM_MAP) {
            modeAvmMap.setChecked(true);
        } else {
            modeAppCast.setChecked(true);
        }

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode;
            if (checkedId == R.id.modeDashboard) {
                mode = MODE_DASHBOARD;
            } else if (checkedId == R.id.modeAvmMap) {
                mode = MODE_AVM_MAP;
            } else {
                mode = MODE_APP_CAST;
            }
            writeCastMode(this, mode);
            AppLog.i("SettingsActivity", "cast mode -> " + mode);
        });

        // 仪表管理
        LinearLayout dashboardManageCard = findViewById(R.id.dashboardManageCard);
        dashboardManageCard.setOnClickListener(
                v -> startActivity(new android.content.Intent(this, DashboardManageActivity.class)));

        // 打赏支持：点击展开赞赏码
        supportContent = findViewById(R.id.supportContent);
        LinearLayout supportCard = findViewById(R.id.supportCard);
        supportCard.setOnClickListener(v -> {
            boolean visible = supportContent.getVisibility() == View.VISIBLE;
            supportContent.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        // 关于
        LinearLayout aboutCard = findViewById(R.id.aboutCard);
        aboutCard.setOnClickListener(v -> showAboutDialog());

        // 免责声明
        LinearLayout disclaimerCard = findViewById(R.id.disclaimerCard);
        disclaimerCard.setOnClickListener(v -> showTextDialog("免责声明", DISCLAIMER_TEXT));

        // 开源许可
        LinearLayout licenseCard = findViewById(R.id.licenseCard);
        licenseCard.setOnClickListener(v -> showTextDialog("开源许可（MIT）", MIT_LICENSE_TEXT));
    }

    private String readVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void showAboutDialog() {
        String content = "应用名称：仪表投屏\n"
                + "版本号：" + readVersionName() + "\n"
                + "包名：" + getPackageName() + "\n\n"
                + "目标车机：吉利博越L（FX11）· 银河 NOS 2.3\n"
                + "其他系统请自行适配，风险自担\n\n"
                + "开源地址：github.com/limrbrown74-lab/cluster-projector\n"
                + "许可协议：MIT License";
        showTextDialog("关于", content);
    }

    private void showTextDialog(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create();
        dialog.show();
        // 大屏车机放大正文，避免默认文字过小
        try {
            TextView tv = dialog.findViewById(android.R.id.message);
            if (tv != null) {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final String DISCLAIMER_TEXT =
            "1. 本应用仅用于吉利博越L（FX11）车机系统的学习、研究用途，请勿用于非法或商业目的。\n\n"
            + "2. 使用本应用会调用车机底层接口（ECARX XUI / VHAL / AVM），将仪表盘从原厂 QNX 显示源切换到 Android 显示源，属于对车机系统的深度修改。\n\n"
            + "3. 任何因使用本应用导致的车机黑屏、功能异常、系统损坏、数据丢失、保修失效等问题，作者均不承担任何责任，风险由使用者自行承担。\n\n"
            + "4. 本应用仅适配吉利博越L 银河 NOS 2.3，其他系统、车型、版本未经验证，请自行适配，适配过程中出现的任何问题与作者无关。\n\n"
            + "5. 请仅在你本人所有的车辆上进行测试，遵守当地法律法规和厂商质保条款。\n\n"
            + "6. 使用即表示你已阅读并同意以上全部条款。";

    private static final String MIT_LICENSE_TEXT =
            "MIT License\n\n"
            + "Copyright (c) 2026 limrbrown74-lab\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            + "of this software and associated documentation files (the \"Software\"), to deal\n"
            + "in the Software without restriction, including without limitation the rights\n"
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            + "copies of the Software, and to permit persons to whom the Software is\n"
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in\n"
            + "all copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN\n"
            + "THE SOFTWARE.";
}
