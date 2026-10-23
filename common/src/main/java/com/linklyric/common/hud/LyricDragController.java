package com.linklyric.common.hud;

import com.linklyric.common.config.LayoutConfig;
import com.linklyric.common.style.LyricStyleConfig;

/**
 * 歌词拖动交互。
 *
 * <p>拖动期间锁定为「按下点 + 累计位移」，而不是每帧读取光标位置：
 * 这样光标即使短暂离开可拖区域，歌词也不会跳变，松手前始终跟随手指。
 *
 * <p>位置以屏幕中心为原点保存为相对偏移，因此改变窗口大小或 GUI 缩放后
 * 歌词仍停在画面上的相同相对位置，不会跑到屏幕外。
 */
public final class LyricDragController {

    /** 判定为拖动的最小位移（像素）；低于此值视为点击，不移动。 */
    private static final int DRAG_THRESHOLD = 2;

    private final FloatingLyricRenderer renderer;
    private final LayoutConfig config;

    /** 按下时的偏移量，拖动过程中以此为基准叠加位移。 */
    private int startOffsetX;
    private int startOffsetY;
    /** 按下时的光标位置。 */
    private int pressX;
    private int pressY;
    private boolean active;
    /** 等待第一次鼠标移动来确定基准点（指令入口专用）。 */
    private boolean waitingForCursor;
    /** 调整会话的起始位置，用于取消回滚。 */
    private int sessionOriginX;
    private int sessionOriginY;

    public LyricDragController(FloatingLyricRenderer renderer, LayoutConfig config) {
        this.renderer = renderer;
        this.config = config;
        if (config != null) {
            renderer.setOffset(config.offsetX(), config.offsetY());
            renderer.setEnabled(config.enabled());
            renderer.setStyle(config.style());
            // 恢复上次的源开关：禁用的源不该在重启后又自己跑起来。
            String id = renderer.sourceId();
            if (config.isSourceDisabled(id)) {
                renderer.setSourceEnabled(false);
            }
        }
    }

    /** 读取当前样式，供配置界面显示。 */
    public LyricStyleConfig style() {
        return renderer.style();
    }

    /**
     * 应用样式并落盘。
     *
     * <p>渲染器立刻拿到新值，因此配置界面的预览是实时的：
     * 不需要「应用」按钮，改一下就变一下。
     */
    public void setStyle(LyricStyleConfig value) {
        renderer.setStyle(value);
        persist();
    }

    /** 打开歌词显示并落盘。 */
    public void enable() {
        renderer.setEnabled(true);
        persist();
    }

    /** 关闭歌词显示并落盘。 */
    public void disable() {
        renderer.setEnabled(false);
        persist();
    }

    public boolean isEnabled() {
        return renderer.isEnabled();
    }

    /**
     * 数据源是否生效。
     *
     * <p>「生效」= 用户启用 且 连接仍在。两者分开显示会让玩家困惑，
     * 界面上只呈现一个可切换的开关。
     */
    public boolean isSourceEnabled() {
        return renderer.sourceEnabled();
    }

    /**
     * 启用或禁用数据源并落盘。
     *
     * <p>禁用只停止出画，不停止轮询：重新启用时立刻有数据，
     * 不必等待下一次连接。
     */
    public void setSourceEnabled(boolean value) {
        renderer.setSourceEnabled(value);
        if (config != null) {
            config.saveSourceEnabled(renderer.sourceId(), value);
        }
    }

    /** 把当前内存状态写盘；配置缺失时只保留内存值。 */
    private void persist() {
        if (config != null) {
            config.save(renderer.offsetX(), renderer.offsetY(),
                    renderer.isEnabled(), renderer.style());
        }
    }

    /**
     * 由指令进入拖动模式：不依赖当前光标位置。
     *
     * <p>指令入口拿不到「按在歌词上」这个前提，因此改用「跟随光标」模式：
     * 以当前偏移为基准，记录进入时的光标位置，之后每次鼠标移动都叠加位移。
     * 左键确认、Esc 取消由平台层的输入钩子转发。
     *
     * @return 是否成功进入；已在拖动中返回 false
     */
    public boolean beginAtCenter() {
        return beginFromOffset();
    }

    /** 直接设定偏移并落盘，供指令精确定位使用。 */
    public void setOffset(int x, int y) {
        renderer.setOffset(x, y);
        persist();
    }

    /**
     * 进入跟随模式，以当前偏移为基准。
     *
     * <p>光标基准点延迟到第一次移动时才确定：指令执行时光标位置
     * 与歌词的位置没有关系，若此时就取基准，歌词会瞬间跳到光标处。
     */
    private boolean beginFromOffset() {
        if (active) {
            return false;
        }
        active = true;
        waitingForCursor = true;
        startOffsetX = renderer.offsetX();
        startOffsetY = renderer.offsetY();
        renderer.setDragging(true);
        return true;
    }

    /**
     * 开始拖动。
     *
     * @return 是否已进入拖动态；已在拖动中返回 false
     */
    public boolean begin(int mouseX, int mouseY) {
        if (active) {
            return false;
        }
        active = true;
        pressX = mouseX;
        pressY = mouseY;
        startOffsetX = renderer.offsetX();
        startOffsetY = renderer.offsetY();
        renderer.setDragging(true);
        return true;
    }

    /**
     * 拖动中。
     *
     * <p>位移太小视为手抖，不改位置——否则轻微点击也会把歌词挤偏一两像素。
     */
    public void drag(int mouseX, int mouseY) {
        if (!active) {
            return;
        }
        // 指令入口：以第一次移动的位置作为基准，歌词不会瞬移到光标处。
        if (waitingForCursor) {
            waitingForCursor = false;
            pressX = mouseX;
            pressY = mouseY;
            return;
        }
        int dx = mouseX - pressX;
        int dy = mouseY - pressY;
        if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) {
            return;
        }
        renderer.setOffset(startOffsetX + dx, startOffsetY + dy);
    }

    /**
     * 结束一次拖动，但**不落盘**。
     *
     * <p>位置调整采用「先拖、后确认」的语义：松手只是停止跟随，
     * 位置仍留在屏幕上供玩家继续微调。只有点击保存才写盘。
     * 若在这里落盘，点取消也回不去，撤销就无从实现。
     *
     * @return 是否确实发生过拖动
     */
    public boolean end() {
        if (!active) {
            return false;
        }
        active = false;
        waitingForCursor = false;
        renderer.setDragging(false);
        int x = renderer.offsetX();
        int y = renderer.offsetY();
        return x != startOffsetX || y != startOffsetY;
    }

    /** 结束拖动并立即落盘。供不经过调整界面的直接拖动使用。 */
    public boolean endAndPersist() {
        boolean moved = end();
        if (moved) {
            persist();
        }
        return moved;
    }

    /**
     * 开始一次「先拖后确认」的调整会话。
     *
     * <p>记录进入时的位置作为取消回滚点。整个会话期间不落盘，
     * 由 {@link #commit()} 或 {@link #revert()} 收尾。
     */
    public void beginSession() {
        active = false;
        waitingForCursor = false;
        sessionOriginX = renderer.offsetX();
        sessionOriginY = renderer.offsetY();
        renderer.setDragging(true);
    }

    /** 保存本次调整：落盘并结束会话。 */
    public void commit() {
        renderer.setDragging(false);
        sessionOriginX = renderer.offsetX();
        sessionOriginY = renderer.offsetY();
        persist();
    }

    /** 取消本次调整：回到进入会话时的位置，不落盘。 */
    public void revert() {
        renderer.setDragging(false);
        renderer.setOffset(sessionOriginX, sessionOriginY);
    }

    /** 会话期间的位置是否已被改动。 */
    public boolean sessionDirty() {
        return renderer.offsetX() != sessionOriginX
                || renderer.offsetY() != sessionOriginY;
    }

    /**
     * 光标是否落在歌词上。
     *
     * <p>依赖渲染器上一帧记录的包围盒。关闭状态下恒为 false：
     * 看不见的歌词不该抢鼠标。
     */
    public boolean isCursorOverLyric(int mouseX, int mouseY) {
        if (!renderer.isEnabled()) {
            return false;
        }
        return renderer.bounds().contains(mouseX, mouseY);
    }

    /**
     * 按下：光标在歌词上时才进入拖动。
     *
     * <p>这是「不用先打指令也能拖」的入口。返回 false 表示这次按下与歌词无关，
     * 调用方应把事件原样放行，否则界面按钮会被吞掉。
     */
    public boolean beginAtCursor(int mouseX, int mouseY) {
        if (active) {
            return false;
        }
        if (!isCursorOverLyric(mouseX, mouseY)) {
            return false;
        }
        return begin(mouseX, mouseY);
    }

    /** 取消拖动并回到按下前的位置，用于 Esc 之类的放弃操作。 */
    public void cancel() {
        if (!active) {
            return;
        }
        active = false;
        renderer.setDragging(false);
        renderer.setOffset(startOffsetX, startOffsetY);
    }

    public boolean isActive() {
        return active;
    }

    /** 复位到默认位置并落盘。 */
    public void reset() {
        cancel();
        renderer.setOffset(0, 0);
        persist();
    }
}
