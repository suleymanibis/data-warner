package tr.suleyman.mobilveri;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

final class PageMotion {
    private PageMotion() { }

    static void animate(FrameLayout previous, FrameLayout next, int direction,
                        boolean fullWidth, int shortDistance, Interpolator easing) {
        if (direction == 0 || previous == null || previous.getChildCount() == 0
                || !ValueAnimator.areAnimatorsEnabled()) return;
        View outgoing = previous.getChildAt(previous.getChildCount() - 1);
        View incoming = next.getChildAt(0);
        if (incoming == null || outgoing.getWidth() == 0) return;

        outgoing.animate().cancel();
        outgoing.setAlpha(1f);
        outgoing.setTranslationX(0f);
        previous.removeView(outgoing);
        FrameLayout exit = new FrameLayout(next.getContext()) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
            @Override public boolean onTouchEvent(MotionEvent event) { return true; }
        };
        exit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        exit.addView(outgoing, new FrameLayout.LayoutParams(-1, -1));
        next.addView(exit, 0, new FrameLayout.LayoutParams(-1, -1));

        next.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                next.getViewTreeObserver().removeOnPreDrawListener(this);
                float distance = fullWidth ? next.getWidth() : shortDistance;
                incoming.setTranslationX(direction * distance);
                if (!fullWidth) incoming.setAlpha(0f);
                int duration = fullWidth ? 240 : 210;
                incoming.animate().translationX(0f).alpha(1f).setInterpolator(easing).setDuration(duration).start();
                exit.animate().translationX(-direction * distance).alpha(fullWidth ? 1f : 0f)
                        .setInterpolator(easing).setDuration(duration)
                        .withEndAction(() -> next.removeView(exit)).start();
                return true;
            }
        });
    }
}
