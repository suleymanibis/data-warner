package tr.suleyman.mobilveri;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

final class UsageScrollView extends ScrollView {
    private float lastY;
    private int activePointer = -1;

    UsageScrollView(Context context, int edgeColor) {
        super(context);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setEdgeEffectColor(edgeColor);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            lastY = event.getY();
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= 31 || !ValueAnimator.areAnimatorsEnabled() || getChildCount() == 0) {
            return super.onTouchEvent(event);
        }

        View child = getChildAt(0);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                child.animate().cancel();
                child.setTranslationY(0f);
                activePointer = event.getPointerId(0);
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                activePointer = -1;
                settle(child);
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(activePointer);
                if (index >= 0) {
                    float y = event.getY(index);
                    float delta = y - lastY;
                    float offset = child.getTranslationY();
                    if (offset != 0 && Math.signum(delta) != Math.signum(offset)) {
                        offset = Math.signum(offset) * Math.max(0f, Math.abs(offset) - Math.abs(delta));
                    } else if ((delta > 0 && !canScrollVertically(-1) && canScrollVertically(1))
                            || (delta < 0 && !canScrollVertically(1) && canScrollVertically(-1))) {
                        float limit = 18f * getResources().getDisplayMetrics().density;
                        offset += delta * 0.22f * (1f - Math.abs(offset) / limit);
                        offset = Math.max(-limit, Math.min(limit, offset));
                    }
                    child.setTranslationY(offset);
                    lastY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                settle(child);
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void settle(View child) {
        if (child.getTranslationY() == 0f) return;
        child.animate().translationY(0f).setDuration(230).setInterpolator(new DecelerateInterpolator()).start();
    }
}
