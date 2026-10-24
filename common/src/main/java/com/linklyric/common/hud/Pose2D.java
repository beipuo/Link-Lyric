package com.linklyric.common.hud;

/** 极简二维变换栈，避免在 common 里依赖任何渲染库。 */
public interface Pose2D {

    /** 恒等变换：压栈、出栈与变换都不做任何事。 */
    Pose2D IDENTITY = new Pose2D() {
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

    void pushPose();

    void popPose();

    void translate(float x, float y);

    void scale(float x, float y);
}
