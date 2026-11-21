package tr.suleyman.mobilveri;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.function.IntConsumer;

final class TabSwipeLayout extends FrameLayout {
    private final IntConsumer onSwipe;
    private final int touchSlop;
    private final float minimumDistance;
    private View horizontalControl;
    private float startX;
    private float startY;
    private boolean tracking;
    private boolean swiping;

    TabSwipeLayout(Context context, IntConsumer onSwipe) {
        super(context);
        this.onSwipe = onSwipe;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minimumDistance = Math.max(2 * touchSlop, 56 * getResources().getDisplayMetrics().density);
    }

    void keepHorizontalGesturesOn(View control) {
        horizontalControl = control;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
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
        if (!swiping) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_UP:
                swiping = false;
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                int direction = direction(dx, dy, minimumDistance);
                if (direction != 0) onSwipe.accept(direction);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                swiping = false;
                break;
            default:
                break;
        }
        return true;
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
