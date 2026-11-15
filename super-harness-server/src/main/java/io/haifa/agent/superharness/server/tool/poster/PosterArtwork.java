package io.haifa.agent.superharness.server.tool.poster;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.imageio.ImageIO;

final class PosterArtwork {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1440;
    private static final int QR_SIZE = 192;
    private static final Color BACKGROUND = new Color(0x0e1923);
    private static final Color WHITE = new Color(0xfff4e4);
    private static final Color SECONDARY = new Color(0xbccbd3);
    private static final Color ACCENT = new Color(0xffaf62);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM.dd");

    private PosterArtwork() {}

    static BufferedImage readVisual(byte[] bytes) throws IOException {
        var readers = ImageIO.getImageReadersByFormatName("PNG");
        if (!readers.hasNext()) throw new IOException("PNG reader unavailable");
        var reader = readers.next();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 256 || height < 256 || width > 4096 || height > 4096) {
                throw new IllegalArgumentException("主视觉 PNG 的每边长度须在 256 到 4096 像素之间。");
            }
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    static BitMatrix qr(String url) throws WriterException {
        return url.isEmpty() ? null : new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
                Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.ERROR_CORRECTION,
                        ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
    }

    static BufferedImage render(String layout, PosterRenderTool.Recipe recipe, BufferedImage visual, BitMatrix qr) {
        var canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(canvas);
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            if ("a".equals(layout)) {
                text(g, "活动招募  /  A", 72, 62, 29, 936, ACCENT, true);
                text(g, recipe.title(), 68, 118, 92, 936, WHITE, true);
                text(g, recipe.subtitle(), 72, 231, 43, 936, SECONDARY, false);
                crop(g, visual, 72, 336, 936, 605);
            } else {
                crop(g, visual, 0, 0, WIDTH, 760);
                text(g, "活动招募  /  B", 72, 806, 28, 936, ACCENT, true);
                text(g, recipe.title(), 68, 854, 86, 936, WHITE, true);
                text(g, recipe.subtitle(), 72, 956, 39, 936, SECONDARY, false);
            }
            text(g, dates(recipe), 72, 1020, 44, 936, ACCENT, true);
            g.setColor(new Color(0x41525e));
            g.fillRect(72, 1100, 936, 2);
            text(g, recipe.details(), 72, 1150, 37, qr == null ? 936 : 712, WHITE, false);
            if (qr != null) {
                drawQr(g, qr, 1008 - QR_SIZE, 1130);
                text(g, recipe.demo() ? "扫码测试" : "扫码报名", 826, 1334, 27, 180, WHITE, false);
            } else {
                text(g, "报名链接待补", 72, 1260, 28, 800, SECONDARY, false);
            }
            if (recipe.demo()) text(g, "流程演示 · 日期与二维码为测试数据", 72, 1376, 23, 936, SECONDARY, false);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    static BufferedImage compare(BufferedImage a, BufferedImage b) {
        var preview = new BufferedImage(424, 316, BufferedImage.TYPE_INT_RGB);
        var g = graphics(preview);
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, 424, 316);
            text(g, "A  标题优先", 12, 11, 17, 192, WHITE, true);
            text(g, "B  主视觉优先", 220, 11, 17, 192, WHITE, true);
            g.drawImage(a, 12, 39, 192, 256, null);
            g.drawImage(b, 220, 39, 192, 256, null);
        } finally {
            g.dispose();
        }
        return preview;
    }

    static byte[] png(BufferedImage image) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private static String dates(PosterRenderTool.Recipe recipe) {
        LocalDate start = LocalDate.parse(recipe.startDate());
        if (recipe.endDate().isEmpty() || recipe.endDate().equals(recipe.startDate())) {
            return FULL_DATE.format(start);
        }
        LocalDate end = LocalDate.parse(recipe.endDate());
        return FULL_DATE.format(start) + " — "
                + (start.getYear() == end.getYear() ? SHORT_DATE : FULL_DATE).format(end);
    }

    private static Graphics2D graphics(BufferedImage image) {
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    private static void text(Graphics2D g, String value, int x, int y, int size, int width, Color color,
                             boolean bold) {
        var font = new Font("Microsoft YaHei", bold ? Font.BOLD : Font.PLAIN, size);
        if (font.canDisplayUpTo(value) >= 0) font = new Font(Font.SANS_SERIF, font.getStyle(), size);
        if (font.canDisplayUpTo(value) >= 0) throw new IllegalArgumentException("本地字体无法显示部分字符。");
        g.setFont(font);
        while (g.getFontMetrics().stringWidth(value) > width && g.getFont().getSize() > 15) {
            g.setFont(g.getFont().deriveFont((float) g.getFont().getSize() - 1));
        }
        g.setColor(color);
        g.drawString(value, x, y + g.getFontMetrics().getAscent());
    }

    private static void crop(Graphics2D g, BufferedImage image, int x, int y, int width, int height) {
        double ratio = Math.max((double) width / image.getWidth(), (double) height / image.getHeight());
        int newWidth = (int) Math.ceil(image.getWidth() * ratio);
        int newHeight = (int) Math.ceil(image.getHeight() * ratio);
        var clipped = (Graphics2D) g.create(x, y, width, height);
        try {
            clipped.drawImage(image, (width - newWidth) / 2, (height - newHeight) / 2, newWidth, newHeight, null);
        } finally {
            clipped.dispose();
        }
    }

    private static void drawQr(Graphics2D g, BitMatrix qr, int x, int y) {
        g.setColor(Color.WHITE);
        g.fillRect(x, y, qr.getWidth(), qr.getHeight());
        g.setColor(Color.BLACK);
        for (int row = 0; row < qr.getHeight(); row++) {
            for (int col = 0; col < qr.getWidth(); col++) {
                if (qr.get(col, row)) g.fillRect(x + col, y + row, 1, 1);
            }
        }
    }
}
