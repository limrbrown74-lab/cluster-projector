package org.clusterprojector.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 特斯拉风格数字仪表盘（1920x532 基准画布自适应缩放）。
 *
 * <p>布局：顶栏状态指示灯 / 左区超大字车速+档位 / 中区地图导航 / 右区转速+油量+水温。
 * 日间(DAY)与夜间(NIGHT)两套配色，数据全部来自 {@link ClusterVehicleTelemetry}。
 * 地图区为预留示意（画路网+导航线），可替换为真实地图投屏 Surface。
 */
public class ClusterDashboardView extends View {

    public static final int THEME_DAY = 0;
    public static final int THEME_NIGHT = 1;

    /** 逻辑基准分辨率（仪表盘物理尺寸）。 */
    private static final float BASE_W = 1920f;
    private static final float BASE_H = 532f;

    // ---- 主题色（晨光日间 / 夜幕夜间） ----
    private int bgTop, bgBottom, bgGlow;
    private int textPrimary, textSecondary, textTertiary;
    private int accent, accentDim;
    private int statusGreen, statusOrange, statusRed, statusAmber;
    private int cardBg, cardBorder;
    private int gaugeTrack;
    private int mapBgTop, mapBgBottom, roadMain, roadSecondary, routeColor;
    private int speedColor;

    private int theme = THEME_NIGHT;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bgEnabled = true;
    private boolean configChecked;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() == View.VISIBLE) {
                invalidate();
            }
            handler.postDelayed(this, 100);
        }
    };

    public ClusterDashboardView(Context context) {
        super(context);
        init();
    }

    public ClusterDashboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        applyTheme();
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        DashboardConfig.init(getContext());
    }

    /** 是否绘制背景（合并投屏时关闭，只画元素）。 */
    public void setBackgroundEnabled(boolean enabled) {
        bgEnabled = enabled;
        invalidate();
    }

    public boolean isBackgroundEnabled() {
        return bgEnabled;
    }

    public void setTheme(int theme) {
        if (this.theme == theme) {
            return;
        }
        this.theme = theme;
        applyTheme();
        invalidate();
    }

    public int getThemeMode() {
        return theme;
    }

    private void applyTheme() {
        if (theme == THEME_DAY) {
            bgTop = 0xFFEFF3F6;
            bgBottom = 0xFFC4D1DB;
            bgGlow = 0x55FFDEA0;
            textPrimary = 0xFF152233;
            textSecondary = 0xFF5A6B7C;
            textTertiary = 0xFF8A9BAB;
            accent = 0xFF1F6FB2;
            accentDim = 0xFF1F6FB2;
            statusGreen = 0xFF2E7D32;
            statusOrange = 0xFFB36B1F;
            statusRed = 0xFFC0392B;
            statusAmber = 0xFFCF741A;
            cardBg = 0x80FFFFFF;
            cardBorder = 0x22000000;
            gaugeTrack = 0x1F152233;
            mapBgTop = 0xFFDEE7ED;
            mapBgBottom = 0xFFCFDCE4;
            roadMain = Color.WHITE;
            roadSecondary = 0xFFF7C948;
            routeColor = 0xFF1F6FB2;
            speedColor = 0xFF152233;
        } else {
            bgTop = 0xFF1A1E24;
            bgBottom = 0xFF0B0D10;
            bgGlow = 0x003399FF;
            textPrimary = 0xFFFFFFFF;
            textSecondary = 0xFF8A97A3;
            textTertiary = 0xFF5E6E7C;
            accent = 0xFF3399FF;
            accentDim = 0xFF3399FF;
            statusGreen = 0xFF4DD966;
            statusOrange = 0xFFFF9933;
            statusRed = 0xFFF24D4D;
            statusAmber = 0xFFFF9933;
            cardBg = 0x12FFFFFF;
            cardBorder = 0x1FFFFFFF;
            gaugeTrack = 0x1AFFFFFF;
            mapBgTop = 0xFF1A2026;
            mapBgBottom = 0xFF12171C;
            roadMain = 0xFF262C33;
            roadSecondary = 0xFF3D3824;
            routeColor = 0xFF3399FF;
            speedColor = Color.WHITE;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 保持 1920:532 比例
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w > 0 && h > 0) {
            float ratio = BASE_W / BASE_H;
            if ((float) w / h > ratio) {
                w = (int) (h * ratio);
            } else {
                h = (int) (w / ratio);
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        canvas.save();
        canvas.scale(vw / BASE_W, vh / BASE_H);

        if (bgEnabled) {
            drawBackground(canvas);
        }
        drawTopDivider(canvas);

        // 元素级调度：每个元素支持独立"显示/隐藏 + 位置偏移"
        drawElement(canvas, DashboardConfig.EL_TELLTALES, () -> drawTopBarLeft(canvas));
        drawElement(canvas, DashboardConfig.EL_TOPBAR_RIGHT, () -> drawTopBarRight(canvas));
        drawElement(canvas, DashboardConfig.EL_SPEED, () -> drawSpeedNumber(canvas));
        drawElement(canvas, DashboardConfig.EL_GEAR, () -> drawGear(canvas));
        drawElement(canvas, DashboardConfig.EL_RANGE, () -> drawRangeCard(canvas));
        drawElement(canvas, DashboardConfig.EL_ODOMETER, () -> drawOdometerCard(canvas));
        drawElement(canvas, DashboardConfig.EL_MAP, () -> drawMapZone(canvas));
        drawElement(canvas, DashboardConfig.EL_RPM, () -> drawRpmGauge(canvas));
        drawElement(canvas, DashboardConfig.EL_FUEL, () -> drawFuelGauge(canvas));
        drawElement(canvas, DashboardConfig.EL_COOLANT, () -> drawCoolantGauge(canvas));
        drawBottomLine(canvas);

        canvas.restore();
    }

    /** 元素包裹：不可见跳过，有位置偏移时整体平移。 */
    private void drawElement(Canvas canvas, String elementId, Runnable draw) {
        if (!DashboardConfig.elementVisible(elementId)) {
            return;
        }
        float dx = DashboardConfig.offsetX(DashboardConfig.elementDxPct(elementId), BASE_W);
        float dy = DashboardConfig.offsetY(DashboardConfig.elementDyPct(elementId), BASE_H);
        if (dx == 0f && dy == 0f) {
            draw.run();
            return;
        }
        canvas.save();
        canvas.translate(dx, dy);
        draw.run();
        canvas.restore();
    }

    // ------------------------------------------------------------ background

    private void drawBackground(Canvas canvas) {
        LinearGradient lg = new LinearGradient(0, 0, 0, BASE_H,
                bgTop, bgBottom, Shader.TileMode.CLAMP);
        p.setShader(lg);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, BASE_W, BASE_H, p);
        p.setShader(null);

        if (theme == THEME_DAY) {
            RadialGradient rg = new RadialGradient(270f, 40f, 800f,
                    new int[]{bgGlow, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            p.setShader(rg);
            canvas.drawRect(0, 0, BASE_W, 320f, p);
            p.setShader(null);
        } else {
            RadialGradient rg = new RadialGradient(BASE_W / 2f, -60f, 1200f,
                    new int[]{0xFF23282F, 0x000B0D10}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            p.setShader(rg);
            canvas.drawRect(0, 0, BASE_W, BASE_H, p);
            p.setShader(null);
        }
    }

    // --------------------------------------------------------------- top bar

    private void drawTopDivider(Canvas canvas) {
        // 分隔线（装饰，不参与元素管理）
        p.setColor(cardBorder);
        p.setStrokeWidth(1.5f);
        p.setStyle(Paint.Style.STROKE);
        canvas.drawLine(0, 48f, BASE_W, 48f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 顶栏左侧：转向/灯光指示灯 + 自动驻车 + 安全带。 */
    private void drawTopBarLeft(Canvas canvas) {
        float x = 28f;

        // 远光（蓝）
        x = drawTellTale(canvas, x, 8f, TellTale.HIGH_BEAM);
        x = drawTellTale(canvas, x, 8f, TellTale.LEFT_TURN);
        x = drawTellTale(canvas, x, 8f, TellTale.RIGHT_TURN);
        x = drawTellTale(canvas, x, 8f, TellTale.LOW_BEAM);
        x = drawTellTale(canvas, x, 8f, TellTale.HAZARD);
        x += 4f;

        // 自动驻车 chip
        boolean epb = ClusterVehicleTelemetry.parkingBrake();
        if (epb) {
            float w = 120f;
            drawChip(canvas, x, 8f, w, 32f, statusGreen, withAlpha(statusGreen, 0.4f), 1.5f);
            drawCircleA(canvas, x + 14f, 24f, 9f, statusGreen);
            p.setColor(statusGreen);
            p.setTextSize(13f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("自动驻车", x + 34f, 28.5f, p);
            p.setTypeface(Typeface.DEFAULT);
            x += w + 8f;
        }

        // 分隔
        p.setColor(cardBorder);
        canvas.drawRect(x, 16f, x + 1f, 32f, p);
        x += 9f;

        // 安全带
        boolean belt = ClusterVehicleTelemetry.beltUnfastened();
        if (belt) {
            float w = 108f;
            drawChip(canvas, x, 8f, w, 32f, statusAmber, withAlpha(statusAmber, 0.4f), 1.5f);
            drawBeltIcon(canvas, x + 16f, 24f, statusAmber);
            p.setColor(statusAmber);
            p.setTextSize(13f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("安全带", x + 38f, 28.5f, p);
            p.setTypeface(Typeface.DEFAULT);
        }
    }

    /** 顶栏右侧：时间 / 温度 / ACC / 歌曲 / 信号。 */
    private void drawTopBarRight(Canvas canvas) {
        float rx = BASE_W - 28f;

        // 时间（模拟，使用系统时间）
        p.setColor(textSecondary);
        p.setTextSize(14f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        String time = sdf.format(new java.util.Date());
        float tw = p.measureText(time);
        rx -= tw;
        canvas.drawText(time, rx, 28.5f, p);
        rx -= 16f;

        // 环境温度
        int amb = ClusterVehicleTelemetry.ambient();
        String ambText = amb > -100 ? amb + "°C" : "--°C";
        p.setTextSize(14f);
        float aw = p.measureText(ambText);
        rx -= aw;
        canvas.drawText(ambText, rx, 28.5f, p);
        rx -= 16f;

        // ACC 巡航
        boolean cruise = ClusterVehicleTelemetry.cruiseOn();
        double acc = ClusterVehicleTelemetry.accSetSpeed();
        if (cruise && acc > 0) {
            String accText = "ACC " + (int) Math.round(acc) + " km/h";
            p.setTextSize(13f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            float accW = p.measureText(accText) + 34f;
            rx -= accW;
            drawChip(canvas, rx, 8f, accW, 32f, accent, withAlpha(accent, 0.4f), 1.5f);
            drawAccIcon(canvas, rx + 16f, 24f, accent);
            p.setColor(accent);
            canvas.drawText(accText, rx + 30f, 28.5f, p);
            rx -= 8f;
            p.setTypeface(Typeface.DEFAULT);
        }

        // 歌曲（模拟，预留媒体元数据接口）
        String song = "晴天 · 周杰伦";
        p.setTextSize(13f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float songW = p.measureText(song) + 32f;
        rx -= songW;
        drawChip(canvas, rx, 8f, songW, 32f, theme == THEME_DAY ? 0x1F000000 : 0x12FFFFFF,
                cardBorder, 1.2f);
        drawMusicIcon(canvas, rx + 13f, 24f, theme == THEME_DAY ? 0xFFB3412F : 0xFFFF6B5A);
        p.setColor(textSecondary);
        canvas.drawText(song, rx + 30f, 28.5f, p);
        p.setTypeface(Typeface.DEFAULT);

        // 信号正常
        boolean online = ClusterVehicleTelemetry.online();
        String sigText = online ? "信号正常" : "信号丢失";
        int sigColor = online ? statusGreen : statusRed;
        p.setTextSize(12f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float sigW = p.measureText(sigText) + 30f;
        rx -= sigW;
        drawChip(canvas, rx, 8f, sigW, 32f, sigColor, withAlpha(sigColor, 0.4f), 1.2f);
        drawSignalIcon(canvas, rx + 12f, 24f, sigColor);
        p.setColor(sigColor);
        canvas.drawText(sigText, rx + 26f, 28.5f, p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private enum TellTale {
        HIGH_BEAM, LEFT_TURN, RIGHT_TURN, LOW_BEAM, HAZARD
    }

    private float drawTellTale(Canvas canvas, float x, float y, TellTale t) {
        float size = 40f;
        boolean active;
        int color;
        switch (t) {
            case HIGH_BEAM:
                active = ClusterVehicleTelemetry.highBeam();
                color = accent;
                break;
            case LEFT_TURN:
                active = ClusterVehicleTelemetry.leftTurn();
                color = statusGreen;
                break;
            case RIGHT_TURN:
                active = ClusterVehicleTelemetry.rightTurn();
                color = statusGreen;
                break;
            case LOW_BEAM:
                active = ClusterVehicleTelemetry.lowBeam();
                color = statusGreen;
                break;
            default:
                active = false;
                color = statusRed;
                break;
        }
        drawChip(canvas, x, y, size, 32f,
                active ? color : (theme == THEME_DAY ? 0x1F000000 : 0x0FFFFFFF),
                active ? withAlpha(color, 0.4f) : (theme == THEME_DAY ? 0x26000000 : 0x1FFFFFFF),
                active ? 1.5f : 1f);
        switch (t) {
            case HIGH_BEAM:
                drawHighBeam(canvas, x + size / 2f, y + 16f, active ? color : textTertiary);
                break;
            case LEFT_TURN:
                drawTurnArrow(canvas, x + size / 2f, y + 16f, true, active ? color : textTertiary);
                break;
            case RIGHT_TURN:
                drawTurnArrow(canvas, x + size / 2f, y + 16f, false, active ? color : textTertiary);
                break;
            case LOW_BEAM:
                drawLowBeam(canvas, x + size / 2f, y + 16f, active ? color : textTertiary);
                break;
            default:
                drawHazard(canvas, x + size / 2f, y + 16f, textTertiary);
                break;
        }
        return x + size + 8f;
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (255 * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void drawChip(Canvas canvas, float x, float y, float w, float h, int fill,
                          int border, float stroke) {
        RectF r = new RectF(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        canvas.drawRoundRect(r, 8f, 8f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(border);
        canvas.drawRoundRect(r, 8f, 8f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawCircleA(Canvas canvas, float cx, float cy, float r, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(color);
        canvas.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(10f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("A", cx - p.measureText("A") / 2f, cy + 3.5f, p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawTurnArrow(Canvas canvas, float cx, float cy, boolean left, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.8f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        if (left) {
            path.moveTo(cx + 8f, cy - 9f);
            path.lineTo(cx - 6f, cy);
            path.lineTo(cx + 8f, cy + 9f);
            path.moveTo(cx - 6f, cy);
            path.lineTo(cx + 13f, cy);
        } else {
            path.moveTo(cx - 8f, cy - 9f);
            path.lineTo(cx + 6f, cy);
            path.lineTo(cx - 8f, cy + 9f);
            path.moveTo(cx + 6f, cy);
            path.lineTo(cx - 13f, cy);
        }
        canvas.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawHighBeam(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.6f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        path.moveTo(cx - 11f, cy + 7f);
        path.lineTo(cx - 5f, cy - 6f);
        path.lineTo(cx + 5f, cy - 6f);
        path.lineTo(cx + 11f, cy + 7f);
        canvas.drawPath(path, p);
        p.setStrokeWidth(3.2f);
        canvas.drawLine(cx - 7f, cy + 2f, cx + 7f, cy + 2f, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawLowBeam(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.6f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        path.moveTo(cx - 11f, cy + 7f);
        path.lineTo(cx - 5f, cy - 6f);
        path.lineTo(cx + 5f, cy - 6f);
        path.lineTo(cx + 11f, cy + 7f);
        canvas.drawPath(path, p);
        p.setStrokeWidth(3.2f);
        canvas.drawLine(cx - 7f, cy + 2f, cx + 7f, cy + 2f, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawHazard(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.4f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        path.moveTo(cx, cy - 7f);
        path.lineTo(cx + 9f, cy + 7f);
        path.lineTo(cx - 9f, cy + 7f);
        path.close();
        canvas.drawPath(path, p);
        p.setStrokeWidth(2f);
        canvas.drawLine(cx, cy - 2f, cx, cy + 3f, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPoint(cx, cy + 5.5f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawBeltIcon(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.4f);
        p.setStyle(Paint.Style.STROKE);
        Path path = new Path();
        path.addCircle(cx, cy, 6f, Path.Direction.CW);
        canvas.drawPath(path, p);
        canvas.drawLine(cx, cy - 2f, cx, cy + 2f, p);
        canvas.drawPoint(cx, cy + 4.5f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawAccIcon(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, 6.4f, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 2.4f, p);
        p.setStrokeWidth(1.6f);
        p.setStyle(Paint.Style.STROKE);
        canvas.drawLine(cx, cy - 6.4f, cx, cy - 4f, p);
        canvas.drawLine(cx, cy + 4f, cx, cy + 6.4f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawMusicIcon(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStrokeWidth(2.4f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - 4f, cy + 7f, cx - 4f, cy - 5f, p);
        canvas.drawLine(cx - 4f, cy - 5f, cx + 6f, cy - 8f, p);
        canvas.drawLine(cx + 6f, cy - 8f, cx + 6f, cy + 4f, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - 4f, cy + 7f, 3f, p);
        canvas.drawCircle(cx + 6f, cy + 4f, 3f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSignalIcon(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(cx - 7f, cy + 4f, cx - 4f, cy + 9f, p);
        canvas.drawRect(cx - 2f, cy + 1f, cx + 1f, cy + 9f, p);
        canvas.drawRect(cx + 3f, cy - 3f, cx + 6f, cy + 9f, p);
    }

    // ------------------------------------------------------------ speed zone

    private void drawSpeedNumber(Canvas canvas) {
        float x = 44f;
        float y = 64f;

        // 标题
        p.setColor(textSecondary);
        p.setTextSize(14f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setLetterSpacing(0.12f);
        canvas.drawText("SPEED 车速", x, y + 16f, p);
        p.setLetterSpacing(0f);
        p.setTypeface(Typeface.DEFAULT);

        // 大数字
        int speed = ClusterVehicleTelemetry.speed();
        String speedText = speed >= 0 ? String.valueOf(speed) : "--";
        p.setColor(speedColor);
        p.setTextSize(170f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setShadowLayer(18f, 0, 4f,
                theme == THEME_DAY ? 0x99FFFFFF : withAlpha(accent, 0.25f));
        canvas.drawText(speedText, x, y + 178f, p);
        p.setShadowLayer(0, 0, 0, 0);
        p.setTypeface(Typeface.DEFAULT);

        // KM/H
        p.setColor(textSecondary);
        p.setTextSize(20f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setLetterSpacing(0.2f);
        canvas.drawText("KM/H", x, y + 210f, p);
        p.setLetterSpacing(0f);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawGear(Canvas canvas) {
        float x = 44f;
        float y = 64f;

        // 档位
        float gx = x;
        String currentGear = ClusterVehicleTelemetry.gearText();
        String[] gears = {"P", "R", "N", "D"};
        for (String g : gears) {
            boolean active = g.equals(currentGear);
            float gw = 50f;
            drawChip(canvas, gx, y + 240f, gw, 42f,
                    active ? accent : (theme == THEME_DAY ? 0x80FFFFFF : 0x0FFFFFFF),
                    active ? withAlpha(accent, 0.6f) : cardBorder, active ? 1f : 1f);
            p.setColor(active ? (theme == THEME_DAY ? Color.WHITE : 0xFF0B0D10) : textTertiary);
            p.setTextSize(20f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            float tw2 = p.measureText(g);
            canvas.drawText(g, gx + (gw - tw2) / 2f, y + 240f + 28f, p);
            p.setTypeface(Typeface.DEFAULT);
            gx += gw + 10f;
        }
    }

    private void drawRangeCard(Canvas canvas) {
        float x = 44f;
        float y = 64f;

        // 续航
        float cy = y + 296f;
        float cardW = (372f - 10f) / 2f;
        float cardH = 60f;

        int range = ClusterVehicleTelemetry.range();
        String rangeText = range >= 0 ? range + " km" : "--";
        drawInfoCard(canvas, x, cy, cardW, cardH,
                0xFF1F6FB2, "续航", rangeText);
    }

    private void drawOdometerCard(Canvas canvas) {
        float x = 44f;
        float y = 64f;

        // 总里程
        float cy = y + 296f;
        float cardW = (372f - 10f) / 2f;
        float cardH = 60f;

        double odo = ClusterVehicleTelemetry.odometerKm();
        String odoText = odo >= 0 ? formatOdo(odo) + " km" : "--";
        drawInfoCard(canvas, x + cardW + 10f, cy, cardW, cardH,
                0xFFB36B1F, "总里程", odoText);
    }

    private static String formatOdo(double km) {
        if (km >= 1000) {
            java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0");
            return df.format(km);
        }
        return String.valueOf((int) km);
    }

    private void drawInfoCard(Canvas canvas, float x, float y, float w, float h,
                              int iconColor, String label, String value) {
        drawChip(canvas, x, y, w, h, cardBg, cardBorder, 1f);
        // 图标
        p.setColor(iconColor);
        p.setStrokeWidth(2.4f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (iconColor == 0xFF1F6FB2 || iconColor == 0xFF3399FF) {
            Path path = new Path();
            path.moveTo(x + 14f, y + 40f);
            path.lineTo(x + 24f, y + 22f);
            path.lineTo(x + 34f, y + 40f);
            canvas.drawPath(path, p);
            canvas.drawLine(x + 20f, y + 34f, x + 28f, y + 34f, p);
        } else {
            canvas.drawCircle(x + 24f, y + 31f, 10f, p);
            Path path = new Path();
            path.moveTo(x + 24f, y + 24f);
            path.lineTo(x + 24f, y + 31f);
            path.lineTo(x + 27f, y + 33f);
            canvas.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeJoin(Paint.Join.MITER);

        // 值
        p.setColor(textPrimary);
        p.setTextSize(18f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(value, x + 42f, y + 32f, p);
        // 标签
        p.setColor(textSecondary);
        p.setTextSize(10f);
        p.setTypeface(Typeface.DEFAULT);
        canvas.drawText(label, x + 42f, y + 48f, p);
    }

    // -------------------------------------------------------------- map zone

    private void drawMapZone(Canvas canvas) {
        float x = 442f;
        float y = 62f;
        float w = 1018f;
        float h = 448f;
        RectF r = new RectF(x, y, x + w, y + h);

        // 底
        LinearGradient lg = new LinearGradient(0, y, 0, y + h, mapBgTop, mapBgBottom,
                Shader.TileMode.CLAMP);
        p.setShader(lg);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(r, 18f, 18f, p);
        p.setShader(null);

        canvas.save();
        canvas.clipPath(roundRectPath(x, y, w, h, 18f));

        // 水系
        p.setColor(theme == THEME_DAY ? 0x70A9C6D8 : 0x801E2F3C);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(26f);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path water = new Path();
        water.moveTo(x + 898f, y - 20f);
        water.cubicTo(x + 858f, y + 90f, x + 948f, y + 180f, x + 898f, y + 300f);
        water.cubicTo(x + 868f, y + 380f, x + 908f, y + 420f, x + 888f, y + 470f);
        canvas.drawPath(water, p);

        // 快速路
        p.setColor(roadSecondary);
        p.setStrokeWidth(16f);
        canvas.drawLine(x, y + 96f, x + w, y + 96f, p);
        p.setStrokeWidth(14f);
        canvas.drawLine(x + 340f, y, x + 340f, y + 470f, p);

        // 主干道
        p.setColor(roadMain);
        p.setStrokeWidth(12f);
        canvas.drawLine(x, y + 300f, x + w, y + 300f, p);
        canvas.drawLine(x + 660f, y, x + 660f, y + 470f, p);
        p.setStrokeWidth(8f);
        canvas.drawLine(x, y + 190f, x + w, y + 190f, p);

        // 次路
        p.setStrokeWidth(6f);
        canvas.drawLine(x + 160f, y, x + 160f, y + 470f, p);
        canvas.drawLine(x + 820f, y, x + 820f, y + 470f, p);
        canvas.drawLine(x + 200f, y + 380f, x + w, y + 380f, p);

        // 路名
        p.setColor(textTertiary);
        p.setTextSize(14f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("新华路", x + 700f, y + 88f, p);
        canvas.drawText("起海路", x + 44f, y + 292f, p);
        p.setTypeface(Typeface.DEFAULT);

        // 导航路线
        p.setColor(routeColor);
        p.setStrokeWidth(7f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path route = new Path();
        route.moveTo(x + 878f, y + 448f);
        route.quadTo(x + 838f, y + 340f, x + 658f, y + 300f);
        route.lineTo(x + 468f, y + 300f);
        route.quadTo(x + 398f, y + 300f, x + 358f, y + 200f);
        route.lineTo(x + 318f, y + 120f);
        canvas.drawPath(route, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);

        // 目的地
        p.setColor(statusGreen);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x + 318f, y + 120f, 11f, p);
        p.setColor(theme == THEME_DAY ? Color.WHITE : 0xFF12171C);
        p.setStrokeWidth(4f);
        p.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x + 318f, y + 120f, 11f, p);
        p.setStyle(Paint.Style.FILL);

        // 导航提示框
        String navText = "前方 300 米右转 · 进入新华路";
        p.setTextSize(15f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float nw = p.measureText(navText) + 44f;
        float nx = x + (w - nw) / 2f;
        drawChip(canvas, nx, y + 16f, nw, 34f,
                theme == THEME_DAY ? 0xE6FFFFFF : 0xE6111823,
                theme == THEME_DAY ? 0x24000000 : 0x24FFFFFF, 1f);
        drawNavArrow(canvas, nx + 16f, y + 33f, accent);
        p.setColor(textPrimary);
        canvas.drawText(navText, nx + 28f, y + 34f, p);
        p.setTypeface(Typeface.DEFAULT);

        // 剩余信息
        String remainText = "剩余 12.4 km · 预计 18 分钟";
        p.setTextSize(14f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float rw = p.measureText(remainText) + 28f;
        drawChip(canvas, x + 16f, y + h - 38f, rw, 30f,
                theme == THEME_DAY ? 0xDBFFFFFF : 0xDB101218,
                theme == THEME_DAY ? 0x22000000 : 0x22FFFFFF, 1f);
        p.setColor(statusGreen);
        canvas.drawText(remainText, x + 30f, y + h - 19f, p);
        p.setTypeface(Typeface.DEFAULT);

        canvas.restore();

        // 边框
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(cardBorder);
        canvas.drawRoundRect(r, 18f, 18f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private Path roundRectPath(float x, float y, float w, float h, float r) {
        Path path = new Path();
        path.addRoundRect(new RectF(x, y, x + w, y + h), r, r, Path.Direction.CW);
        return path;
    }

    private void drawNavArrow(Canvas canvas, float cx, float cy, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        path.moveTo(cx, cy - 8f);
        path.lineTo(cx + 6f, cy);
        path.lineTo(cx + 2f, cy);
        path.lineTo(cx + 2f, cy + 8f);
        path.lineTo(cx - 2f, cy + 8f);
        path.lineTo(cx - 2f, cy);
        path.lineTo(cx - 6f, cy);
        path.close();
        canvas.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ----------------------------------------------------------- right zone

    private void drawRpmGauge(Canvas canvas) {
        float x = 1486f;
        float y = 64f;
        float w = 406f;

        // ---- 转速卡 ----
        float rpmH = 176f;
        drawChip(canvas, x, y, w, rpmH, cardBg, cardBorder, 1f);

        int rpm = ClusterVehicleTelemetry.rpm();
        String rpmText = rpm >= 0 ? String.valueOf(rpm) : "--";
        p.setColor(textPrimary);
        p.setTextSize(72f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(rpmText, x + 22f, y + 78f, p);
        p.setTypeface(Typeface.DEFAULT);

        p.setColor(textTertiary);
        p.setTextSize(14f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setLetterSpacing(0.06f);
        float rpmLabelW = p.measureText("RPM");
        canvas.drawText("RPM", x + w - 22f - rpmLabelW, y + 78f, p);
        p.setLetterSpacing(0f);

        p.setColor(textSecondary);
        p.setTextSize(12f);
        p.setTypeface(Typeface.DEFAULT);
        canvas.drawText("转速", x + 22f, y + 96f, p);

        // 0-8 进度条（带红区尾段 25%）
        float barY = y + 112f;
        float barH = 14f;
        float barW = w - 44f;
        RectF track = new RectF(x + 22f, barY, x + 22f + barW, barY + barH);
        p.setColor(gaugeTrack);
        canvas.drawRoundRect(track, barH / 2f, barH / 2f, p);

        float maxRpm = 8000f;
        float pct = rpm >= 0 ? Math.min(rpm / maxRpm, 1f) : 0f;

        // 红区（6-8 => 75%-100%）
        RectF redZone = new RectF(x + 22f + barW * 0.75f, barY, x + 22f + barW, barY + barH);
        p.setColor(statusRed);
        canvas.drawRoundRect(redZone, 0f, 0f, p);

        // 当前值填充（蓝→绿）
        if (pct > 0) {
            LinearGradient fg = new LinearGradient(x + 22f, 0, x + 22f + barW, 0,
                    new int[]{accent, statusGreen}, null, Shader.TileMode.CLAMP);
            p.setShader(fg);
            RectF fill = new RectF(x + 22f, barY, x + 22f + barW * pct, barY + barH);
            canvas.drawRoundRect(fill, barH / 2f, barH / 2f, p);
            p.setShader(null);
        }

        // 当前值刻度线
        if (pct > 0.02f && pct < 0.98f) {
            p.setColor(textPrimary);
            canvas.drawRect(x + 22f + barW * pct - 2f, barY - 3f, x + 22f + barW * pct + 2f, barY + barH + 3f, p);
        }

        // 刻度数字
        p.setColor(textTertiary);
        p.setTextSize(11f);
        String[] rpmTicks = {"0", "2", "4", "6", "8"};
        float tickW = barW / 4f;
        for (int i = 0; i < 5; i++) {
            boolean last = i == 4;
            p.setColor(last ? statusRed : textTertiary);
            if (last) {
                p.setTypeface(Typeface.DEFAULT_BOLD);
            }
            canvas.drawText(rpmTicks[i], x + 22f + tickW * i - 6f, barY + barH + 16f, p);
            if (last) {
                p.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void drawFuelGauge(Canvas canvas) {
        float x = 1486f;
        float y = 64f;
        float w = 406f;

        float rpmH = 176f;
        float subY = y + rpmH + 12f;
        float subW = (w - 12f) / 2f;
        float subH = 118f;

        double fuel = ClusterVehicleTelemetry.fuelPercent();
        String fuelText = fuel >= 0 ? (int) Math.round(fuel) + "%" : "--%";
        drawSubGauge(canvas, x, subY, subW, subH, 0xFFB36B1F, statusOrange,
                "油量", fuelText, "E", "F", fuel >= 0 ? (float) fuel / 100f : 0f);
    }

    private void drawCoolantGauge(Canvas canvas) {
        float x = 1486f;
        float y = 64f;
        float w = 406f;

        float rpmH = 176f;
        float subY = y + rpmH + 12f;
        float subW = (w - 12f) / 2f;
        float subH = 118f;

        double coolant = ClusterVehicleTelemetry.coolantC();
        String coolText = coolant > -100 ? (int) Math.round(coolant) + "°C" : "--°C";
        float coolPct = coolant > -100 ? (float) (Math.min(Math.max(coolant, 40f), 120f) - 40f) / 80f : 0f;
        drawSubGauge(canvas, x + subW + 12f, subY, subW, subH, 0xFF2E7D32, statusGreen,
                "水温", coolText, "C", "H", coolPct);
    }

    private void drawSubGauge(Canvas canvas, float x, float y, float w, float h,
                              int gradStart, int gradEnd, String label, String value,
                              String lo, String hi, float pct) {
        drawChip(canvas, x, y, w, h, cardBg, cardBorder, 1f);

        p.setColor(textPrimary);
        p.setTextSize(40f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(value, x + 16f, y + 46f, p);
        p.setTypeface(Typeface.DEFAULT);

        p.setColor(textTertiary);
        p.setTextSize(12f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float labelW = p.measureText(label);
        canvas.drawText(label, x + w - 16f - labelW, y + 20f, p);
        p.setTypeface(Typeface.DEFAULT);

        // 进度条
        float barY = y + 58f;
        float barH = 10f;
        float barW = w - 32f;
        RectF track = new RectF(x + 16f, barY, x + 16f + barW, barY + barH);
        p.setColor(gaugeTrack);
        canvas.drawRoundRect(track, barH / 2f, barH / 2f, p);

        if (pct > 0.01f) {
            LinearGradient fg = new LinearGradient(x + 16f, 0, x + 16f + barW, 0,
                    new int[]{gradStart, gradEnd}, null, Shader.TileMode.CLAMP);
            p.setShader(fg);
            RectF fill = new RectF(x + 16f, barY, x + 16f + barW * pct, barY + barH);
            canvas.drawRoundRect(fill, barH / 2f, barH / 2f, p);
            p.setShader(null);
        }

        p.setColor(textTertiary);
        p.setTextSize(10f);
        canvas.drawText(lo, x + 16f, barY + barH + 13f, p);
        p.setColor(gradEnd);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(hi, x + 16f + barW - 8f, barY + barH + 13f, p);
        p.setTypeface(Typeface.DEFAULT);
    }

    // ------------------------------------------------------------ bottom line

    private void drawBottomLine(Canvas canvas) {
        LinearGradient lg = new LinearGradient(0, 0, BASE_W, 0,
                new int[]{0x00FFFFFF, withAlpha(accent, 0.4f), 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        p.setShader(lg);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, BASE_H - 2f, BASE_W, BASE_H, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }
}
