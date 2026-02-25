import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import javax.swing.Timer;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

public class BubbleShooter extends JPanel implements ActionListener, MouseMotionListener, MouseListener, KeyListener {

    // ── Constants ──────────────────────────────────────────────────────────────
    private final int WIDTH = 640, HEIGHT = 780;
    private final int BR = 38;
    private final Color[] COLORS = {
            new Color(255, 80, 80),
            new Color(60, 160, 255),
            new Color(80, 220, 120),
            new Color(255, 210, 50),
            new Color(200, 80, 255),
            new Color(255, 140, 40)
    };

    // ── Game State ─────────────────────────────────────────────────────────────
    enum GameState {
        MENU, PLAYING, PAUSED, GAME_OVER
    }

    private GameState state = GameState.MENU;

    private Timer gameTimer;
    private long frameCount = 0;

    // Bubbles
    private Bubble currentBubble;
    private Bubble[] bubbleQueue = new Bubble[3];
    private List<Bubble> staticBubbles = new ArrayList<>();

    // Effects
    private List<Particle> particles = new ArrayList<>();
    private List<FloatingText> floatingTexts = new ArrayList<>();
    private int shakeOffsetX = 0, shakeOffsetY = 0;
    private int shakeDuration = 0, shakeIntensity = 0;

    // Stats
    private int score = 0, level = 1, combo = 0, shotsCount = 0, totalPopped = 0;
    private int highScore = 0;

    // Power-ups
    private int powerupLightning = 2;
    private int powerupRainbow = 1;
    private int powerupShield = 1;
    private boolean shieldActive = false;
    private boolean rainbowActive = false;

    // Achievements
    private boolean[] achievements = new boolean[5];
    private String[] achNames = { "First Pop!", "Combo King", "Bomb Expert", "Sharp Shooter", "High Scorer" };
    private String[] achDesc = { "Pop 3+ bubbles", "Reach combo x5", "Use a bomb", "100 shots fired", "Score 5000+" };
    private List<String> pendingAch = new ArrayList<>();
    private long toastTimer = 0;

    // Mouse / shooting
    private Point mousePos = new Point(320, 700);
    private boolean isShooting = false;

    // Background stars
    private int[] starX = new int[180];
    private int[] starY = new int[180];
    private int[] starSz = new int[180];
    private float[] starBr = new float[180];

    // UI rects
    private final Rectangle btnPlay = new Rectangle(WIDTH / 2 - 100, 320, 200, 50);
    private final Rectangle btnScores = new Rectangle(WIDTH / 2 - 100, 390, 200, 50);
    private final Rectangle btnPause = new Rectangle(WIDTH - 55, 8, 45, 34);
    private final Rectangle btnLight = new Rectangle(10, HEIGHT - 58, 60, 45);
    private final Rectangle btnRainbow = new Rectangle(80, HEIGHT - 58, 60, 45);
    private final Rectangle btnShieldB = new Rectangle(150, HEIGHT - 58, 60, 45);

    private final String HS_FILE = System.getProperty("user.home") + "/.bubbleshooter_hs.txt";

    // ── Constructor ────────────────────────────────────────────────────────────
    public BubbleShooter() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
        addMouseMotionListener(this);
        addMouseListener(this);
        addKeyListener(this);

        Random rng = new Random();
        for (int i = 0; i < 180; i++) {
            starX[i] = rng.nextInt(WIDTH);
            starY[i] = rng.nextInt(HEIGHT);
            starSz[i] = rng.nextInt(3) + 1;
            starBr[i] = rng.nextFloat();
        }
        loadHighScore();
        gameTimer = new Timer(16, this);
        gameTimer.start();
    }

    // ── Init ───────────────────────────────────────────────────────────────────
    private void startGame() {
        staticBubbles.clear();
        particles.clear();
        floatingTexts.clear();
        score = 0;
        level = 1;
        combo = 0;
        shotsCount = 0;
        totalPopped = 0;
        shieldActive = false;
        rainbowActive = false;
        powerupLightning = 2;
        powerupRainbow = 1;
        powerupShield = 1;
        achievements = new boolean[5];
        pendingAch.clear();
        toastTimer = 0;
        createLevelRows(4);
        for (int i = 0; i < 3; i++)
            bubbleQueue[i] = makeRandBubble(0, 0);
        spawnNext();
        state = GameState.PLAYING;
    }

    private void createLevelRows(int rows) {
        for (int row = 0; row < rows; row++) {
            int count = (WIDTH / BR) + (row % 2 == 0 ? 0 : -1);
            for (int col = 0; col < count; col++) {
                int x = col * BR + (row % 2 == 0 ? 1 : BR / 2 + 1);
                int y = 62 + row * (BR - 4);
                staticBubbles.add(makeRandBubble(x, y));
            }
        }
    }

    private Bubble makeRandBubble(int x, int y) {
        double r = Math.random();
        if (r < 0.10)
            return new Bubble(x, y, null, BType.BOMB);
        if (r < 0.14)
            return new Bubble(x, y, null, BType.STONE);
        return new Bubble(x, y, randColor(), BType.NORMAL);
    }

    private void spawnNext() {
        BType t = rainbowActive ? BType.RAINBOW : bubbleQueue[0].type;
        Color c = rainbowActive ? null : bubbleQueue[0].color;
        currentBubble = new Bubble(WIDTH / 2 - BR / 2, HEIGHT - 140, c, t);
        bubbleQueue[0] = bubbleQueue[1];
        bubbleQueue[1] = bubbleQueue[2];
        bubbleQueue[2] = makeRandBubble(0, 0);
        isShooting = false;
        rainbowActive = false;
    }

    private Color randColor() {
        return COLORS[new Random().nextInt(COLORS.length)];
    }

    // ── Paint ──────────────────────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Screen shake
        if (shakeDuration > 0) {
            shakeOffsetX = (int) ((Math.random() * 2 - 1) * shakeIntensity * (shakeDuration / 18.0));
            shakeOffsetY = (int) ((Math.random() * 2 - 1) * shakeIntensity * (shakeDuration / 18.0));
            shakeDuration--;
        } else {
            shakeOffsetX = 0;
            shakeOffsetY = 0;
        }
        g2.translate(shakeOffsetX, shakeOffsetY);

        drawBackground(g2);

        if (state == GameState.MENU) {
            drawMenu(g2);
        } else {
            drawGameArea(g2);
            if (state == GameState.PAUSED)
                drawPause(g2);
            if (state == GameState.GAME_OVER)
                drawGameOver(g2);
        }
        g2.translate(-shakeOffsetX, -shakeOffsetY);
    }

    // ── Background ────────────────────────────────────────────────────────────
    private void drawBackground(Graphics2D g2) {
        GradientPaint bg = new GradientPaint(0, 0, new Color(5, 5, 20), 0, HEIGHT, new Color(15, 5, 35));
        g2.setPaint(bg);
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        for (int i = 0; i < 180; i++) {
            float tw = (float) (0.5 + 0.5 * Math.sin(frameCount * 0.04 + i));
            float al = Math.max(0.1f, Math.min(1f, starBr[i] * tw));
            g2.setColor(new Color(1f, 1f, 1f, al));
            g2.fillOval(starX[i], starY[i], starSz[i], starSz[i]);
        }
        nebula(g2, 120, 260, 130, new Color(80, 20, 120, 14));
        nebula(g2, 520, 380, 110, new Color(20, 60, 130, 14));
    }

    private void nebula(Graphics2D g2, int cx, int cy, int r, Color c) {
        for (int i = r; i > 0; i -= 15) {
            float a = (float) (r - i) / r * 0.35f;
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (a * 255)));
            g2.fillOval(cx - i, cy - i, i * 2, i * 2);
        }
    }

    // ── MENU ──────────────────────────────────────────────────────────────────
    private void drawMenu(Graphics2D g2) {
        g2.setFont(new Font("Courier New", Font.BOLD, 56));
        String title = "BUBBLE ULTRA";
        int tw = g2.getFontMetrics().stringWidth(title);
        for (int b = 12; b > 0; b -= 3) {
            g2.setColor(new Color(150, 60, 255, 18));
            g2.drawString(title, WIDTH / 2 - tw / 2 + b / 2, 225 + b / 2);
        }
        GradientPaint tg = new GradientPaint(0, 180, new Color(200, 100, 255), 0, 235, new Color(80, 180, 255));
        g2.setPaint(tg);
        g2.drawString(title, WIDTH / 2 - tw / 2, 225);

        g2.setFont(new Font("Courier New", Font.PLAIN, 14));
        g2.setColor(new Color(150, 130, 200));
        String sub = "★  SPACE EDITION  ★";
        g2.drawString(sub, WIDTH / 2 - g2.getFontMetrics().stringWidth(sub) / 2, 255);

        g2.setFont(new Font("Courier New", Font.BOLD, 16));
        g2.setColor(new Color(255, 210, 80));
        String hs = "HIGH SCORE: " + String.format("%06d", highScore);
        g2.drawString(hs, WIDTH / 2 - g2.getFontMetrics().stringWidth(hs) / 2, 296);

        menuBtn(g2, btnPlay, "▶  PLAY", new Color(100, 200, 255), btnPlay.contains(mousePos));
        menuBtn(g2, btnScores, "★  SCORES", new Color(255, 200, 80), btnScores.contains(mousePos));

        g2.setFont(new Font("Courier New", Font.PLAIN, 12));
        g2.setColor(new Color(100, 90, 140));
        String[] hints = { "MOUSE — aim & shoot", "1/2/3 — Power-ups", "ESC — pause" };
        for (int i = 0; i < hints.length; i++) {
            int hw = g2.getFontMetrics().stringWidth(hints[i]);
            g2.drawString(hints[i], WIDTH / 2 - hw / 2, 488 + i * 22);
        }

        for (int i = 0; i < 8; i++) {
            double t = frameCount * 0.015 + i * 0.8;
            int bx = (int) (80 + i * 70 + Math.sin(t) * 20), by = (int) (600 + Math.cos(t * 0.7) * 25);
            simpleBubble(g2, bx, by, 26, COLORS[i % COLORS.length]);
        }
    }

    private void menuBtn(Graphics2D g2, Rectangle r, String text, Color ac, boolean hov) {
        g2.setColor(new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), hov ? 80 : 30));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
        g2.setColor(new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), hov ? 220 : 140));
        g2.setStroke(new BasicStroke(hov ? 2f : 1.5f));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
        g2.setFont(new Font("Courier New", Font.BOLD, 18));
        g2.setColor(ac);
        int tw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, r.x + r.width / 2 - tw / 2, r.y + 32);
    }

    // ── GAME AREA ─────────────────────────────────────────────────────────────
    private void drawGameArea(Graphics2D g2) {
        drawHUD(g2);
        // danger zone
        g2.setColor(new Color(255, 60, 60, 35));
        g2.fillRect(0, HEIGHT - 167, WIDTH, 3);
        GradientPaint sep = new GradientPaint(0, HEIGHT - 167, new Color(255, 40, 40, 0), 0, HEIGHT - 147,
                new Color(255, 40, 40, 55));
        g2.setPaint(sep);
        g2.fillRect(0, HEIGHT - 167, WIDTH, 20);

        drawAimLine(g2);
        for (Bubble b : new ArrayList<>(staticBubbles))
            b.draw(g2, frameCount);
        if (state != GameState.GAME_OVER)
            currentBubble.draw(g2, frameCount);
        drawQueuePanel(g2);
        drawPowerBar(g2);
        drawShooterBase(g2);
        for (Particle p : new ArrayList<>(particles))
            p.draw(g2);
        for (FloatingText ft : new ArrayList<>(floatingTexts))
            ft.draw(g2);
        drawAchToast(g2);
    }

    private void drawHUD(Graphics2D g2) {
        GradientPaint hbg = new GradientPaint(0, 0, new Color(20, 10, 50, 230), 0, 52, new Color(10, 5, 30, 210));
        g2.setPaint(hbg);
        g2.fillRect(0, 0, WIDTH, 52);
        g2.setColor(new Color(120, 60, 255, 140));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(0, 52, WIDTH, 52);

        hudBox(g2, 8, 4, 155, "SCORE", String.format("%06d", score), new Color(100, 200, 255));
        hudBox(g2, WIDTH / 2 - 55, 4, 110, "LEVEL", String.valueOf(level), new Color(255, 200, 80));
        if (combo > 1)
            hudBox(g2, WIDTH - 178, 4, 122, "COMBO", "x" + combo, new Color(255, 100, 200));

        // Pause btn
        boolean hp = btnPause.contains(mousePos);
        g2.setColor(new Color(80, 60, 120, hp ? 160 : 100));
        g2.fillRoundRect(btnPause.x, btnPause.y, btnPause.width, btnPause.height, 8, 8);
        g2.setColor(new Color(180, 150, 255, hp ? 255 : 180));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(btnPause.x, btnPause.y, btnPause.width, btnPause.height, 8, 8);
        g2.setFont(new Font("Courier New", Font.BOLD, 18));
        g2.drawString("II", btnPause.x + 9, btnPause.y + 24);

        // Level bar
        int pw = 180, px = WIDTH / 2 - pw / 2;
        g2.setColor(new Color(40, 20, 80));
        g2.fillRoundRect(px, 55, pw, 5, 3, 3);
        float prog = Math.min(1f, (float) score / (level * 350));
        GradientPaint pp = new GradientPaint(px, 0, new Color(120, 60, 255), px + pw, 0, new Color(60, 180, 255));
        g2.setPaint(pp);
        g2.fillRoundRect(px, 55, (int) (pw * prog), 5, 3, 3);
    }

    private void hudBox(Graphics2D g2, int x, int y, int w, String label, String val, Color ac) {
        g2.setColor(new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), 20));
        g2.fillRoundRect(x, y + 1, w, 44, 8, 8);
        g2.setColor(new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y + 1, w, 44, 8, 8);
        g2.setFont(new Font("Courier New", Font.BOLD, 9));
        g2.setColor(new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), 170));
        g2.drawString(label, x + 6, y + 15);
        if (!val.isEmpty()) {
            g2.setFont(new Font("Courier New", Font.BOLD, 18));
            g2.setColor(ac);
            g2.drawString(val, x + 6, y + 40);
        }
    }

    private void drawShooterBase(Graphics2D g2) {
        int cx = WIDTH / 2, cy = HEIGHT - 140;
        RadialGradientPaint gl = new RadialGradientPaint(cx, cy + 15, 90, new float[] { 0f, 1f },
                new Color[] { new Color(120, 60, 255, 45), new Color(0, 0, 0, 0) });
        g2.setPaint(gl);
        g2.fillOval(cx - 90, cy - 20, 180, 90);
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        GradientPaint bp = new GradientPaint(cx - 65, cy, new Color(120, 60, 255), cx + 65, cy,
                new Color(60, 180, 255));
        g2.setPaint(bp);
        g2.drawArc(cx - 65, cy - 22, 130, 80, 0, 180);
        g2.setColor(new Color(200, 150, 255));
        g2.fillOval(cx - 5, cy + 8, 10, 10);
    }

    private void drawAimLine(Graphics2D g2) {
        if (isShooting || state != GameState.PLAYING)
            return;
        int cx = WIDTH / 2, cy = HEIGHT - 140;
        if (mousePos.y >= cy)
            return;
        double dx = mousePos.x - cx, dy = mousePos.y - cy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1)
            return;
        dx /= len;
        dy /= len;

        float[] dash = { 10f, 8f };
        g2.setStroke(
                new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, (float) (frameCount % 18)));
        g2.setColor(new Color(255, 255, 255, 55));
        double ex = cx, ey = cy, vdx = dx, vdy = dy;
        for (int seg = 0; seg < 3; seg++) {
            double nx = ex + vdx * 220, ny = ey + vdy * 220;
            if (nx < BR || nx > WIDTH - BR) {
                double t2 = vdx < 0 ? (ex - BR) / (-vdx) : (WIDTH - BR - ex) / vdx;
                t2 = Math.min(t2, 220);
                double mx = ex + vdx * t2, my = ey + vdy * t2;
                g2.drawLine((int) ex, (int) ey, (int) mx, (int) my);
                vdx *= -1;
                ex = mx;
                ey = my;
            } else {
                g2.drawLine((int) ex, (int) ey, (int) nx, (int) ny);
                ex = nx;
                ey = ny;
            }
            if (ey < 62)
                break;
        }
        g2.setStroke(new BasicStroke(1));
        for (int d = 30; d < 260; d += 45) {
            int px2 = (int) (cx + dx * d), py2 = (int) (cy + dy * d);
            if (py2 > 62) {
                float a = 1f - d / 260f;
                g2.setColor(new Color(1f, 1f, 1f, a * 0.5f));
                g2.fillOval(px2 - 4, py2 - 4, 8, 8);
            }
        }
    }

    // ── Queue Panel (next 3) ──────────────────────────────────────────────────
    private void drawQueuePanel(Graphics2D g2) {
        int px = WIDTH - 130, py = HEIGHT - 175;
        g2.setColor(new Color(40, 20, 80, 180));
        g2.fillRoundRect(px - 8, py - 28, 125, 150, 14, 14);
        g2.setColor(new Color(120, 60, 255, 90));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(px - 8, py - 28, 125, 150, 14, 14);
        g2.setFont(new Font("Courier New", Font.BOLD, 9));
        g2.setColor(new Color(150, 120, 255));
        g2.drawString("NEXT 3", px + 18, py - 10);

        int[] sz = { 32, 26, 20 };
        for (int i = 0; i < 3; i++) {
            if (bubbleQueue[i] != null) {
                smallBubble(g2, bubbleQueue[i], px + 10, py + i * 42, sz[i]);
                g2.setFont(new Font("Courier New", Font.BOLD, 9));
                g2.setColor(new Color(180, 160, 220));
                g2.drawString("#" + (i + 1), px + sz[i] + 14, py + i * 42 + sz[i] / 2 + 4);
            }
        }
    }

    private void smallBubble(Graphics2D g2, Bubble b, int x, int y, int size) {
        switch (b.type) {
            case BOMB:
                g2.setColor(new Color(50, 50, 50));
                g2.fillOval(x, y, size, size);
                g2.setColor(new Color(255, 80, 0, 150));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(x, y, size, size);
                g2.setFont(new Font("Courier New", Font.BOLD, size / 2));
                g2.setColor(new Color(255, 100, 0));
                g2.drawString("B", x + size / 4, y + size * 3 / 4);
                break;
            case STONE:
                g2.setColor(new Color(110, 115, 125));
                g2.fillOval(x, y, size, size);
                g2.setColor(new Color(180, 185, 195, 100));
                g2.setStroke(new BasicStroke(1f));
                g2.drawOval(x, y, size, size);
                break;
            case RAINBOW:
                Color[] rc = { Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.BLUE, new Color(150, 0, 255) };
                for (int i = 0; i < rc.length; i++) {
                    g2.setColor(rc[i]);
                    g2.fillArc(x, y, size, size, i * 60, 60);
                }
                g2.setColor(new Color(255, 255, 255, 100));
                g2.setStroke(new BasicStroke(1f));
                g2.drawOval(x, y, size, size);
                break;
            default:
                Color c = b.color != null ? b.color : Color.WHITE;
                g2.setColor(c);
                g2.fillOval(x, y, size, size);
                g2.setColor(new Color(255, 255, 255, 80));
                g2.setStroke(new BasicStroke(1f));
                g2.drawOval(x, y, size, size);
                break;
        }
    }

    // ── Power-up Bar ──────────────────────────────────────────────────────────
    private void drawPowerBar(Graphics2D g2) {
        g2.setColor(new Color(20, 10, 45, 200));
        g2.fillRoundRect(8, HEIGHT - 62, 220, 54, 12, 12);
        g2.setColor(new Color(100, 60, 200, 80));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(8, HEIGHT - 62, 220, 54, 12, 12);
        powerBtn(g2, btnLight, "⚡", String.valueOf(powerupLightning), new Color(255, 230, 50), "[1]",
                powerupLightning > 0);
        powerBtn(g2, btnRainbow, "★", String.valueOf(powerupRainbow), new Color(180, 100, 255), "[2]",
                powerupRainbow > 0);
        powerBtn(g2, btnShieldB, "O", String.valueOf(powerupShield), new Color(80, 200, 255), "[3]", powerupShield > 0);
    }

    private void powerBtn(Graphics2D g2, Rectangle r, String icon, String cnt, Color ac, String key, boolean avail) {
        Color bg = avail ? new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), 55) : new Color(50, 50, 50, 80);
        g2.setColor(bg);
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setColor(avail ? new Color(ac.getRed(), ac.getGreen(), ac.getBlue(), 180) : new Color(80, 80, 80));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setFont(new Font("Courier New", Font.BOLD, 20));
        g2.setColor(avail ? ac : new Color(80, 80, 80));
        g2.drawString(icon, r.x + 7, r.y + 30);
        g2.setFont(new Font("Courier New", Font.BOLD, 9));
        g2.setColor(avail ? Color.WHITE : new Color(80, 80, 80));
        g2.drawString("x" + cnt, r.x + 32, r.y + 14);
        g2.setFont(new Font("Courier New", Font.PLAIN, 8));
        g2.setColor(new Color(150, 130, 200));
        g2.drawString(key, r.x + 30, r.y + 30);
    }

    // ── Pause Screen ──────────────────────────────────────────────────────────
    private void drawPause(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 155));
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        int bx = WIDTH / 2 - 140, by = HEIGHT / 2 - 115;
        g2.setColor(new Color(25, 10, 55, 245));
        g2.fillRoundRect(bx, by, 280, 230, 18, 18);
        GradientPaint brd = new GradientPaint(bx, by, new Color(120, 60, 255), bx + 280, by + 230,
                new Color(60, 180, 255));
        g2.setPaint(brd);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(bx, by, 280, 230, 18, 18);
        g2.setFont(new Font("Courier New", Font.BOLD, 36));
        g2.setColor(new Color(180, 140, 255));
        g2.drawString("PAUSED", WIDTH / 2 - 80, HEIGHT / 2 - 58);
        g2.setFont(new Font("Courier New", Font.PLAIN, 14));
        g2.setColor(new Color(150, 200, 255));
        g2.drawString("Score  : " + String.format("%06d", score), WIDTH / 2 - 78, HEIGHT / 2 - 10);
        g2.drawString("Level   : " + level, WIDTH / 2 - 78, HEIGHT / 2 + 15);
        g2.drawString("Shots   : " + shotsCount, WIDTH / 2 - 78, HEIGHT / 2 + 40);
        g2.drawString("Popped : " + totalPopped, WIDTH / 2 - 78, HEIGHT / 2 + 65);
        if ((frameCount / 30) % 2 == 0) {
            g2.setFont(new Font("Courier New", Font.BOLD, 13));
            g2.setColor(new Color(200, 150, 255));
            g2.drawString("[ ESC / CLICK TO RESUME ]", WIDTH / 2 - 105, HEIGHT / 2 + 100);
        }
    }

    // ── Game Over ─────────────────────────────────────────────────────────────
    private void drawGameOver(Graphics2D g2) {
        GradientPaint ov = new GradientPaint(0, 0, new Color(0, 0, 0, 185), 0, HEIGHT, new Color(20, 0, 40, 215));
        g2.setPaint(ov);
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        int bx = WIDTH / 2 - 165, by = HEIGHT / 2 - 145;
        g2.setColor(new Color(25, 8, 55, 245));
        g2.fillRoundRect(bx, by, 330, 295, 20, 20);
        GradientPaint brd = new GradientPaint(bx, by, new Color(200, 80, 255), bx + 330, by + 295,
                new Color(80, 160, 255));
        g2.setPaint(brd);
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(bx, by, 330, 295, 20, 20);

        g2.setFont(new Font("Courier New", Font.BOLD, 44));
        for (int b = 10; b > 0; b -= 2) {
            g2.setColor(new Color(200, 60, 255, 18));
            g2.drawString("GAME OVER", WIDTH / 2 - 130 + b / 2, HEIGHT / 2 - 80 + b / 2);
        }
        g2.setColor(new Color(255, 100, 255));
        g2.drawString("GAME OVER", WIDTH / 2 - 130, HEIGHT / 2 - 80);

        g2.setFont(new Font("Courier New", Font.PLAIN, 14));
        g2.setColor(new Color(180, 220, 255));
        g2.drawString("SCORE", WIDTH / 2 - 120, HEIGHT / 2 - 28);
        g2.drawString("LEVEL", WIDTH / 2 - 120, HEIGHT / 2 - 3);
        g2.drawString("SHOTS", WIDTH / 2 - 120, HEIGHT / 2 + 22);
        g2.drawString("POPPED", WIDTH / 2 - 120, HEIGHT / 2 + 47);
        g2.setFont(new Font("Courier New", Font.BOLD, 14));
        g2.setColor(new Color(100, 200, 255));
        g2.drawString(String.format("%06d", score), WIDTH / 2 + 20, HEIGHT / 2 - 28);
        g2.setColor(new Color(255, 200, 80));
        g2.drawString(String.valueOf(level), WIDTH / 2 + 20, HEIGHT / 2 - 3);
        g2.setColor(new Color(200, 255, 200));
        g2.drawString(String.valueOf(shotsCount), WIDTH / 2 + 20, HEIGHT / 2 + 22);
        g2.setColor(new Color(200, 255, 200));
        g2.drawString(String.valueOf(totalPopped), WIDTH / 2 + 20, HEIGHT / 2 + 47);

        if (score > 0 && score >= highScore) {
            g2.setFont(new Font("Courier New", Font.BOLD, 12));
            g2.setColor(new Color(255, 220, 50));
            g2.drawString("★  NEW HIGH SCORE!  ★", WIDTH / 2 - 88, HEIGHT / 2 + 78);
        }
        if ((frameCount / 30) % 2 == 0) {
            g2.setFont(new Font("Courier New", Font.BOLD, 13));
            g2.setColor(new Color(200, 150, 255));
            g2.drawString("[ CLICK TO PLAY AGAIN ]", WIDTH / 2 - 100, HEIGHT / 2 + 115);
        }
    }

    // ── Achievement Toast ─────────────────────────────────────────────────────
    private void drawAchToast(Graphics2D g2) {
        if (pendingAch.isEmpty())
            return;
        String msg = pendingAch.get(0);
        int tw = 310, th = 46, tx = WIDTH / 2 - tw / 2, ty = 64;
        g2.setColor(new Color(25, 55, 25, 215));
        g2.fillRoundRect(tx, ty, tw, th, 12, 12);
        g2.setColor(new Color(70, 210, 70, 180));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(tx, ty, tw, th, 12, 12);
        g2.setFont(new Font("Courier New", Font.BOLD, 10));
        g2.setColor(new Color(140, 255, 140));
        g2.drawString("ACHIEVEMENT UNLOCKED!", tx + 12, ty + 17);
        g2.setFont(new Font("Courier New", Font.PLAIN, 12));
        g2.setColor(Color.WHITE);
        g2.drawString("★  " + msg, tx + 12, ty + 34);
    }

    // ── Game Logic ────────────────────────────────────────────────────────────
    @Override
    public void actionPerformed(ActionEvent e) {
        frameCount++;
        if (state == GameState.PLAYING && isShooting) {
            currentBubble.move();
            checkCollisions();
        }
        // update effects
        particles.removeIf(p -> {
            p.update();
            return p.isDead();
        });
        floatingTexts.removeIf(ft -> {
            ft.update();
            return ft.isDead();
        });
        // achievement toasts
        if (!pendingAch.isEmpty()) {
            toastTimer++;
            if (toastTimer > 160) {
                pendingAch.remove(0);
                toastTimer = 0;
            }
        }
        repaint();
    }

    private void checkCollisions() {
        if (currentBubble.x <= 0 || currentBubble.x >= WIDTH - BR)
            currentBubble.vx *= -1;
        boolean hit = false;
        if (currentBubble.y <= 62)
            hit = true;
        if (!hit)
            for (Bubble b : staticBubbles) {
                if (currentBubble.dist(b) < BR - 6) {
                    hit = true;
                    break;
                }
            }
        if (!hit)
            return;

        if (currentBubble.type == BType.BOMB) {
            shake(12, 18);
            explode((int) currentBubble.x + BR / 2, (int) currentBubble.y + BR / 2, new Color(255, 150, 0));
            List<Bubble> rm = new ArrayList<>();
            for (Bubble b : staticBubbles)
                if (currentBubble.dist(b) < BR * 3.8 && b.type != BType.STONE) {
                    rm.add(b);
                    spawnParts((int) b.x + BR / 2, (int) b.y + BR / 2, b.color != null ? b.color : Color.ORANGE, 6);
                }
            staticBubbles.removeAll(rm);
            int pts = rm.size() * 20;
            score += pts;
            totalPopped += rm.size();
            floatingTexts.add(new FloatingText("BOOM! +" + pts, (int) currentBubble.x, (int) currentBubble.y - 25,
                    new Color(255, 150, 0)));
            combo++;
            checkAch(2);

        } else if (currentBubble.type == BType.RAINBOW) {
            Bubble proxy = new Bubble(currentBubble.x, currentBubble.y, randColor(), BType.NORMAL);
            staticBubbles.add(proxy);
            List<Bubble> best = null;
            int bestSz = 0;
            for (Color c : COLORS) {
                proxy.color = c;
                Set<Bubble> m = new HashSet<>();
                findMatches(proxy, c, m);
                if (m.size() >= 3 && m.size() > bestSz) {
                    bestSz = m.size();
                    best = new ArrayList<>(m);
                }
            }
            if (best != null) {
                staticBubbles.removeAll(best);
                int pts = best.size() * 15;
                score += pts;
                totalPopped += best.size();
                combo++;
                for (Bubble b : best)
                    spawnParts((int) b.x + BR / 2, (int) b.y + BR / 2, b.color, 8);
                floatingTexts.add(new FloatingText("RAINBOW +" + pts, (int) currentBubble.x, (int) currentBubble.y - 25,
                        new Color(200, 150, 255)));
            } else {
                staticBubbles.remove(proxy);
                floatingTexts.add(new FloatingText("No match!", (int) currentBubble.x, (int) currentBubble.y - 25,
                        new Color(180, 120, 255)));
            }
        } else {
            Bubble nb = new Bubble(currentBubble.x, currentBubble.y, currentBubble.color, BType.NORMAL);
            staticBubbles.add(nb);
            handleMatch(nb);
        }

        // danger check
        boolean danger = false;
        for (Bubble b : staticBubbles) {
            if (b.y > HEIGHT - 170) {
                danger = true;
                break;
            }
        }
        if (danger) {
            if (shieldActive) {
                shieldActive = false;
                for (Bubble b : staticBubbles)
                    b.y -= 22;
                floatingTexts.add(
                        new FloatingText("SHIELD SAVED YOU!", WIDTH / 2 - 85, HEIGHT / 2, new Color(80, 200, 255)));
            } else {
                endGame();
                return;
            }
        }
        levelUp();
        spawnNext();
    }

    private void handleMatch(Bubble tgt) {
        Set<Bubble> m = new HashSet<>();
        findMatches(tgt, tgt.color, m);
        if (m.size() >= 3) {
            staticBubbles.removeAll(m);
            int pts = m.size() * 10;
            if (combo > 1)
                pts = (int) (pts * (1 + combo * 0.3));
            score += pts;
            totalPopped += m.size();
            combo++;
            shake(3, 6);
            for (Bubble b : m)
                spawnParts((int) b.x + BR / 2, (int) b.y + BR / 2, b.color, 9);
            String txt = combo > 2 ? "COMBO x" + combo + " +" + pts : "+" + pts;
            Color tc = combo > 2 ? new Color(255, 100, 200) : new Color(100, 255, 150);
            floatingTexts.add(new FloatingText(txt, (int) tgt.x, (int) tgt.y - 20, tc));
            checkAch(0);
            if (combo >= 5)
                checkAch(1);
            if (score >= 5000)
                checkAch(4);
        } else {
            combo = Math.max(1, combo - 1);
        }
    }

    private void findMatches(Bubble b, Color tgt, Set<Bubble> m) {
        if (b == null || m.contains(b) || b.type == BType.BOMB || b.type == BType.STONE || b.color == null
                || !b.color.equals(tgt))
            return;
        m.add(b);
        for (Bubble n : staticBubbles)
            if (b.dist(n) < BR + 10)
                findMatches(n, tgt, m);
    }

    private void levelUp() {
        if (score > level * 350) {
            level++;
            for (Bubble b : staticBubbles)
                b.y += 24;
            int cnt = WIDTH / BR;
            for (int col = 0; col < cnt; col++)
                staticBubbles.add(0, makeRandBubble(col * BR + 1, 64));
            powerupLightning++;
            floatingTexts
                    .add(new FloatingText("LEVEL UP! " + level, WIDTH / 2 - 65, HEIGHT / 2, new Color(255, 220, 50)));
            shake(5, 10);
        }
    }

    private void endGame() {
        state = GameState.GAME_OVER;
        if (score > highScore) {
            highScore = score;
            saveHighScore();
        }
    }

    // ── Power-ups ────────────────────────────────────────────────────────────
    private void useLightning() {
        if (powerupLightning <= 0 || state != GameState.PLAYING)
            return;
        powerupLightning--;
        double maxY = 0;
        for (Bubble b : staticBubbles)
            if (b.y > maxY)
                maxY = b.y;
        List<Bubble> row = new ArrayList<>();
        for (Bubble b : staticBubbles)
            if (Math.abs(b.y - maxY) < BR / 2 && b.type != BType.STONE)
                row.add(b);
        staticBubbles.removeAll(row);
        int pts = row.size() * 12;
        score += pts;
        totalPopped += row.size();
        for (Bubble b : row)
            spawnParts((int) b.x + BR / 2, (int) b.y + BR / 2, new Color(255, 230, 50), 8);
        floatingTexts
                .add(new FloatingText("LIGHTNING! +" + pts, WIDTH / 2 - 80, HEIGHT / 2 - 40, new Color(255, 230, 50)));
        shake(4, 8);
        beep(880, 120);
    }

    private void useRainbow() {
        if (powerupRainbow <= 0 || state != GameState.PLAYING)
            return;
        powerupRainbow--;
        rainbowActive = true;
        floatingTexts
                .add(new FloatingText("RAINBOW READY!", WIDTH / 2 - 75, HEIGHT / 2 - 40, new Color(200, 150, 255)));
        beep(660, 100);
    }

    private void useShield() {
        if (powerupShield <= 0 || state != GameState.PLAYING)
            return;
        powerupShield--;
        shieldActive = true;
        floatingTexts.add(new FloatingText("SHIELD ACTIVE!", WIDTH / 2 - 70, HEIGHT / 2 - 40, new Color(80, 200, 255)));
        beep(550, 100);
    }

    // ── Achievements ─────────────────────────────────────────────────────────
    private void checkAch(int idx) {
        if (!achievements[idx]) {
            achievements[idx] = true;
            pendingAch.add(achNames[idx] + " — " + achDesc[idx]);
        }
    }

    // ── Effects ───────────────────────────────────────────────────────────────
    private void spawnParts(int x, int y, Color c, int n) {
        Random r = new Random();
        for (int i = 0; i < n; i++)
            particles.add(new Particle(x, y, c, r));
    }

    private void explode(int x, int y, Color c) {
        Random r = new Random();
        for (int i = 0; i < 35; i++)
            particles.add(new Particle(x, y, c, r));
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            Particle p = new Particle(x, y, new Color(255, 200, 100), r);
            p.vx = Math.cos(a) * 7;
            p.vy = Math.sin(a) * 7;
            p.size = 7;
            particles.add(p);
        }
    }

    private void shake(int intensity, int duration) {
        shakeIntensity = intensity;
        shakeDuration = duration;
    }

    private void beep(int freq, int ms) {
        new Thread(() -> {
            try {
                AudioFormat af = new AudioFormat(44100, 8, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
                if (!AudioSystem.isLineSupported(info))
                    return;
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(af, 4096);
                line.start();
                byte[] buf = new byte[(int) (44100 * ms / 1000.0)];
                for (int i = 0; i < buf.length; i++)
                    buf[i] = (byte) (Math.sin(2 * Math.PI * i * freq / 44100) * 60);
                line.write(buf, 0, buf.length);
                line.drain();
                line.close();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void simpleBubble(Graphics2D g2, int x, int y, int r, Color c) {
        RadialGradientPaint p = new RadialGradientPaint(new Point2D.Double(x + r * 0.35, y + r * 0.3), r * 0.9f,
                new float[] { 0f, 1f }, new Color[] { lighten(c, 0.5f), c.darker() });
        g2.setPaint(p);
        g2.fillOval(x, y, r, r);
        g2.setColor(new Color(255, 255, 255, 55));
        g2.setStroke(new BasicStroke(1f));
        g2.drawOval(x, y, r, r);
    }

    // ── High Score ───────────────────────────────────────────────────────────
    private void loadHighScore() {
        try {
            highScore = Integer.parseInt(Files.readString(Path.of(HS_FILE)).trim());
        } catch (Exception e) {
            highScore = 0;
        }
    }

    private void saveHighScore() {
        try {
            Files.writeString(Path.of(HS_FILE), String.valueOf(highScore));
        } catch (Exception ignored) {
        }
    }

    // ── Color util ───────────────────────────────────────────────────────────
    private Color lighten(Color c, float f) {
        return new Color(Math.min(255, (int) (c.getRed() + (255 - c.getRed()) * f)),
                Math.min(255, (int) (c.getGreen() + (255 - c.getGreen()) * f)),
                Math.min(255, (int) (c.getBlue() + (255 - c.getBlue()) * f)));
    }

    // ── Input ────────────────────────────────────────────────────────────────
    public void mouseMoved(MouseEvent e) {
        mousePos = e.getPoint();
        repaint();
    }

    public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        Point p = e.getPoint();
        if (state == GameState.MENU) {
            if (btnPlay.contains(p)) {
                beep(440, 80);
                startGame();
            } else if (btnScores.contains(p))
                showScores();
            return;
        }
        if (state == GameState.GAME_OVER) {
            startGame();
            return;
        }
        if (state == GameState.PAUSED) {
            state = GameState.PLAYING;
            return;
        }
        // PLAYING
        if (btnPause.contains(p)) {
            state = GameState.PAUSED;
            return;
        }
        if (btnLight.contains(p)) {
            useLightning();
            return;
        }
        if (btnRainbow.contains(p)) {
            useRainbow();
            return;
        }
        if (btnShieldB.contains(p)) {
            useShield();
            return;
        }
        if (!isShooting) {
            int cx = WIDTH / 2, cy = HEIGHT - 140;
            if (e.getY() < cy) {
                double ang = Math.atan2(e.getY() - cy, e.getX() - cx);
                currentBubble.vx = Math.cos(ang) * 14;
                currentBubble.vy = Math.sin(ang) * 14;
                isShooting = true;
                shotsCount++;
                if (shotsCount >= 100)
                    checkAch(3);
                beep(330, 60);
            }
        }
    }

    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_ESCAPE) {
            if (state == GameState.PLAYING)
                state = GameState.PAUSED;
            else if (state == GameState.PAUSED)
                state = GameState.PLAYING;
        }
        if (state == GameState.PLAYING) {
            if (k == KeyEvent.VK_1)
                useLightning();
            else if (k == KeyEvent.VK_2)
                useRainbow();
            else if (k == KeyEvent.VK_3)
                useShield();
        }
    }

    private void showScores() {
        StringBuilder sb = new StringBuilder("HIGH SCORE: " + String.format("%06d", highScore) + "\n\nAchievements:\n");
        boolean any = false;
        for (int i = 0; i < achievements.length; i++) {
            if (achievements[i]) {
                sb.append("★ ").append(achNames[i]).append("\n");
                any = true;
            }
        }
        if (!any)
            sb.append("(none yet)\n\n").append("Unlock by playing!");
        JOptionPane.showMessageDialog(this, sb.toString(), "Scores & Achievements", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Inner Classes ─────────────────────────────────────────────────────────
    enum BType {
        NORMAL, BOMB, STONE, RAINBOW
    }

    class Bubble {
        double x, y, vx, vy;
        Color color;
        BType type;
        float ph;

        Bubble(double x, double y, Color c, BType t) {
            this.x = x;
            this.y = y;
            color = c;
            type = t;
            ph = (float) (Math.random() * Math.PI * 2);
        }

        void move() {
            x += vx;
            y += vy;
        }

        double dist(Bubble b) {
            return Math.sqrt(Math.pow(x - b.x, 2) + Math.pow(y - b.y, 2));
        }

        void draw(Graphics2D g, long fc) {
            int ix = (int) x, iy = (int) y;
            float pulse = (float) (0.95 + 0.05 * Math.sin(fc * 0.08 + ph));
            switch (type) {
                case BOMB:
                    dBomb(g, ix, iy, fc);
                    break;
                case STONE:
                    dStone(g, ix, iy);
                    break;
                case RAINBOW:
                    dRainbow(g, ix, iy, fc);
                    break;
                default:
                    dColor(g, ix, iy, pulse);
            }
        }

        private void dColor(Graphics2D g, int ix, int iy, float pulse) {
            Color c = color != null ? color : Color.WHITE;
            int r = (int) (BR * pulse), off = (BR - r) / 2;
            RadialGradientPaint og = new RadialGradientPaint(new Point2D.Double(ix + BR / 2.0, iy + BR / 2.0),
                    BR * 1.3f, new float[] { 0f, 1f },
                    new Color[] { new Color(c.getRed(), c.getGreen(), c.getBlue(), 55), new Color(0, 0, 0, 0) });
            g.setPaint(og);
            g.fillOval(ix - (int) (BR * 0.3), iy - (int) (BR * 0.3), (int) (BR * 1.6), (int) (BR * 1.6));
            RadialGradientPaint body = new RadialGradientPaint(
                    new Point2D.Double(ix + off + r * 0.35, iy + off + r * 0.35), r * 0.9f,
                    new float[] { 0f, 0.6f, 1f }, new Color[] { lighten(c, 0.5f), c, c.darker().darker() });
            g.setPaint(body);
            g.fillOval(ix + off, iy + off, r, r);
            RadialGradientPaint sp = new RadialGradientPaint(
                    new Point2D.Double(ix + off + r * 0.28, iy + off + r * 0.22), r * 0.38f, new float[] { 0f, 1f },
                    new Color[] { new Color(255, 255, 255, 185), new Color(255, 255, 255, 0) });
            g.setPaint(sp);
            g.fillOval(ix + off, iy + off, r, r);
            g.setColor(new Color(255, 255, 255, 55));
            g.setStroke(new BasicStroke(1.2f));
            g.drawOval(ix + off, iy + off, r - 1, r - 1);
        }

        private void dBomb(Graphics2D g, int ix, int iy, long fc) {
            int cx2 = ix + BR / 2, cy2 = iy + BR / 2;
            float gp = (float) (0.5 + 0.5 * Math.sin(fc * 0.15 + ph));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx2, cy2), BR * 1.5f, new float[] { 0f, 1f },
                    new Color[] { new Color(255, 80, 0, (int) (130 * gp)), new Color(0, 0, 0, 0) }));
            g.fillOval(ix - BR / 2, iy - BR / 2, BR * 2, BR * 2);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx2 - 5, cy2 - 5), BR * 0.6f, new float[] { 0f, 1f },
                    new Color[] { new Color(90, 90, 90), new Color(15, 15, 15) }));
            g.fillOval(ix + 2, iy + 2, BR - 4, BR - 4);
            g.setColor(new Color(200, 150, 50));
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(cx2 + 5, iy + 3, cx2 + 13, iy - 9);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx2 + 13, iy - 9), 7f, new float[] { 0f, 1f },
                    new Color[] { new Color(255, 255, 100, (int) (255 * gp)), new Color(255, 100, 0, 0) }));
            g.fillOval(cx2 + 7, iy - 15, 12, 12);
            g.setFont(new Font("Courier New", Font.BOLD, 14));
            g.setColor(new Color(255, 80, 0, 200));
            g.drawString("B", cx2 - 5, cy2 + 6);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx2 - 6, cy2 - 6), BR * 0.25f, new float[] { 0f, 1f },
                    new Color[] { new Color(255, 255, 255, 100), new Color(255, 255, 255, 0) }));
            g.fillOval(ix + 4, iy + 4, BR / 2, BR / 2);
        }

        private void dStone(Graphics2D g, int ix, int iy) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(ix + BR * 0.35, iy + BR * 0.35), BR * 0.8f,
                    new float[] { 0f, 1f }, new Color[] { new Color(165, 170, 182), new Color(70, 75, 85) }));
            g.fillOval(ix + 2, iy + 2, BR - 4, BR - 4);
            g.setColor(new Color(200, 205, 215, 75));
            g.setStroke(new BasicStroke(1f));
            g.drawOval(ix + 2, iy + 2, BR - 4, BR - 4);
            int cx2 = ix + BR / 2, cy2 = iy + BR / 2;
            g.setColor(new Color(50, 55, 65, 180));
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(cx2, cy2 - 8, cx2 - 6, cy2 + 5);
            g.drawLine(cx2, cy2 - 8, cx2 + 5, cy2 + 3);
        }

        private void dRainbow(Graphics2D g, int ix, int iy, long fc) {
            int r = BR - 2;
            Color[] rc = { Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.BLUE, new Color(150, 0, 255) };
            for (int i = 0; i < rc.length; i++) {
                g.setColor(rc[i]);
                g.fillArc(ix + 1, iy + 1, r, r, i * 60, 60);
            }
            float gp = (float) (0.5 + 0.5 * Math.sin(fc * 0.1 + ph));
            g.setColor(new Color(255, 255, 255, (int) (100 * gp)));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(ix + 1, iy + 1, r, r);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(ix + r * 0.3, iy + r * 0.25), r * 0.35f,
                    new float[] { 0f, 1f },
                    new Color[] { new Color(255, 255, 255, 160), new Color(255, 255, 255, 0) }));
            g.fillOval(ix, iy, r + 2, r + 2);
        }
    }

    class Particle {
        double x, y, vx, vy;
        Color color;
        int life, maxLife;
        float size;

        Particle(int x, int y, Color c, Random r) {
            this.x = x;
            this.y = y;
            double ang = r.nextDouble() * Math.PI * 2, spd = 2 + r.nextDouble() * 5.5;
            vx = Math.cos(ang) * spd;
            vy = Math.sin(ang) * spd - 2;
            color = c != null ? c : new Color(255, 200, 50);
            life = 40 + r.nextInt(30);
            maxLife = life;
            size = 4 + r.nextFloat() * 7;
        }

        void update() {
            x += vx;
            y += vy;
            vy += 0.18;
            vx *= 0.96;
            life--;
        }

        boolean isDead() {
            return life <= 0;
        }

        void draw(Graphics2D g) {
            float a = (float) life / maxLife, s = size * a;
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (a * 220)));
            g.fillOval((int) (x - s / 2), (int) (y - s / 2), (int) s, (int) s);
            g.setColor(new Color(255, 255, 255, (int) (a * 90)));
            g.fillOval((int) (x - s / 4), (int) (y - s / 4), (int) (s / 2), (int) (s / 2));
        }
    }

    class FloatingText {
        String text;
        double x, y;
        Color color;
        int life = 85;
        float scale = 0;

        FloatingText(String t, int x, int y, Color c) {
            text = t;
            this.x = x;
            this.y = y;
            color = c;
        }

        void update() {
            y -= 1.3;
            life--;
            scale = Math.min(1f, scale + 0.12f);
        }

        boolean isDead() {
            return life <= 0;
        }

        void draw(Graphics2D g) {
            if ((int) (16 * scale) < 6)
                return;
            float a = life < 30 ? life / 30f : 1f;
            g.setFont(new Font("Courier New", Font.BOLD, (int) (16 * scale)));
            g.setColor(new Color(0, 0, 0, (int) (a * 140)));
            g.drawString(text, (int) x + 2, (int) y + 2);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (a * 255)));
            g.drawString(text, (int) x, (int) y);
        }
    }

    // ── Unused listeners ──────────────────────────────────────────────────────
    public void mouseDragged(MouseEvent e) {
    }

    public void mouseClicked(MouseEvent e) {
    }

    public void mouseReleased(MouseEvent e) {
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }

    public void keyReleased(KeyEvent e) {
    }

    public void keyTyped(KeyEvent e) {
    }

    // ── Main ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("BUBBLE SHOOTER ULTRA");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            BubbleShooter game = new BubbleShooter();
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}
