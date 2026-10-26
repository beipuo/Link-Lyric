package com.linklyric.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link Pose2D#IDENTITY} 的退化契约：任何变换都必须无声无息地什么也不做。
 *
 * <h2>为什么值得单独钉住</h2>
 *
 * <p>{@code IDENTITY} 是「平台不支持变换」时的兜底实现，也是单测里的替身。
 * 它的价值全部建立在「调用它等于没调用」上：调用方无须判空、无须分支，
 * 直接 {@code push -> translate/rotate -> pop} 即可。
 *
 * <p>这个契约没有编译期保护。若有一天有人把 {@code IDENTITY} 换成带状态的
 * 实现（例如「记录调用次数以便调试」、或「先攒起来等真栈就绪再回放」），
 * 编译照过、测试照跑，但：
 * <ul>
 *   <li>{@code pushPose}/{@code popPose} 一旦记账，未配对的 pop 就会抛异常，
 *       而调用点的 try/finally 是按「空实现不会失败」写的；</li>
 *   <li>变换一旦累积，无变换能力的平台上文字会整体偏移——恰恰是最难归因的
 *       那类故障：没有报错，只是位置不对。</li>
 * </ul>
 *
 * <p>因此这里把「不抛异常、不改变任何东西、始终是同一个实例」三件事一起锁住。
 */
class PoseIdentityTest {

    /** 空实现不得抛异常，且连续调用之间不得有任何副作用。 */
    @Test
    void 变换全部退化为空操作() {
        Pose2D pose = Pose2D.IDENTITY;

        assertDoesNotThrow(() -> {
            pose.pushPose();
            pose.translate(120f, 45f);
            pose.scale(1.8f, 0.6f);
            pose.rotate(37f);
            pose.shear(0.25f, -0.5f);
            pose.translate(-120f, -45f);
            pose.rotate(-90f);
            pose.shear(-0.25f, 0f);
            pose.popPose();
            // 未配对的出栈也不该出事：空实现没有栈可弹。
            pose.popPose();
        }, "IDENTITY 的任何变换都必须静默退化");
    }

    /** 极端参数同样不得抛异常——退化实现不该对取值范围有任何期待。 */
    @Test
    void 极端参数也不抛异常() {
        Pose2D pose = Pose2D.IDENTITY;

        assertDoesNotThrow(() -> {
            pose.rotate(0f);
            pose.rotate(-360f);
            pose.rotate(1e9f);
            pose.rotate(Float.NaN);
            pose.shear(0f, 0f);
            pose.shear(-1e6f, 1e6f);
            pose.shear(Float.POSITIVE_INFINITY, Float.NaN);
            pose.translate(Float.NEGATIVE_INFINITY, 0f);
            pose.scale(0f, -0f);
        }, "退化实现对参数不做校验，也就不该因参数而失败");
    }

    /**
     * 调用前后必须是同一个对象、且仍是{@code IDENTITY}。
     *
     * <p>如果哪天改成「返回一个可变的新实例」，{@code IDENTITY} 这个名字就撒谎了：
     * 常量字段被当作「恒等变换」使用，却带着上一次调用的残留状态。
     */
    @Test
    void 常量始终是同一个恒等实例() {
        Pose2D first = Pose2D.IDENTITY;
        first.pushPose();
        first.rotate(45f);
        first.shear(0.5f, 0.5f);
        first.scale(3f, 3f);
        first.popPose();

        assertSame(first, Pose2D.IDENTITY, "IDENTITY 不得在调用过程中被替换");
        assertEquals(Pose2D.IDENTITY.getClass(), first.getClass(),
                "IDENTITY 的运行时类型必须稳定");
    }

    /**
     * 默认实现本身就退化，不依赖 {@code IDENTITY}。
     *
     * <p>这条测的是<b>另一个</b>实现，因为它防的是另一件事：
     * 第三方实现与测试替身若是「只实现了四个旧方法」的匿名类，
     * 走到这里的正是接口上的 {@code default} 方法体。
     * 它必须安静地什么都不做——若默认实现改成抛
     * {@code UnsupportedOperationException}，这些实现会在运行期炸掉，
     * 而编译期毫无征兆。
     */
    @Test
    void 接口默认实现同样退化() {
        // 只实现四个基础方法，刻意不覆写 rotate / shear。
        Pose2D minimal = new Pose2D() {
            @Override
            public void pushPose() {
            }

            @Override
            public void popPose() {
            }

            @Override
            public void translate(float x, float y) {
            }

            @Override
            public void scale(float x, float y) {
            }
        };

        assertDoesNotThrow(() -> {
            minimal.pushPose();
            minimal.rotate(90f);
            minimal.shear(1f, 1f);
            minimal.popPose();
        }, "rotate / shear 的默认实现必须退化，不得要求实现方跟进");
    }
}
