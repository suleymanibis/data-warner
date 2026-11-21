package tr.suleyman.mobilveri;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

final class TabSwipeLayout extends FrameLayout {
    private final IntConsumer onSwipe;
    private final IntFunction<View> neighbor;
    private final int touchSlop;
    private final float minimumDistance;
    private View horizontalControl;
    private float startX;
    private float startY;
    private boolean tracking;
    private boolean swiping;
    private boolean settling;
    private int previewDirection;
    private View preview;
    private Runnable afterGesture;

    TabSwipeLayout(Context context, IntFunction<View> neighbor, IntConsumer onSwipe) {
        super(context);
        this.neighbor = neighbor;
        this.onSwipe = onSwipe;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minimumDistance = Math.max(2 * touchSlop, 56 * getResources().getDisplayMetrics().density);
    }

    void keepHorizontalGesturesOn(View control) {
        horizontalControl = control;
    }

    void whenGestureEnds(Runnable action) {
        if (swiping || settling) afterGesture = action;
        else action.run();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (settling) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                tracking = !hitsHorizontalControl(event);
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                float dx = Math.abs(event.getX() - startX);
                float dy = Math.abs(event.getY() - startY);
                if (dx > 2 * touchSlop && dx > 1.5f * dy) {
                    swiping = true;
                    movePages(event.getX() - startX);
                    return true;
                }
                if (dy > touchSlop && dy >= dx) tracking = false;
                break;
            default:
                tracking = false;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (settling) return true;
        if (!swiping) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                movePages(event.getX() - startX);
                break;
            case MotionEvent.ACTION_UP:
                swiping = false;
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                int direction = direction(dx, dy, minimumDistance);
                settle(direction == previewDirection ? direction : 0);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                swiping = false;
                settle(0);
                break;
            default:
                break;
        }
        return true;
    }

    private void movePages(float drag) {
        int direction = drag < 0 ? 1 : -1;
        if (direction != previewDirection) {
            if (preview != null) removeView(preview);
            previewDirection = direction;
            preview = neighbor.apply(direction);
            if (preview != null) {
                preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                addView(preview, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        float distance = preview == null ? 0 : Math.max(-getWidth(), Math.min(getWidth(), drag));
        getChildAt(0).setTranslationX(distance);
        if (preview != null) preview.setTranslationX(distance + direction * getWidth());
    }

    private void settle(int direction) {
        if (preview == null) {
            previewDirection = 0;
            finishDeferredAction();
            return;
        }
        View current = getChildAt(0);
        View incoming = preview;
        float destination = direction == 0 ? 0 : -direction * getWidth();
        Runnable finish = () -> {
            removeView(incoming);
            preview = null;
            previewDirection = 0;
            settling = false;
            current.setTranslationX(0);
            if (direction != 0) onSwipe.accept(direction);
            finishDeferredAction();
        };
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish.run();
            return;
        }
        settling = true;
        DecelerateInterpolator ease = new DecelerateInterpolator();
        incoming.animate().translationX(destination + previewDirection * getWidth()).setDuration(180)
                .setInterpolator(ease).start();
        current.animate().translationX(destination).setDuration(180).setInterpolator(ease).withEndAction(finish).start();
    }

    private void finishDeferredAction() {
        Runnable deferred = afterGesture;
        afterGesture = null;
        if (deferred != null) deferred.run();
    }

    static int direction(float dx, float dy, float minimumDistance) {
        if (Math.abs(dx) < minimumDistance || Math.abs(dx) <= 1.5f * Math.abs(dy)) return 0;
        return dx < 0 ? 1 : -1;
    }

    private boolean hitsHorizontalControl(MotionEvent event) {
        if (horizontalControl == null || !horizontalControl.isShown()
                || (!horizontalControl.canScrollHorizontally(-1) && !horizontalControl.canScrollHorizontally(1))) {
            return false;
        }
        Rect visible = new Rect();
        if (!horizontalControl.getLocalVisibleRect(visible)) return false;
        int[] location = new int[2];
        horizontalControl.getLocationOnScreen(location);
        visible.offset(location[0], location[1]);
        return visible.contains((int) event.getRawX(), (int) event.getRawY());
    }
}
